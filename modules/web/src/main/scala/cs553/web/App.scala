package cs553.web

import com.raquo.laminar.api.L.*
import org.scalajs.dom

import scala.scalajs.js

/** The browser entry point. It holds the one `Var[UiState]` and connects it to the URL, the Play
  * timer and the screens. `Ui.update` makes every state change.
  */
object App {

  def main(args: Array[String]): Unit = {
    val state = Var(Ui.update(Ui.initial, UiEvent.Navigate(Route.parse(dom.window.location.hash))))
    val dispatch: Observer[UiEvent] = state.updater[UiEvent]((s, e) => Ui.update(s, e))

    val page = div(
      cls("app"),
      state.signal.map(Ui.route).distinct --> Observer[Route](writeUrl),
      windowEvents(_.onHashChange)
        .map(_ => Route.parse(dom.window.location.hash)) --> Observer[Route] { route =>
        if Route.render(route) != Route.render(Ui.route(state.now())) then {
          dispatch.onNext(UiEvent.Navigate(route))
        }
      },
      state.signal.map(Ui.tickInterval).distinct.flatMapSwitch {
        case Some(ms) => EventStream.periodic(ms).drop(1).mapTo(UiEvent.Tick)
        case None     => EventStream.empty
      } --> dispatch,
      child <-- state.signal.map(Ui.screen).splitOne(_.isDefined) { (sandbox, _, screens) =>
        if sandbox then {
          val initial = Ui.screen(state.now())
          initial.fold(Home.render(state.signal.map(Ui.notice))) { first =>
            Sandbox.render(screens.map(_.getOrElse(first)), dispatch)
          }
        } else { Home.render(state.signal.map(Ui.notice)) }
      }
    )
    renderOnDomContentLoaded(dom.document.getElementById("app"), page): Unit
  }

  /** Shows the route in the URL. A move to another screen adds a history entry. A step in the same
    * screen replaces the entry, so Back leaves the sandbox in one click.
    */
  private def writeUrl(route: Route): Unit = {
    val wanted = Route.render(route)
    val current = dom.window.location.hash
    if current != wanted then {
      if Route.changesScreen(Route.parse(current), route) then {
        dom.window.history.pushState(new js.Object(), "", wanted)
      } else { dom.window.history.replaceState(new js.Object(), "", wanted) }
    }
  }
}
