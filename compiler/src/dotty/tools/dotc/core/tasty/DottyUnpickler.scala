package dotty.tools
package dotc
package core
package tasty

import Contexts._, SymDenotations._, Symbols._,  Decorators._
import dotty.tools.dotc.ast.tpd
import TastyUnpickler._, TastyBuffer._
import util.Positions._
import util.{SourceFile, NoSource}
import Annotations.Annotation
import classfile.ClassfileParser
import Names.SimpleName

object DottyUnpickler {

  /** Exception thrown if classfile is corrupted */
  class BadSignature(msg: String) extends RuntimeException(msg)

  class TreeSectionUnpickler(posUnpickler: Option[PositionUnpickler], commentUnpickler: Option[CommentUnpickler])
  extends SectionUnpickler[TreeUnpickler]("ASTs") {
    def unpickle(reader: TastyReader, nameAtRef: NameTable) =
      new TreeUnpickler(reader, nameAtRef, posUnpickler, commentUnpickler, Seq.empty)
  }

  class PositionsSectionUnpickler extends SectionUnpickler[PositionUnpickler]("Positions") {
    def unpickle(reader: TastyReader, nameAtRef: NameTable) =
      new PositionUnpickler(reader)
  }

  class CommentsSectionUnpickler extends SectionUnpickler[CommentUnpickler]("Comments") {
    def unpickle(reader: TastyReader, nameAtRef: NameTable): CommentUnpickler =
      new CommentUnpickler(reader)
  }
}

/** A class for unpickling Tasty trees and symbols.
 *  @param bytes         the bytearray containing the Tasty file from which we unpickle
 */
class DottyUnpickler(bytes: Array[Byte]) extends ClassfileParser.Embedded with tpd.TreeProvider {
  import tpd._
  import DottyUnpickler._

  val unpickler = new TastyUnpickler(bytes)
  private val posUnpicklerOpt = unpickler.unpickle(new PositionsSectionUnpickler)
  private val commentUnpicklerOpt = unpickler.unpickle(new CommentsSectionUnpickler)
  private val treeUnpickler = unpickler.unpickle(treeSectionUnpickler(posUnpicklerOpt, commentUnpicklerOpt)).get

  /** Enter all toplevel classes and objects into their scopes
   *  @param roots          a set of SymDenotations that should be overwritten by unpickling
   */
  def enter(roots: Set[SymDenotation])(implicit ctx: Context): Unit =
    treeUnpickler.enterTopLevel(roots)

  def unpickleExpr()(implicit ctx: Context): Tree =
    treeUnpickler.unpickleExpr()

  def unpickleTypeTree()(implicit ctx: Context): Tree =
    treeUnpickler.unpickleTypeTree()

  protected def treeSectionUnpickler(posUnpicklerOpt: Option[PositionUnpickler], commentUnpicklerOpt: Option[CommentUnpickler]): TreeSectionUnpickler = {
    new TreeSectionUnpickler(posUnpicklerOpt, commentUnpicklerOpt)
  }

  protected def computeTrees(implicit ctx: Context) = treeUnpickler.unpickle()

  private[this] var ids: Array[String] = null

  override def mightContain(id: String)(implicit ctx: Context): Boolean = {
    if (ids == null)
      ids =
        unpickler.nameAtRef.contents.toArray.collect {
          case name: SimpleName => name.toString
        }.sorted
    ids.binarySearch(id) >= 0
  }
}