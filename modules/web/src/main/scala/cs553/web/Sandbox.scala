package cs553.web

import com.raquo.laminar.api.L.*
import org.scalajs.dom

/** The sandbox screen. Its one primary action is "Deliver next". */
object Sandbox {

  def render(screen: Signal[Screen], dispatch: Observer[UiEvent]): HtmlElement = {
    val frame = screen.map(_.frame)
    div(
      cls("page", "sandbox"),
      headerTag(
        cls("bar"),
        a(cls("back"), href(Route.render(Route.Home)), "All algorithms"),
        div(
          cls("bar-actions"),
          button(
            cls("quiet"),
            text <-- screen.map(s =>
              if s.copied then { "Link copied" }
              else { "Copy link" }
            ),
            onClick --> { _ =>
              dom.window.navigator.clipboard.writeText(dom.window.location.href): Unit
              dispatch.onNext(UiEvent.Copied)
            }
          ),
          button(cls("quiet"), "Settings", onClick.mapTo(UiEvent.ToggleSettings) --> dispatch)
        )
      ),
      mainTag(
        h1(text <-- screen.map(_.title)),
        div(
          cls("stage"),
          Canvas.render(frame, dispatch),
          Panels.render(screen.map(_.panel).distinct, dispatch)
        ),
        div(
          cls("lines"),
          p(cls("status"), text <-- frame.map(_.status)),
          child.maybe <-- frame.map(
            _.decidedAt.map(n => p(cls("fact"), s"Algorithm decided at step $n"))
          ),
          child.maybe <-- frame.map(_.quiescentAt.map { n =>
            p(
              cls("fact"),
              s"Network quiescent at step $n ",
              span(cls("aside"), "(only an observer knows this)")
            )
          }),
          child.maybe <-- frame.map(
            _.limitAt.map(n => p(cls("fact"), s"Run stopped at the step limit, step $n"))
          ),
          child.maybe <-- screen.map(_.branchLine.map(text => p(cls("aside"), text))),
          child.maybe <-- screen.map(_.refused.map(text => p(cls("refused"), text)))
        ),
        div(
          cls("controls"),
          child.maybe <-- screen
            .map(_.primary)
            .distinct
            .map(_.map { primary =>
              button(cls("primary"), primary.label, onClick.mapTo(primary.event) --> dispatch)
            }),
          Timeline.render(screen, dispatch)
        )
      )
    )
  }
}
