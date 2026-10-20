package cs553.web

import cs553.core.codec.RunLink
import cs553.core.ids.NodeId
import cs553.core.protocol.ChannelOrder
import cs553.core.registry.Registry
import cs553.core.trace.EnvelopeId

/** The speed of Play. */
enum Speed(val label: String, val intervalMs: Int) {
  case Slow extends Speed("1×", 900)
  case Normal extends Speed("2×", 450)
  case Fast extends Speed("4×", 200)

  def nextSpeed: Speed = this match {
    case Slow   => Normal
    case Normal => Fast
    case Fast   => Slow
  }
}

/** The one side panel that is open. Panels are closed by default. */
enum Panel {
  case Closed
  case Node(id: NodeId)
  case Envelope(id: EnvelopeId)
  case Settings
}

/** The screen state that is not part of the run. */
final case class View(panel: Panel, playing: Boolean, speed: Speed, copied: Boolean)

object View {
  val initial: View = View(Panel.Closed, playing = false, Speed.Slow, copied = false)
}

/** The screen. */
enum Page {
  case Home(notice: Option[String])
  case Sandbox(session: Session[?, ?, ?, ?], view: View)
}

/** The whole UI state. The app holds one `Var[UiState]` and changes it only with `Ui.update`. */
final case class UiState(page: Page)

/** Everything that the learner or a timer can do. */
enum UiEvent {
  case Navigate(route: Route)
  case Forward
  case ContinueHere
  case Restart
  case Deliver(id: EnvelopeId)
  case Fire(node: NodeId, index: Int)
  case Seek(step: Int)
  case StepBack
  case TogglePlay
  case Tick
  case CycleSpeed
  case SelectNode(id: NodeId)
  case SelectEnvelope(id: EnvelopeId)
  case ClosePanel
  case ToggleSettings
  case ChoosePreset(name: String)
  case ChooseSeed(seed: Long)
  case ChooseOrder(order: ChannelOrder)
  case Copied
}

/** The pure state machine of the UI. */
object Ui {

  val initial: UiState = UiState(Page.Home(None))

  def update(state: UiState, event: UiEvent): UiState = {
    event match {
      case UiEvent.Navigate(route) => navigate(route)
      case other                   =>
        state.page match {
          case Page.Home(_)                => state
          case Page.Sandbox(session, view) =>
            UiState(sandbox(session, view.copy(copied = false), other))
        }
    }
  }

  private def navigate(route: Route): UiState = route match {
    case Route.Home     => initial
    case Route.Open(id) =>
      Registry.find(id) match {
        case None      => UiState(Page.Home(Some(s"No algorithm has the id '${id.value}'.")))
        case Some(any) => fromResult(Session.open(any.algorithm))
      }
    case Route.Replay(link, step) =>
      val found =
        RunLink.algorithmOf(link).left.map(_.toList.map(_.render).mkString("; ")).flatMap { id =>
          Registry.find(id).toRight(s"No algorithm has the id '${id.value}'.")
        }
      found match {
        case Left(error) => UiState(Page.Home(Some(s"This link could not be read: $error")))
        case Right(any)  => fromResult(Session.fromLink(any.algorithm, link, step))
      }
  }

  private def fromResult(result: Either[String, Session[?, ?, ?, ?]]): UiState = result match {
    case Left(error)    => UiState(Page.Home(Some(s"This run could not start: $error")))
    case Right(session) => UiState(Page.Sandbox(session, View.initial))
  }

  private def sandbox(session: Session[?, ?, ?, ?], view: View, event: UiEvent): Page = {
    def run(next: Session[?, ?, ?, ?]): Page = Page.Sandbox(next, view)
    def runAndShow(next: Session[?, ?, ?, ?], panel: Panel): Page =
      Page.Sandbox(next, view.copy(panel = panel))
    event match {
      case UiEvent.Navigate(_)   => Page.Sandbox(session, view)
      case UiEvent.Forward       => run(session.forward)
      case UiEvent.ContinueHere  => run(session.continueHere)
      case UiEvent.Restart       => Page.Sandbox(session.restart, view.copy(playing = false))
      case UiEvent.Deliver(id)   => run(session.deliver(id))
      case UiEvent.Fire(node, i) => run(session.fire(node, i))
      case UiEvent.Seek(step)    => Page.Sandbox(session.seek(step), view.copy(playing = false))
      case UiEvent.StepBack      =>
        Page.Sandbox(session.seek(session.cursor - 1), view.copy(playing = false))
      case UiEvent.TogglePlay =>
        val canPlay = !(session.live && session.finished)
        Page.Sandbox(session, view.copy(playing = !view.playing && canPlay))
      case UiEvent.Tick =>
        if !view.playing then { Page.Sandbox(session, view) }
        else {
          val next = session.forward
          val stuck = next.cursor == session.cursor && next.last == session.last
          val stop = (next.live && next.finished) || stuck
          Page.Sandbox(next, view.copy(playing = !stop))
        }
      case UiEvent.CycleSpeed     => Page.Sandbox(session, view.copy(speed = view.speed.nextSpeed))
      case UiEvent.SelectNode(id) => runAndShow(session, toggle(view.panel, Panel.Node(id)))
      case UiEvent.SelectEnvelope(id) => runAndShow(session, toggle(view.panel, Panel.Envelope(id)))
      case UiEvent.ClosePanel         => runAndShow(session, Panel.Closed)
      case UiEvent.ToggleSettings     => runAndShow(session, toggle(view.panel, Panel.Settings))
      case UiEvent.ChoosePreset(name) =>
        Page.Sandbox(session.withPreset(name), view.copy(playing = false))
      case UiEvent.ChooseSeed(seed) =>
        Page.Sandbox(session.withSeed(seed), view.copy(playing = false))
      case UiEvent.ChooseOrder(order) =>
        Page.Sandbox(session.withOrder(order), view.copy(playing = false))
      case UiEvent.Copied => Page.Sandbox(session, view.copy(copied = true))
    }
  }

  private def toggle(open: Panel, wanted: Panel): Panel = if open == wanted then { Panel.Closed }
  else { wanted }

  /** The route that the URL fragment shows for this state. */
  def route(state: UiState): Route = state.page match {
    case Page.Home(_)             => Route.Home
    case Page.Sandbox(session, _) => Route.Replay(session.link, Some(session.cursor))
  }

  /** The interval of the Play timer, or `None` when Play is off. */
  def tickInterval(state: UiState): Option[Int] = state.page match {
    case Page.Home(_)          => None
    case Page.Sandbox(_, view) =>
      if view.playing then { Some(view.speed.intervalMs) }
      else { None }
  }

  def notice(state: UiState): Option[String] = state.page match {
    case Page.Home(notice)  => notice
    case Page.Sandbox(_, _) => None
  }

  /** The sandbox screen, or `None` on the home screen. */
  def screen(state: UiState): Option[Screen] = state.page match {
    case Page.Home(_)                => None
    case Page.Sandbox(session, view) =>
      session.frame.map { frame =>
        val panel = view.panel match {
          case Panel.Closed       => None
          case Panel.Node(id)     => session.nodePanel(id)
          case Panel.Envelope(id) => session.envelopePanel(id)
          case Panel.Settings     => Some(PanelView.SettingsInfo(session.settings))
        }
        Screen(
          session.alg.name,
          frame,
          session.primary,
          panel,
          session.branchLine,
          session.refused,
          view.playing,
          view.speed,
          view.copied
        )
      }
  }
}
