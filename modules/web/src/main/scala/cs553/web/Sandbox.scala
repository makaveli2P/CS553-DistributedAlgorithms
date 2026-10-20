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
          child.maybe <-- screen
            .map(_.lesson)
            .distinct
            .map(_.map { route =>
              a(cls("quiet", "link"), href(Route.render(route)), "Lesson")
            }),
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
            viewSwitch(screen.map(_.mode).distinct, dispatch),
            Canvas
              .render(frame, dispatch)
              .amend(cls("canvas--hidden") <-- screen.map(_.mode != ViewMode.Graph).distinct),
            SpaceTimeView.render(screen.map(_.spaceTime), screen.map(_.mode).distinct, dispatch),
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
              child.maybe <-- frame
                .map(_.invariant)
                .distinct
                .map(_.map { line =>
                  p(cls("invariant"), role("alert"), line)
                }),
              child.maybe <-- frame.map(facts),
              children <-- frame.map(_.decisions.map(line => p(cls("fact"), line))),
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

  /** The quiet switch between the two views of the run. */
  private def viewSwitch(mode: Signal[ViewMode], dispatch: Observer[UiEvent]): HtmlElement = {
    def choice(wanted: ViewMode, name: String) = button(
      cls("quiet", "seg"),
      cls("seg--on") <-- mode.map(_ == wanted),
      aria.pressed <-- mode.map(m => (m == wanted).toString),
      name,
      onClick.mapTo(UiEvent.SetMode(wanted)) --> dispatch
    )
    div(
      cls("view-switch"),
      choice(ViewMode.Graph, "Graph"),
      choice(ViewMode.SpaceTime, "Space-time")
    )
  }

  /** One short line with a key for each tick on the slider and each fact of the overlay, or
    * nothing.
    */
  private def facts(frame: Frame): Option[HtmlElement] = {
    val keys = List(
      frame.decidedAt.map { n =>
        span(cls("key"), span(cls("dot", "tick--decided")), s"decided at step $n")
      },
      frame.quiescentAt.map { n =>
        span(
          cls("key"),
          title("Only an observer of the whole network knows this."),
          span(cls("dot", "tick--quiescent")),
          s"quiescent at step $n"
        )
      }
    ).flatten ++ frame.facts.map(text => span(cls("key"), text))
    Option.when(keys.nonEmpty)(p(cls("facts"), keys))
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
