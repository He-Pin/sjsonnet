package sjsonnet

import fastparse.IndexedParserInput

import scala.collection.mutable

case class DummyPath(segments: String*) extends Path{
  def relativeToString(p: Path): String = ""

  def debugRead(): Option[String] = None

  def parent(): Path = DummyPath(segments.dropRight(1):_*)

  def segmentCount(): Int = segments.length

  def last: String = segments.last

  def /(s: String): Path = DummyPath(segments :+ s:_*)

  def renderOffsetStr(offset: Int, loadedFileContents: mutable.Map[Path, IndexedParserInput]): String = {
    segments.mkString("/") + ":" + offset
  }
}
