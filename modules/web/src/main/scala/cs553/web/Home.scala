package cs553.web

import com.raquo.laminar.api.L.*
import cs553.core.Catalogue
import cs553.core.registry.Registry

/** The home screen. Its one question: which algorithm to open. */
object Home {

  def render(notice: Signal[Option[String]]): HtmlElement = {
    val ready = Registry.all.map(_.algorithm)
    val later = Catalogue.algorithms.map(_._1).filterNot(name => ready.exists(_.name == name))
    div(
      cls("page", "home"),
      headerTag(cls("bar"), span(cls("brand"), "CS553 Distributed Algorithms")),
      mainTag(
        h1("Which algorithm do you want to run?"),
        child.maybe <-- notice.map(_.map(text => p(cls("notice"), text))),
        ul(
          cls("algorithms"),
          ready.map { alg =>
            li(
              a(
                href(Route.render(Route.Open(alg.id))),
                div(
                  cls("algorithm-head"),
                  span(cls("algorithm-name"), alg.name),
                  span(cls("family"), alg.family.toString)
                ),
                p(cls("algorithm-summary"), alg.summary)
              )
            )
          }
        ),
        p(cls("later"), s"Later: ${later.mkString(", ")}.")
      )
    )
  }
}
