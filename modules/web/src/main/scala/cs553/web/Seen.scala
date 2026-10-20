package cs553.web

import cs553.core.ids.AlgorithmId
import org.scalajs.dom

import scala.util.Try

/** Remembers in the browser which lessons the learner has seen. Storage can be missing or can
  * throw, for example in a private window, so each call falls back to "not seen" and to no write.
  */
object Seen {

  private def key(id: AlgorithmId): String = s"da.lesson.seen.${id.value}"

  def has(id: AlgorithmId): Boolean =
    Try(dom.window.localStorage.getItem(key(id))).toOption.flatMap(Option(_)).isDefined

  def mark(id: AlgorithmId): Unit = {
    val _ = Try(dom.window.localStorage.setItem(key(id), "1"))
  }
}
