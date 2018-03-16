package dotty.tools.dotc
package transform

import core._
import Decorators._, Flags._, Types._, Contexts._, Symbols._, Constants._
import Flags._
import ast.Trees._
import ast.{TreeTypeMap, untpd}
import util.Positions._
import StdNames._
import tasty.TreePickler.Hole
import MegaPhase.MiniPhase
import SymUtils._
import NameKinds._
import typer.Implicits.SearchFailureType

import scala.collection.mutable
import dotty.tools.dotc.core.StdNames._
import dotty.tools.dotc.core.quoted._


/** Translates quoted terms and types to `unpickle` method calls.
 *  Checks that the phase consistency principle (PCP) holds.
 *
 *
 *  Transforms top level quote
 *   ```
 *   '{ ...
 *      val x1 = ???
 *      val x2 = ???
 *      ...
 *      ~{ ... '{ ... x1 ... x2 ...} ... }
 *      ...
 *    }
 *    ```
 *  to
 *    ```
 *     unpickle(
 *       [[ // PICKLED TASTY
 *         ...
 *         val x1 = ???
 *         val x2 = ???
 *         ...
 *         Hole(0 | x1, x2)
 *         ...
 *       ]],
 *       List(
 *         (args: Seq[Any]) => {
 *           val x1$1 = args(0).asInstanceOf[Expr[T]]
 *           val x2$1 = args(1).asInstanceOf[Expr[T]] // can be asInstanceOf[Type[T]]
 *           ...
 *           { ... '{ ... x1$1.unary_~ ... x2$1.unary_~ ...} ... }
 *         }
 *       )
 *     )
 *    ```
 *  and then performs the same transformation on `'{ ... x1$1.unary_~ ... x2$1.unary_~ ...}`.
 */
class ReifyQuotes extends MacroTransformWithImplicits {
  import ast.tpd._

  override def phaseName: String = "reifyQuotes"

  override def run(implicit ctx: Context): Unit =
    if (ctx.compilationUnit.containsQuotesOrSplices) super.run

  protected def newTransformer(implicit ctx: Context): Transformer =
    new Reifier(inQuote = false, null, 0, new LevelInfo, new mutable.ListBuffer[Tree])

  private class LevelInfo {
    /** A map from locally defined symbols to the staging levels of their definitions */
    val levelOf = new mutable.HashMap[Symbol, Int]

    /** Register a reference defined in a quote but used in another quote nested in a splice.
     *  Returns a lifted version of the reference that needs to be used in its place.
     *     '{
     *        val x = ???
     *        { ... '{ ... x ... } ... }.unary_~
     *      }
     *  Lifting the `x` in `{ ... '{ ... x ... } ... }.unary_~` will return a `x$1.unary_~` for which the `x$1`
     *  be created by some outer reifier.
     *
     *  This transformation is only applied to definitions at staging level 1.
     *
     *  See `needsLifting`
     */
    val lifters = new mutable.HashMap[Symbol, RefTree => Tree]
  }

  /** The main transformer class
   *  @param  inQuote    we are within a `'(...)` context that is not shadowed by a nested `~(...)`
   *  @param  outer      the next outer reifier, null is this is the topmost transformer
   *  @param  level      the current level, where quotes add one and splices subtract one level
   *  @param  levels     a stacked map from symbols to the levels in which they were defined
   *  @param  embedded   a list of embedded quotes (if `inSplice = true`) or splices (if `inQuote = true`
   */
  private class Reifier(inQuote: Boolean, val outer: Reifier, val level: Int, levels: LevelInfo,
      val embedded: mutable.ListBuffer[Tree]) extends ImplicitsTransformer {
    import levels._
    assert(level >= 0)

    /** A nested reifier for a quote (if `isQuote = true`) or a splice (if not) */
    def nested(isQuote: Boolean): Reifier = {
      val nestedEmbedded = if (level > 1 || (level == 1 && isQuote)) embedded else new mutable.ListBuffer[Tree]
      new Reifier(isQuote, this, if (isQuote) level + 1 else level - 1, levels, nestedEmbedded)
    }

    /** We are in a `~(...)` context that is not shadowed by a nested `'(...)` */
    def inSplice = outer != null && !inQuote

    /** A map from type ref T to expressions of type `quoted.Type[T]`".
     *  These will be turned into splices using `addTags`
     */
    val importedTags = new mutable.LinkedHashMap[TypeRef, Tree]()

    /** A stack of entered symbols, to be unwound after scope exit */
    var enteredSyms: List[Symbol] = Nil

    /** Assuming importedTags = `Type1 -> tag1, ..., TypeN -> tagN`, the expression
     *
     *      { type <Type1> = <tag1>.unary_~
     *        ...
     *        type <TypeN> = <tagN>.unary_~
     *        <expr>
     *      }
     *
     *  references to `TypeI` in `expr` are rewired to point to the locally
     *  defined versions. As a side effect, prepend the expressions `tag1, ..., `tagN`
     *  as splices to `embedded`.
     */
    private def addTags(expr: Tree)(implicit ctx: Context): Tree =
      if (importedTags.isEmpty) expr
      else {
        val itags = importedTags.toList
        val typeDefs = for ((tref, tag) <- itags) yield {
          val rhs = transform(tag.select(tpnme.UNARY_~))
          val alias = ctx.typeAssigner.assignType(untpd.TypeBoundsTree(rhs, rhs), rhs, rhs)
          val original = tref.symbol.asType
          val local = original.copy(
            owner = ctx.owner,
            flags = Synthetic,
            info = TypeAlias(tag.tpe.select(tpnme.UNARY_~)))
          ctx.typeAssigner.assignType(untpd.TypeDef(original.name, alias), local)
        }
        importedTags.clear()
        Block(typeDefs,
          new TreeTypeMap(substFrom = itags.map(_._1.symbol), substTo = typeDefs.map(_.symbol))
            .apply(expr))
      }

    /** Enter staging level of symbol defined by `tree`, if applicable. */
    def markDef(tree: Tree)(implicit ctx: Context) = tree match {
      case tree: DefTree =>
        val sym = tree.symbol
        if ((sym.isClass || !sym.maybeOwner.isType) && !levelOf.contains(sym)) {
          levelOf(sym) = level
          enteredSyms = sym :: enteredSyms
        }
      case _ =>
    }

    /** Does the level of `sym` match the current level?
     *  An exception is made for inline vals in macros. These are also OK if their level
     *  is one higher than the current level, because on execution such values
     *  are constant expression trees and we can pull out the constant from the tree.
     */
    def levelOK(sym: Symbol)(implicit ctx: Context): Boolean = levelOf.get(sym) match {
      case Some(l) =>
        l == level ||
        sym.is(Inline) && sym.owner.is(Macro) && sym.info.isValueType && l - 1 == level
      case None =>
        true
    }

    /** Issue a "splice outside quote" error unless we ar in the body of an inline method */
    def spliceOutsideQuotes(pos: Position)(implicit ctx: Context): Unit =
      ctx.error(i"splice outside quotes", pos)

    /** Try to heal phase-inconsistent reference to type `T` using a local type definition.
     *  @return None      if successful
     *  @return Some(msg) if unsuccessful where `msg` is a potentially empty error message
     *                    to be added to the "inconsistent phase" message.
     */
    def tryHeal(tp: Type, pos: Position)(implicit ctx: Context): Option[String] = tp match {
      case tp: TypeRef =>
        if (level == 0) {
          assert(ctx.owner.ownersIterator.exists(_.is(Macro)))
          None
        } else {
          val reqType = defn.QuotedTypeType.appliedTo(tp)
          val tag = ctx.typer.inferImplicitArg(reqType, pos)
          tag.tpe match {
            case fail: SearchFailureType =>
              Some(i"""
                      |
                      | The access would be accepted with the right type tag, but
                      | ${ctx.typer.missingArgMsg(tag, reqType, "")}""")
            case _ =>
              importedTags(tp) = nested(isQuote = false).transform(tag)
              None
          }
        }
      case _ =>
        Some("")
    }

    /** Check reference to `sym` for phase consistency, where `tp` is the underlying type
     *  by which we refer to `sym`.
     */
    def check(sym: Symbol, tp: Type, pos: Position)(implicit ctx: Context): Unit = {
      val isThis = tp.isInstanceOf[ThisType]
      def symStr =
        if (!isThis) sym.show
        else if (sym.is(ModuleClass)) sym.sourceModule.show
        else i"${sym.name}.this"
      if (!isThis && sym.maybeOwner.isType)
        check(sym.owner, sym.owner.thisType, pos)
      else if (sym.exists && !sym.isStaticOwner && !levelOK(sym))
        for (errMsg <- tryHeal(tp, pos))
          ctx.error(em"""access to $symStr from wrong staging level:
                        | - the definition is at level ${levelOf(sym)},
                        | - but the access is at level $level.$errMsg""", pos)
    }

    /** Check all named types and this-types in a given type for phase consistency. */
    def checkType(pos: Position)(implicit ctx: Context): TypeAccumulator[Unit] = new TypeAccumulator[Unit] {
      def apply(acc: Unit, tp: Type): Unit = reporting.trace(i"check type level $tp at $level") {
        tp match {
          case tp: NamedType if tp.symbol.isSplice =>
            if (inQuote) outer.checkType(pos).foldOver(acc, tp)
            else {
              if (tp.isTerm) spliceOutsideQuotes(pos)
              tp
            }
          case tp: NamedType =>
            check(tp.symbol, tp, pos)
            foldOver(acc, tp)
          case tp: ThisType =>
            check(tp.cls, tp, pos)
            foldOver(acc, tp)
          case _ =>
            foldOver(acc, tp)
        }
      }
    }

    /** If `tree` refers to a locally defined symbol (either directly, or in a pickled type),
     *  check that its staging level matches the current level. References to types
     *  that are phase-incorrect can still be healed as follows:
     *
     *  If `T` is a reference to a type at the wrong level, heal it by setting things up
     *  so that we later add a type definition
     *
     *     type T' = ~quoted.Type[T]
     *
     *  to the quoted text and rename T to T' in it. This is done later in `reify` via
     *  `addTags`. `checkLevel` itself only records what needs to be done in the
     *  `typeTagOfRef` field of the current `Splice` structure.
     */
    private def checkLevel(tree: Tree)(implicit ctx: Context): Tree = {
      tree match {
        case (_: Ident) | (_: This) =>
          check(tree.symbol, tree.tpe, tree.pos)
        case (_: UnApply)  | (_: TypeTree) =>
          checkType(tree.pos).apply((), tree.tpe)
        case Select(qual, OuterSelectName(_, levels)) =>
          checkType(tree.pos).apply((), tree.tpe.widen)
        case _: Bind =>
          checkType(tree.pos).apply((), tree.symbol.info)
        case _: Template =>
          checkType(tree.pos).apply((), tree.symbol.owner.asClass.givenSelfType)
        case _ =>
      }
      tree
    }

    /** Split `body` into a core and a list of embedded splices.
     *  Then if inside a splice, make a hole from these parts.
     *  If outside a splice, generate a call tp `scala.quoted.Unpickler.unpickleType` or
     *  `scala.quoted.Unpickler.unpickleExpr` that matches `tpe` with
     *  core and splices as arguments.
     */
    private def quotation(body: Tree, quote: Tree)(implicit ctx: Context): Tree = {
      val isType = quote.symbol eq defn.typeQuoteMethod
      if (level > 0) {
        val body1 = nested(isQuote = true).transform(body)
        // Keep quotes as trees to reduce pickled size and have a Expr.show without pickled quotes
        if (isType) ref(defn.typeQuoteMethod).appliedToType(body1.tpe.widen)
        else ref(defn.quoteMethod).appliedToType(body1.tpe.widen).appliedTo(body1)
      }
      else {
        val (body1, splices) = nested(isQuote = true).split(body)
        val meth =
          if (isType) ref(defn.Unpickler_unpickleType).appliedToType(body1.tpe)
          else ref(defn.Unpickler_unpickleExpr).appliedToType(body1.tpe.widen)
        meth.appliedTo(
            liftList(PickledQuotes.pickleQuote(body1).map(x => Literal(Constant(x))), defn.StringType),
            liftList(splices, defn.AnyType)).withPos(quote.pos)
      }
    }

    /** If inside a quote, split the body of the splice into a core and a list of embedded quotes
     *  and make a hole from these parts. Otherwise issue an error, unless we
     *  are in the body of an inline method.
     */
    private def splice(splice: Select)(implicit ctx: Context): Tree = {
      if (level > 1) {
        val body1 = nested(isQuote = false).transform(splice.qualifier)
        body1.select(splice.name)
      }
      else if (!inQuote && level == 0) {
        spliceOutsideQuotes(splice.pos)
        splice
      }
      else {
        val (body1, quotes) = nested(isQuote = false).split(splice.qualifier)
        makeHole(body1, quotes, splice.tpe).withPos(splice.pos)
      }
    }

    /** Transforms the contents of a nested splice
     *  Assuming
     *     '{
     *        val x = ???
     *        val y = ???
     *        { ... '{ ... x .. y ... } ... }.unary_~
     *      }
     *  then the spliced subexpression
     *     { ... '{ ... x ... y ... } ... }
     *  will be transformed to
     *     (args: Seq[Any]) => {
     *       val x$1 = args(0).asInstanceOf[Expr[Any]] // or .asInstanceOf[Type[Any]]
     *       val y$1 = args(1).asInstanceOf[Expr[Any]] // or .asInstanceOf[Type[Any]]
     *       { ... '{ ... x$1.unary_~ ... y$1.unary_~ ... } ... }
     *     }
     *
     *  See: `lift`
     *
     *  At the same time register `embedded` trees `x` and `y` to place as arguments of the hole
     *  placed in the original code.
     *     '{
     *        val x = ???
     *        val y = ???
     *        Hole(0 | x, y)
     *      }
     */
    private def makeLambda(tree: Tree)(implicit ctx: Context): Tree = {
      def body(arg: Tree)(implicit ctx: Context): Tree = {
        var i = 0
        transformWithLifter(tree)(
          (lifted: mutable.ListBuffer[Tree]) => (tree: RefTree) => {
            val argTpe =
              if (tree.isTerm) defn.QuotedExprType.appliedTo(tree.tpe.widen)
              else defn.QuotedTypeType.appliedTo(defn.AnyType)
            val selectArg = arg.select(nme.apply).appliedTo(Literal(Constant(i))).asInstance(argTpe)
            val liftedArg = SyntheticValDef(UniqueName.fresh(tree.name.toTermName).toTermName, selectArg)
            i += 1
            embedded += tree
            lifted += liftedArg
            ref(liftedArg.symbol)
          }
        )
      }

      val lambdaOwner = ctx.owner.ownersIterator.find(o => levelOf.getOrElse(o, level) == level).get
      val tpe = MethodType(defn.SeqType.appliedTo(defn.AnyType) :: Nil, tree.tpe.widen)
      val meth = ctx.newSymbol(lambdaOwner, UniqueName.fresh(nme.ANON_FUN), Synthetic | Method, tpe)
      Closure(meth, tss => body(tss.head.head)(ctx.withOwner(meth)).changeOwner(ctx.owner, meth))
    }

    private def transformWithLifter(tree: Tree)(
        lifter: mutable.ListBuffer[Tree] => RefTree => Tree)(implicit ctx: Context): Tree = {
      val lifted = new mutable.ListBuffer[Tree]
      val lifter2 = lifter(lifted)
      outer.enteredSyms.foreach(s => lifters.put(s, lifter2))
      val tree2 = transform(tree)
      lifters --= outer.enteredSyms
      seq(lifted.result(), tree2)
    }

    /** Returns true if this tree will be lifted by `makeLambda` */
    private def needsLifting(tree: RefTree)(implicit ctx: Context): Boolean = {
      // Check phase consistency and presence of lifter
      level == 1 && !tree.symbol.is(Inline) && levelOf.get(tree.symbol).contains(1) &&
      lifters.contains(tree.symbol)
    }

    /** Transform `tree` and return the resulting tree and all `embedded` quotes
     *  or splices as a pair, after performing the `addTags` transform.
     */
    private def split(tree: Tree)(implicit ctx: Context): (Tree, List[Tree]) = {
      val tree1 = if (inQuote) addTags(transform(tree)) else makeLambda(tree)
      (tree1, embedded.toList)
    }

    /** Register `body` as an `embedded` quote or splice
     *  and return a hole with `splices` as arguments and the given type `tpe`.
     */
    private def makeHole(body: Tree, splices: List[Tree], tpe: Type)(implicit ctx: Context): Hole = {
      val idx = embedded.length
      embedded += body
      Hole(idx, splices).withType(tpe).asInstanceOf[Hole]
    }

    override def transform(tree: Tree)(implicit ctx: Context): Tree =
      reporting.trace(i"reify $tree at $level", show = true) {
        def mapOverTree(lastEntered: List[Symbol]) =
          try super.transform(tree)
          finally
            while (enteredSyms ne lastEntered) {
              levelOf -= enteredSyms.head
              enteredSyms = enteredSyms.tail
            }
        tree match {
          case Quoted(quotedTree) =>
            quotation(quotedTree, tree)
          case tree: Select if tree.symbol.isSplice =>
            splice(tree)
          case tree: RefTree if needsLifting(tree) =>
            val lift = lifters(tree.symbol)
            splice(lift(tree).select(if (tree.isTerm) nme.UNARY_~ else tpnme.UNARY_~))
          case Block(stats, _) =>
            val last = enteredSyms
            stats.foreach(markDef)
            mapOverTree(last)

          case Inlined(call, bindings, InlineSplice(expansion @ Select(body, name))) =>
            // To maintain phase consistency, we move the binding of the this parameter into the spliced code
            val (splicedBindings, stagedBindings) = bindings.partition {
              case vdef: ValDef => vdef.symbol.is(Synthetic) // Assume that only _this bindings are tagged with Synthetic
              case _ => false
            }

            val tree1 =
              if (level == 0) cpy.Inlined(tree)(call, stagedBindings, Splicer.splice(seq(splicedBindings, body).withPos(tree.pos)))
              else seq(stagedBindings, cpy.Select(expansion)(cpy.Inlined(tree)(call, splicedBindings, body), name))
            val tree2 = transform(tree1)

            // due to value-discarding which converts an { e } into { e; () })
            if (tree.tpe =:= defn.UnitType) Block(tree2 :: Nil, Literal(Constant(())))
            else tree2
          case _: Import =>
            tree
          case tree: DefDef if tree.symbol.is(Macro) && level == 0 =>
            markDef(tree)
            nested(isQuote = true).transform(tree)
              // check macro code as it if appeared in a quoted context
            cpy.DefDef(tree)(rhs = EmptyTree)
          case _ =>
            markDef(tree)
            checkLevel(mapOverTree(enteredSyms))
        }
      }

    private def liftList(list: List[Tree], tpe: Type)(implicit ctx: Context): Tree = {
      list.foldRight[Tree](ref(defn.NilModule)) { (x, acc) =>
        acc.select("::".toTermName).appliedToType(tpe).appliedTo(x)
      }
    }

    /** InlineSplice is used to detect cases where the expansion
     *  consists of a (possibly multiple & nested) block or a sole expression.
     */
    object InlineSplice {
      def unapply(tree: Tree)(implicit ctx: Context): Option[Select] = {
        tree match {
          case expansion: Select if expansion.symbol.isSplice => Some(expansion)
          case Block(List(stat), Literal(Constant(()))) => unapply(stat)
          case Block(Nil, expr) => unapply(expr)
          case _ => None
        }
      }
    }
  }
}