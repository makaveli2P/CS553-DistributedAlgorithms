package cs553.web

import com.raquo.laminar.api.L.*
import org.scalajs.dom

import scala.scalajs.js
import scala.util.Failure
import scala.util.Success

/** The sandbox screen. It has one primary action at a time: the seeded next step, "Continue from
  * step k" on a past step, or "Run again" at the end. The settings are always visible: in a column
  * next to the canvas on a wide screen, and under the timeline on a narrow screen.
  */
object Sandbox {

  def render(screen: Signal[Screen], dispatch: Observer[UiEvent]): HtmlElement = {
    val frame = screen.map(_.frame)
    val panel = screen.map(_.panel).distinct
    div(
      cls("page", "sandbox"),
      headerTag(
        cls("bar"),
        a(cls("back"), href(Route.render(Route.Home)), "All algorithms"),
        div(
          cls("bar-actions"),
          button(
            cls("quiet"),
            text <-- screen.map(_.copy).distinct.map {
              case CopyState.Copied                  => "Link copied"
              case CopyState.Idle | CopyState.Failed => "Copy link"
            },
            onClick --> { _ => copyLink(dispatch) }
          )
        )
      ),
      mainTag(
        h1(text <-- screen.map(_.title)),
        child.maybe <-- screen.map(_.copy == CopyState.Failed).distinct.map { failed =>
          Option
            .when(failed)(p(cls("aside", "copy-hint"), "Copy the address bar to share this run."))
        },
        div(
          cls("workspace"),
          div(
            cls("stage"),
            Canvas.render(frame, dispatch),
            div(
              cls("controls"),
              child.maybe <-- screen
                .map(_.primary)
                .distinct
                .map(_.map { primary =>
                  button(
                    cls("primary"),
                    primary.label,
                    primary.hint.map(title(_)),
                    onClick.mapTo(primary.event) --> dispatch
                  )
                }),
              Timeline.render(screen, dispatch)
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
            )
          ),
          asideTag(
            cls("side"),
            Panels.settings(screen.map(_.settings), dispatch),
            Panels.render(panel, dispatch)
          )
        )
      )
    )
  }

  /** Copies the URL. The clipboard exists only in a secure context, and the write can fail, so the
    * screen says "Link copied" only after the write succeeds.
    */
  private def copyLink(dispatch: Observer[UiEvent]): Unit = {
    val navigator = dom.window.navigator
    val clipboard = navigator.asInstanceOf[js.Dynamic].clipboard
    if js.isUndefined(clipboard) then { dispatch.onNext(UiEvent.CopyFailed) }
    else {
      navigator.clipboard
        .writeText(dom.window.location.href)
        .toFuture
        .onComplete {
          case Success(_) => dispatch.onNext(UiEvent.Copied)
          case Failure(_) => dispatch.onNext(UiEvent.CopyFailed)
        }(using scala.scalajs.concurrent.JSExecutionContext.queue)
    }
  }
}
