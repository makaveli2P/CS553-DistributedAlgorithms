package cs553.web

import com.raquo.laminar.api.L.*
import cs553.core.Catalogue
import org.scalajs.dom

/** The browser entry point. M2 adds the sandbox. */
object App {

  def main(args: Array[String]): Unit = {
    val page = mainTag(
      h1("CS553 Distributed Algorithms"),
      p(Catalogue.summary),
      ul(Catalogue.algorithms.map { case (name, family) => li(s"$name ($family)") })
    )
    renderOnDomContentLoaded(dom.document.getElementById("app"), page): Unit
  }
}
