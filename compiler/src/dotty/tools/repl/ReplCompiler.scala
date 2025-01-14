package dotty.tools.repl

import dotty.tools.backend.jvm.GenBCode
import dotty.tools.dotc.ast.Trees._
import dotty.tools.dotc.ast.{tpd, untpd}
import dotty.tools.dotc.core.Contexts._
import dotty.tools.dotc.core.Decorators._
import dotty.tools.dotc.core.Flags._
import dotty.tools.dotc.core.Names._
import dotty.tools.dotc.core.Phases
import dotty.tools.dotc.core.Phases.Phase
import dotty.tools.dotc.core.StdNames._
import dotty.tools.dotc.reporting.diagnostic.messages
import dotty.tools.dotc.typer.{FrontEnd, ImportInfo}
import dotty.tools.dotc.util.Positions._
import dotty.tools.dotc.util.SourceFile
import dotty.tools.dotc.{CompilationUnit, Compiler, Run}
import dotty.tools.io._
import dotty.tools.repl.results._

import scala.collection.mutable

/** This subclass of `Compiler` replaces the appropriate phases in order to
 *  facilitate the REPL
 *
 *  Specifically it replaces the front end with `REPLFrontEnd`, and adds a
 *  custom subclass of `GenBCode`. The custom `GenBCode`, `REPLGenBCode`, works
 *  in conjunction with a specialized class loader in order to load virtual
 *  classfiles.
 */
class ReplCompiler extends Compiler {
  override protected def frontendPhases: List[List[Phase]] =
    Phases.replace(classOf[FrontEnd], _ => new REPLFrontEnd :: Nil, super.frontendPhases)

  def newRun(initCtx: Context, objectIndex: Int) = new Run(this, initCtx) {
    override protected[this] def rootContext(implicit ctx: Context) =
      addMagicImports(super.rootContext)

    private def addMagicImports(initCtx: Context): Context = {
      def addImport(path: TermName)(implicit ctx: Context) = {
        val importInfo = ImportInfo.rootImport { () =>
          ctx.requiredModuleRef(path)
        }
        ctx.fresh.setNewScope.setImportInfo(importInfo)
      }

      (1 to objectIndex)
        .foldLeft(initCtx) { (ictx, i) =>
          addImport(nme.EMPTY_PACKAGE ++ "." ++ objectNames(i))(ictx)
        }
    }
  }

  private[this] val objectNames = mutable.Map.empty[Int, TermName]
  private def objectName(state: State) =
    objectNames.getOrElseUpdate(state.objectIndex, {
      (str.REPL_SESSION_LINE + state.objectIndex).toTermName
    })

  private case class Definitions(stats: List[untpd.Tree], state: State)

  private def definitions(trees: List[untpd.Tree], state: State): Definitions = {
    import untpd._

    implicit val ctx: Context = state.context

    var valIdx = state.valIndex

    val defs = trees.flatMap {
      case expr @ Assign(id: Ident, _) =>
        // special case simple reassignment (e.g. x = 3)
        // in order to print the new value in the REPL
        val assignName = (id.name ++ str.REPL_ASSIGN_SUFFIX).toTermName
        val assign = ValDef(assignName, TypeTree(), id).withPos(expr.pos)
        List(expr, assign)
      case expr if expr.isTerm =>
        val resName = (str.REPL_RES_PREFIX + valIdx).toTermName
        valIdx += 1
        val vd = ValDef(resName, TypeTree(), expr).withPos(expr.pos)
        vd :: Nil
      case other =>
        other :: Nil
    }

    Definitions(
      state.imports ++ defs,
      state.copy(
        objectIndex = state.objectIndex + (if (defs.isEmpty) 0 else 1),
        valIndex = valIdx
      )
    )
  }

  /** Wrap trees in an object and add imports from the previous compilations
   *
   *  The resulting structure is something like:
   *
   *  ```
   *  package <none> {
   *    object rs$line$nextId {
   *      import rs$line${i <- 0 until nextId}._
   *
   *      <trees>
   *    }
   *  }
   *  ```
   */
  private def wrapped(defs: Definitions): untpd.PackageDef = {
    import untpd._

    assert(defs.stats.nonEmpty)

    implicit val ctx: Context = defs.state.context

    val tmpl = Template(emptyConstructor, Nil, EmptyValDef, defs.stats)
    val module = ModuleDef(objectName(defs.state), tmpl)
      .withPos(Position(0, defs.stats.last.pos.end))

    PackageDef(Ident(nme.EMPTY_PACKAGE), List(module))
  }

  private def createUnit(defs: Definitions, sourceCode: String): CompilationUnit = {
    val unit = new CompilationUnit(new SourceFile(objectName(defs.state).toString, sourceCode))
    unit.untpdTree = wrapped(defs)
    unit
  }

  private def runCompilationUnit(unit: CompilationUnit, state: State): Result[(CompilationUnit, State)] = {
    val ctx = state.context
    ctx.run.compileUnits(unit :: Nil)

    if (!ctx.reporter.hasErrors) (unit, state).result
    else ctx.reporter.removeBufferedMessages(ctx).errors
  }

  final def compile(parsed: Parsed)(implicit state: State): Result[(CompilationUnit, State)] = {
    val defs = definitions(parsed.trees, state)
    val unit = createUnit(defs, parsed.sourceCode)
    runCompilationUnit(unit, defs.state)
  }

  final def typeOf(expr: String)(implicit state: State): Result[String] =
    typeCheck(expr).map { tree =>
      implicit val ctx = state.context
      tree.rhs match {
        case Block(xs, _) => xs.last.tpe.widen.show
        case _ =>
          """Couldn't compute the type of your expression, so sorry :(
            |
            |Please report this to my masters at github.com/lampepfl/dotty
          """.stripMargin
      }
    }

  final def typeCheck(expr: String, errorsAllowed: Boolean = false)(implicit state: State): Result[tpd.ValDef] = {

    def wrapped(expr: String, sourceFile: SourceFile, state: State)(implicit ctx: Context): Result[untpd.PackageDef] = {
      def wrap(trees: List[untpd.Tree]): untpd.PackageDef = {
        import untpd._

        val valdef = ValDef("expr".toTermName, TypeTree(), Block(trees, unitLiteral))
        val tmpl = Template(emptyConstructor, Nil, EmptyValDef, state.imports :+ valdef)
        val wrapper = TypeDef("$wrapper".toTypeName, tmpl)
          .withMods(Modifiers(Final))
          .withPos(Position(0, expr.length))
        PackageDef(Ident(nme.EMPTY_PACKAGE), List(wrapper))
      }

      ParseResult(expr) match {
        case Parsed(_, trees) =>
          wrap(trees).result
        case SyntaxErrors(_, reported, trees) =>
          if (errorsAllowed) wrap(trees).result
          else reported.errors
        case _ => List(
          new messages.Error(
            s"Couldn't parse '$expr' to valid scala",
            sourceFile.atPos(Position(0, expr.length))
          )
        ).errors
      }
    }

    def unwrapped(tree: tpd.Tree, sourceFile: SourceFile)(implicit ctx: Context): Result[tpd.ValDef] = {
      def error: Result[tpd.ValDef] =
        List(new messages.Error(s"Invalid scala expression",
          sourceFile.atPos(Position(0, sourceFile.content.length)))).errors

      import tpd._
      tree match {
        case PackageDef(_, List(TypeDef(_, tmpl: Template))) =>
          tmpl.body
              .collectFirst { case dd: ValDef if dd.name.show == "expr" => dd.result }
              .getOrElse(error)
        case _ =>
          error
      }
    }


    val src = new SourceFile("<typecheck>", expr)
    implicit val ctx: Context = state.context.fresh
      .setReporter(newStoreReporter)
      .setSetting(state.context.settings.YstopAfter, List("frontend"))

    wrapped(expr, src, state).flatMap { pkg =>
      val unit = new CompilationUnit(src)
      unit.untpdTree = pkg
      ctx.run.compileUnits(unit :: Nil, ctx)

      if (errorsAllowed || !ctx.reporter.hasErrors)
        unwrapped(unit.tpdTree, src)
      else {
        ctx.reporter.removeBufferedMessages.errors
      }
    }
  }
}
