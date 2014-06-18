package scala.virtualization.lms
package common

import util.GraphUtil
import scala.collection.mutable.{HashMap, HashSet}

// TODO document interface
trait LoopFusionHorizontalTransformer extends PreservingForwardTransformer { 
  val IR: LoopFusionCore
  import IR.{__newVar => _, _}

  /** Sets of vertically fused loops (with effect flag: true if effectful)
    * must be horizontally fused if they have survived DCE, otherwise we're
    * duplicating work. */
  private var verticallyFusedSyms = HashMap[Sym[Any], (List[Sym[Any]], Boolean)]()
  def setVerticallyFusedSyms(map: HashMap[Sym[Any], (List[Sym[Any]], Boolean)]) = { verticallyFusedSyms = map }

  // TODO dedup
  type EffectTuple = Option[(Summary, List[Exp[Any]])]
  object LoopOrReflectedLoop {
    def unapply(a: Def[Any]): Option[(AbstractLoop[_], EffectTuple)] = a match {
      case Reflect(loop: AbstractLoop[_], summ, deps) => Some((loop, Some((summ, deps))))
      case loop: AbstractLoop[_] => Some((loop, None))
      case _ => None
    }
  }
  object IfOrReflectedIf {
    def unapply(a: Def[Any]): Option[(AbstractIfThenElse[_], EffectTuple)] = a match {
      case Reflect(ite: AbstractIfThenElse[_], summ, deps) => Some((ite, Some((summ, deps))))
      case ite: AbstractIfThenElse[_] => Some((ite, None))
      case _ => None
    }
  }

  abstract class FusedSet {
    val syms: List[Sym[Any]]
    val setIndex: Int
    val hasEffects: Boolean
  }
  /** A set of horizontally fused loops.
    * @param shape common shape @param index common index
    * @param syms loop symbols that need to be a part of the set (also loops
    * that haven't been processed yet, but are vertically fused with one of the
    * loops in the set)
    * @param setIndex index of this fusedLoopSet in the FusionScope instance owning it
    * @param innerScope the FusionScope instance used for fusing all inner scopes of the
    * loops in the set. Need to use same instance for each so that we can fuse
    * across (they're different scopes in this schedule, but will be in the
    * same scope of the fat fused loop)
    * @param hasEffects true if the set contains an effectful loop (at most one), it then
    * cannot be fused with any other effectful loops/sets.
    */
  case class FusedLoopSet(shape: Exp[Int], index: Sym[Int], syms: List[Sym[Any]], setIndex: Int,
      innerScope: FusionScope, hasEffects: Boolean) extends FusedSet {
    def addSyms(newSyms: List[Sym[Any]], effectful: Boolean) = {
      assert(!(effectful && hasEffects), "(FTO) ERROR: cannot fuse two effectful sets")
      FusedLoopSet(shape, index, syms ++ newSyms, setIndex, innerScope, hasEffects || effectful)
    }
    override def toString = "FusedLoopSet(shape = " + shape + ", indexSym = " + index + ", loopSyms = " + syms + ")"
  }
  /** A set of horizontally fused Ifs. */
  case class FusedIfSet(cond: Exp[Boolean], syms: List[Sym[Any]], setIndex: Int,
      thenInnerScope: FusionScope, elseInnerScope: FusionScope, hasEffects: Boolean) extends FusedSet {
    def addSym(newSym: Sym[Any], effectful: Boolean) = {
      assert(!(effectful && hasEffects), "(FTO) ERROR: cannot fuse two effectful sets")
      FusedIfSet(cond, syms ++ List(newSym), setIndex, thenInnerScope, elseInnerScope, hasEffects || effectful)
    }
    override def toString = "FusedIfSet(cond = " + cond + ", ifSyms = " + syms + ")"
  }

  /** Fusion sets for a particular fusion scope (can be a combination of several inner
    * scopes if the outer loops have been fused). */
  class FusionScope {
    AllFusionScopes.add(this)
    // All syms here are original syms
    private var loopSets: List[FusedLoopSet] = Nil
    private var ifSets: List[FusedIfSet] = Nil
    // Since we want to prepend new loopSets but still have stable indices, index from back
    private def getLoopSet(setIndex: Int) = loopSets(loopSets.length - 1 - setIndex)
    private def getIfSet(setIndex: Int) = ifSets(ifSets.length - 1 - setIndex)

    // TODO lazy HashMaps?
    // Lookup mandatory fusion set by symbol (sym is vertically fused with a loop in the set)
    private val sym2loopSet = new HashMap[Sym[Any], Int]
    private val sym2ifSet = new HashMap[Sym[Any], Int]
    // Lookup fusion set candidates by shape, need to check independence
    private val shape2loopSets = new HashMap[Exp[Int], List[Int]]
    private val cond2ifSets = new HashMap[Exp[Boolean], List[Int]]

    // realLoopSets(i) contains the symbols from loopSets(i) that were actually fused (not DCE'd)
    // these are new (transformed) syms
    // It also contains one loop of the set so that each loop added to the set can be
    // updated as fused with this one.
    private var realLoopSets: List[(List[Sym[Any]], Option[CanBeFused])] = Nil
    private var realIfSets: List[(List[Sym[Any]], Option[CanBeFused])] = Nil

    def getLoopSet(sym: Sym[Any]): Option[FusedLoopSet] = sym2loopSet.get(sym).map(getLoopSet(_))
    def getIfSet(sym: Sym[Any]): Option[FusedIfSet] = sym2ifSet.get(sym).map(getIfSet(_))
    def getByShape(shape: Exp[Int]): List[FusedLoopSet] = shape2loopSets.get(shape).getOrElse(Nil).map(getLoopSet(_))
    def getByCond(cond: Exp[Boolean]): List[FusedIfSet] = cond2ifSets.get(cond).getOrElse(Nil).map(getIfSet(_))
    def getAllFusedLoops(syms: List[Sym[Any]]): List[Sym[Any]] = (syms ++ syms.flatMap(getLoopSet(_).map(_.syms).getOrElse(Nil)))
    def getAllFusedIfs(syms: List[Sym[Any]]): List[Sym[Any]] = (syms ++ syms.flatMap(getIfSet(_).map(_.syms).getOrElse(Nil)))
    
    // Start a new fusion set
    def recordNewLoop(sym: Sym[Any], shape: Exp[Int], index: Sym[Int], syms: List[Sym[Any]], effectful: Boolean) = {
      val setIndex = loopSets.length
      val set = FusedLoopSet(shape, index, syms, setIndex, new FusionScope, effectful)
      loopSets = set :: loopSets
      realLoopSets ::= (Nil, None)
      val indexList = setIndex :: shape2loopSets.get(shape).getOrElse(Nil)
      shape2loopSets.put(shape, indexList)
      set.syms.foreach({ otherSym => sym2loopSet.put(otherSym, setIndex) match {
        case Some(old) => sys.error("FusedLoopSet already had a set for symbol " + otherSym + ": " + old + " = " 
          + getLoopSet(old) + " instead of new " + set)
        case None =>
      }})
      set.innerScope
    }
    def recordNewIf(sym: Sym[Any], cond: Exp[Boolean], effectful: Boolean) = {
      val setIndex = ifSets.length
      val set = FusedIfSet(cond, List(sym), setIndex, new FusionScope, new FusionScope, effectful)
      ifSets = set :: ifSets
      realIfSets ::= (Nil, None)
      val indexList = setIndex :: cond2ifSets.get(cond).getOrElse(Nil)
      cond2ifSets.put(cond, indexList)
      sym2ifSet.put(sym, setIndex)
      (set.thenInnerScope, set.elseInnerScope)
    }

    // Add the syms to the existing fusion set
    def recordAddLoop(fusedLoopSet: FusedLoopSet, addedSyms: List[Sym[Any]], effectful: Boolean) = {
      val setIndex = fusedLoopSet.setIndex
      addedSyms.foreach(sym2loopSet.put(_, setIndex))
      loopSets = loopSets.updated(loopSets.length - 1 - setIndex, fusedLoopSet.addSyms(addedSyms, effectful))
      fusedLoopSet.innerScope
    }
    def recordAddIf(fusedIfSet: FusedIfSet, addedSym: Sym[Any], effectful: Boolean) = {
      val setIndex = fusedIfSet.setIndex
      sym2ifSet.put(addedSym, setIndex)
      ifSets = ifSets.updated(ifSets.length - 1 - setIndex, fusedIfSet.addSym(addedSym, effectful))
      (fusedIfSet.thenInnerScope, fusedIfSet.elseInnerScope)
    }

    // Record actual fusion resulting in the newSym transformed loop
    def recordRealLoop(sym: Sym[Any], newSym: Exp[Any]): Unit = {
      val setIndex = sym2loopSet(sym)
      val substSym = newSym match { case s@Sym(_) => s case _ => sym }
      val listIndex = realLoopSets.length - 1 - setIndex
      val (oldSet, oldCanBeFused) = realLoopSets(listIndex)

      val newCanBeFused = substSym match {
        case Def(EatReflect(c: CanBeFused)) => oldCanBeFused match {
          case Some(existing) => c.registerFusion(existing); oldCanBeFused
          case None => Some(c)
        }
        case _ => sys.error("Horizontal fusion with something that isn't a CanBeFused: " + substSym +
          " = " + findDefinition(substSym))
      }
      realLoopSets = realLoopSets.updated(listIndex, (substSym :: oldSet, newCanBeFused))
    }
    def recordRealIf(sym: Sym[Any], newSym: Exp[Any]): Unit = {
      val setIndex = sym2ifSet(sym)
      val substSym = newSym match { case s@Sym(_) => s case _ => sym }
      val listIndex = realIfSets.length - 1 - setIndex
      val (oldSet, oldCanBeFused) = realIfSets(listIndex)

      val newCanBeFused = substSym match {
        case Def(EatReflect(c: CanBeFused)) => oldCanBeFused match {
          case Some(existing) => c.registerFusion(existing); oldCanBeFused
          case None => Some(c)
        }
        case _ => sys.error("Horizontal fusion with something that isn't a CanBeFused: " + substSym +
          " = " + findDefinition(substSym))
      }
      realIfSets = realIfSets.updated(listIndex, (substSym :: oldSet, newCanBeFused))
    }
    def getRealLoops = realLoopSets.unzip._1.filter(_.length > 1).map(_.reverse.distinct)

    override def toString() = "FusionScope(" + loopSets.mkString("\n") + ")"
  }

  /** Records all fusion scopes and loads correct scope for
    * reflecting inner blocks. */
  object AllFusionScopes {
    private var allFusionScope: List[FusionScope] = Nil
    def add(f: FusionScope) = allFusionScope ::= f
    def get: List[List[Sym[Any]]] = allFusionScope.flatMap(_.getRealLoops)

    // Record inner scope that should be used before mirroring blocks
    private val blockToFused = new HashMap[Block[Any], FusionScope]()
    def set(blocks: List[Block[Any]], fused: FusionScope) = blocks.foreach { block =>
      blockToFused += (block -> fused)
    }
    def get(block: Block[Any]) = blockToFused.get(block).getOrElse(new FusionScope)
    // Remove entries after use to keep map small
    def remove(blocks: List[Block[Any]]) = blocks foreach { block =>
      blockToFused.remove(block)
    }
  }

  // --- per scope datastructures ----
  var current = new FusionScope

  // indented printing to show scopes
  var indent: Int = -2
  def printdbg(x: => Any) { if (verbosity >= 2) System.err.println(" " * indent + x) }
  def printlog(x: => Any) { if (verbosity >= 1) System.err.println(" " * indent + x) }

  // FixPointTransformer methods, horizontal fusion should only run once
  def getInfoString = "LoopFusionHorizontalTransformer only runs once"
  var hasRunOnce = false
  def isDone = hasRunOnce
  def runOnce[A:Manifest](s: Block[A]): Block[A] = {
    val newBlock = transformBlock(s)
    hasRunOnce = true
    newBlock
  }

  // Set correct current fusion scope
  override def reflectBlock[A](block: Block[A]): Exp[A] = {
    val old = current
    current = AllFusionScopes.get(block)
    indent += 2
    val res = super.reflectBlock(block)
    indent -= 2
    current = old
    res
  }

  /* The transformer: loop fusion sets preference is:
   * 1. check if loop contained in existing (because it was vertically fused with existing)
   * 2. check if there's an existing set with correct shape and no dependencies
   * 3. start a new fusion set
   * If-fusion sets: only 2) and then 3)
   */
  override def transformStm(stm: Stm): Exp[Any] = {
    val transfStm = stm match {
      case TP(sym, LoopOrReflectedLoop(loop, effects)) => 
        // fuse with existing set if one found, otherwise start new set
        // fusion just means remapping the loop index to the index used by the set
        // calculate innerScope to be used for transforming the loop body
        val (innerScope, checkIndex) = current.getLoopSet(sym) match {
          case Some(horizontal) => // case 1. loop contained in existing
            printlog("(HFT) Fusing " + sym + " with containing fusion set " + horizontal)
            assert(loop.size == horizontal.shape, "Error: HFT with different shapes")
            val checkIndex = fuse(sym, loop.v, horizontal.index)
            (horizontal.innerScope, checkIndex)

          case None => 
            val (setToFuse, setToFuseEffectful) = 
              verticallyFusedSyms.get(sym).getOrElse((List(sym), effects.isDefined))
            val existing = current.getByShape(loop.size)
              .filter({ candidate => checkIndep(sym, candidate, setToFuse) })
              .filter({ candidate => checkEffects(sym, candidate, setToFuse, setToFuseEffectful) })
              .headOption
            existing match {
              case Some(fusedLoopSet) => // case 2. compatible existing set
                printlog("(HFT) Fusing " + sym + " with fusion set " + fusedLoopSet)
                assert(loop.size == fusedLoopSet.shape, "Error: HFT with different shapes 2")
                val checkIndex = fuse(sym, loop.v, fusedLoopSet.index)
                (current.recordAddLoop(fusedLoopSet, setToFuse, setToFuseEffectful), checkIndex)

              case None => // case 3. start a new fusion set
                printdbg("(HFT) Recording " + sym + ", no fusion")
                (current.recordNewLoop(sym, loop.size, loop.v, setToFuse, setToFuseEffectful), None)
            }
        }
        
        // Do the actual transformation with the correct innerScopes
        // for reflecting the loop body
        AllFusionScopes.set(blocks(loop), innerScope)
        val superTransformedStm = super.transformStm(stm)
        AllFusionScopes.remove(blocks(loop))

        // TODO make log warnings?
        checkIndex.foreach({ index =>
          if (superTransformedStm == sym)
            sys.error("(HFT) ERROR: loop index remapping was not successful, aborting fusion of " + stm + ", mirroring returned the same loop: " + superTransformedStm)
          else {
            superTransformedStm match {
              case Def(LoopOrReflectedLoop(loop, _)) => 
                if (loop.v != index) {
                  sys.error("(HFT) ERROR: loop index remapping to " + index + " was not successful, aborting fusion of " + stm + ", mirroring returned: " + loop)
                } else {
                  // book keeping
                  current.recordRealLoop(sym, superTransformedStm)
                }
              case _ => sys.error("(HFT) ERROR: loop index remapping was not successful, aborting fusion of " + stm +
                ", mirroring returned something that isn't a loop: " + superTransformedStm + " = " + findDefinition(superTransformedStm.asInstanceOf[Sym[Any]]))
            }
          }
        })

        // don't want to change other indices, TODO reset to old (see fuse)
        subst -= loop.v

        if (superTransformedStm != sym) {
          printdbg("(HFT) - new loop symbol: " + sym + " -> " + superTransformedStm)
        }

        Some(superTransformedStm)

      case TP(sym, IfOrReflectedIf(ifthenelse, effects)) => 
        // fuse with existing set if one found, otherwise start new set
        // if-fusion doesn't remap anything, but combines the inner scopes per branch
          
        val setToFuse = List(sym)
        val setToFuseEffectful = effects.isDefined
        val existing = current.getByCond(ifthenelse.cond)
          .filter({ candidate => checkIndep(sym, candidate, setToFuse) })
          .filter({ candidate => checkEffects(sym, candidate, setToFuse, setToFuseEffectful) })
          .headOption
        val (thenInnerScope, elseInnerScope) = existing match {
          case Some(fusedIfSet) => // case 2. compatible existing set
            printlog("(HFT) Fusing " + sym + " with fusion set " + fusedIfSet)
            current.recordAddIf(fusedIfSet, sym, setToFuseEffectful)

          case None => // case 3. start a new fusion set
            printdbg("(HFT) Recording if-sym " + sym + ", no fusion")
            current.recordNewIf(sym, ifthenelse.cond, setToFuseEffectful)
        }
        
        // Do the actual transformation with the correct innerScopes
        // for reflecting the loop body
        AllFusionScopes.set(List(ifthenelse.thenp), thenInnerScope)
        AllFusionScopes.set(List(ifthenelse.elsep), elseInnerScope)
        val superTransformedStm = super.transformStm(stm)
        AllFusionScopes.remove(List(ifthenelse.thenp, ifthenelse.elsep))

        // book keeping
        current.recordRealIf(sym, superTransformedStm)
        if (superTransformedStm != sym)
          printdbg("(HFT) - new if symbol: " + sym + " -> " + superTransformedStm)
        Some(superTransformedStm)

      case _ => None
    }

    transfStm.getOrElse(super.transformStm(stm))
  }

  /** Adds a substitution from the old to the new index. */
  def fuse(sym: Sym[Any], oldIndex: Sym[Int], newIndex: Sym[Int]) = {
    if (oldIndex == newIndex) {
      printdbg("(HFT) - already using same index " + oldIndex)
      None
    } else {
      printdbg("(HFT) - remapping index: " + oldIndex + " -> " + newIndex)
      subst.get(oldIndex) match {
        case Some(`newIndex`) => // already present in subst
        
        // TODO once we implement multiple potential producers mapped to same index
        // we should return the existing index so it can be reset after we've
        // transformed this loop
        // TODO should unique-ify indices
        case Some(existingNew) => sys.error("(HFT) Error: existing remap to " + existingNew + 
            " encountered when fusing " + sym + " by remapping oldIndex " + oldIndex + 
            " to newIndex " + newIndex)
        case None => // new substitution
      }
      subst += (oldIndex -> newIndex)
      Some(newIndex)
    }
  }

  /** Returns true if the existing set and the setToFuse (containing sym)
    * are mutually independent and thus safe to fuse. */
  def checkIndep(sym: Sym[Any], existing: FusedSet, setToFuse: List[Sym[Any]]): Boolean = {
    val existingSet = existing.syms

    // traverse the statements (+fusion sets) needed for the loop
    // and check there are no deps
    // throws exception to stop traversal as soon as dep found
    case class DependencyException(dependsOn: Either[Sym[Any],Sym[Any]]) extends Exception
    try {
      // check both ways - each has to be indep of other
      GraphUtil.stronglyConnectedComponents(setToFuse, { sym: Sym[Any] => 
        findDefinition(sym) match {
          case Some(d) => 
            val next = current.getAllFusedLoops(syms(d.rhs))
            val taboo = next.collectFirst({ case x if existingSet.contains(x) => x })
            if (taboo.isDefined) {
              throw DependencyException(Left(taboo.get))
            }
            next
          case None => List()
        }
      })
      // this is necessary, see fusion30 test for example
      GraphUtil.stronglyConnectedComponents(existingSet, { sym: Sym[Any] => 
        findDefinition(sym) match {
          case Some(d) => 
            val next = current.getAllFusedLoops(syms(d.rhs))
            val taboo = next.collectFirst({ case x if setToFuse.contains(x) => x })
            if (taboo.isDefined) {
              throw DependencyException(Right(taboo.get))
            }
            next
          case None => List()
        }
      })
      true
    } catch {
      case DependencyException(dependsOn) => 
        val setS = if (setToFuse.length > 1) " and its set (" + setToFuse + ")" else ""
        val msg = "(HFT) The candidate " + sym + setS + " cannot be fused with the existing " + existing + " because "
        printdbg(msg + (dependsOn match {
          case Left(existing) => "the candidate set depends on " + existing
          case Right(toFuse) => "the existing set depends on "  + toFuse
        }))
        false
    }
  }

  def checkEffects(sym: Sym[Any], existing: FusedSet, setToFuse: List[Sym[Any]],
      setToFuseEffectful: Boolean): Boolean = {
    // TODO what about order of effects between loops?
    val effectsOk = !(setToFuseEffectful && existing.hasEffects) 
    if (!effectsOk) {
      val setS = if (setToFuse.length > 1) " and its set (" + setToFuse + ")" else ""
      printdbg("(HFT) The candidate " + sym + setS + " cannot be fused with the existing " + existing + " because both are effectful.")
    }
    effectsOk
  }
}