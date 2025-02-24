package dotty.tools.dotc.quoted

import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.Driver
import dotty.tools.dotc.core.Contexts.{Context, ContextBase}
import dotty.tools.io.{AbstractFile, Directory, PlainDirectory, VirtualDirectory}
import dotty.tools.repl.AbstractFileClassLoader

import scala.quoted.{Expr, Type}
import java.net.URLClassLoader

import dotty.tools.dotc.tastyreflect.TastyImpl

class QuoteDriver extends Driver {
  import tpd._

  private[this] val contextBase: ContextBase = new ContextBase

  def run[T](expr: Expr[T], settings: ToolboxSettings): T = {
    val outDir: AbstractFile = settings.outDir match {
      case Some(out) =>
        val dir = Directory(out)
        dir.createDirectory()
        new PlainDirectory(Directory(out))
      case None =>
        new VirtualDirectory("<quote compilation output>")
    }

    val (_, ctx0: Context) = setup(settings.compilerArgs.toArray :+ "dummy.scala", initCtx.fresh)
    val ctx = ctx0.fresh.setSetting(ctx0.settings.outputDir, outDir)

    val driver = new QuoteCompiler
    driver.newRun(ctx).compileExpr(expr)

    val classLoader = new AbstractFileClassLoader(outDir, this.getClass.getClassLoader)

    val clazz = classLoader.loadClass(driver.outputClassName.toString)
    val method = clazz.getMethod("apply")
    val instance = clazz.newInstance()

    method.invoke(instance).asInstanceOf[T]
  }

  def show(expr: Expr[_], settings: ToolboxSettings): String = {
    def show(tree: Tree, ctx: Context): String = {
      val tree1 = if (settings.rawTree) tree else (new TreeCleaner).transform(tree)(ctx)
      new TastyImpl(ctx).showSourceCode.showTree(tree1)(ctx)
    }
    withTree(expr, show, settings)
  }

  def withTree[T](expr: Expr[_], f: (Tree, Context) => T, settings: ToolboxSettings): T = {
    val (_, ctx: Context) = setup(settings.compilerArgs.toArray :+ "dummy.scala", initCtx.fresh)

    var output: Option[T] = None
    def registerTree(tree: tpd.Tree)(ctx: Context): Unit = {
      assert(output.isEmpty)
      output = Some(f(tree, ctx))
    }
    new QuoteDecompiler(registerTree).newRun(ctx).compileExpr(expr)
    output.getOrElse(throw new Exception("Could not extract " + expr))
  }

  def withTypeTree[T](tpe: Type[_], f: (TypTree, Context) => T, settings: ToolboxSettings): T = {
    val (_, ctx: Context) = setup(settings.compilerArgs.toArray :+ "dummy.scala", initCtx.fresh)

    var output: Option[T] = None
    def registerTree(tree: tpd.Tree)(ctx: Context): Unit = {
      assert(output.isEmpty)
      output = Some(f(tree.asInstanceOf[TypTree], ctx))
    }
    new QuoteDecompiler(registerTree).newRun(ctx).compileType(tpe)
    output.getOrElse(throw new Exception("Could not extract " + tpe))
  }

  override def initCtx: Context = {
    val ictx = contextBase.initialCtx
    var classpath = System.getProperty("java.class.path")
    this.getClass.getClassLoader match {
      case cl: URLClassLoader =>
        // Loads the classes loaded by this class loader
        // When executing `run` or `test` in sbt the classpath is not in the property java.class.path
        val newClasspath = cl.getURLs.map(_.getFile())
        classpath = newClasspath.mkString("", ":", if (classpath == "") "" else ":" + classpath)
      case _ =>
    }
    ictx.settings.classpath.update(classpath)(ictx)
    ictx
  }

}
