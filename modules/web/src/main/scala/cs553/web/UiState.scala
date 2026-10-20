package cs553.web

import cs553.core.codec.RunLink
import cs553.core.ids.NodeId
import cs553.core.lessons.Lesson
import cs553.core.lessons.Lessons
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

/** The inspector that is open: a node or an envelope. It is closed by default. The settings are
  * always visible, so they are not a panel.
  */
enum Panel {
  case Closed
  case Node(id: NodeId)
  case Envelope(id: EnvelopeId)
}

/** The screen state that is not part of the run. `size` is the measured size of the canvas. */
final case class View(
    panel: Panel,
    playing: Boolean,
    speed: Speed,
    copy: CopyState,
    size: Size,
    mode: ViewMode
)

object View {
  val initial: View =
    View(
      Panel.Closed,
      playing = false,
      Speed.Normal,
      CopyState.Idle,
      Layout.defaultSize,
      ViewMode.Graph
    )
}

/** The calm reply to the answer of a lesson. */
enum Reply {
  case Right(text: String)
  case Wrong(text: String)
}

/** A notice on the home screen: one plain line, and the detail for a developer. */
final case class Notice(text: String, detail: String)

/** The screen. */
enum Page {
  case Home(notice: Option[Notice])
  case Sandbox(session: Session[?, ?, ?, ?], view: View)

  /** A guided lesson. `session` holds the whole run of the lesson. `index` is the screen: a beat,
    * or the question at the end. `reply` is the reply to the last answer.
    */
  case Lesson(
      session: Session[?, ?, ?, ?],
      lesson: cs553.core.lessons.Lesson,
      index: Int,
      reply: Option[Reply],
      size: Size
  )
}

/** The kind of screen. The app keeps one element for each kind. */
enum PageKind {
  case Home, Sandbox, Lesson
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
  case ChoosePreset(name: String)
  case ChooseSeed(seed: Long)
  case ChooseOrder(order: ChannelOrder)
  case ChooseSource(source: RunSource)
  case Copied
  case CopyFailed
  case Resized(size: Size)
  case SetMode(mode: ViewMode)
  case LessonNext
  case LessonBack
}

/** The pure state machine of the UI. */
object Ui {

  val initial: UiState = UiState(Page.Home(None))

  def update(state: UiState, event: UiEvent): UiState = {
    event match {
      case UiEvent.Navigate(route) => navigate(route, measured(state))
      case other                   =>
        state.page match {
          case Page.Home(_)                               => state
          case Page.Lesson(session, lesson, i, reply, sz) =>
            UiState(lessonStep(session, lesson, i, reply, sz, other))
          case Page.Sandbox(session, view) =>
            val keepCopy = other match {
              case UiEvent.Resized(_) => view.copy
              case _                  => CopyState.Idle
            }
            UiState(sandbox(session, view.copy(copy = keepCopy), other))
        }
    }
  }

  /** The measured size of the canvas. A hash change from one sandbox to another keeps the same svg
    * element, so the ResizeObserver sends no new `Resized`, and the new view must keep the size.
    */
  private def measured(state: UiState): Size = state.page match {
    case Page.Sandbox(_, view)       => view.size
    case Page.Lesson(_, _, _, _, sz) => sz
    case Page.Home(_)                => Layout.defaultSize
  }

  private def navigate(route: Route, size: Size): UiState = route match {
    case Route.Home       => initial
    case Route.Lesson(id) =>
      val opened = for {
        any <- Registry.find(id).toRight(s"No algorithm has the id '${id.value}'.")
        lesson <- Lessons.find(id).toRight(s"${any.name} has no lesson.")
        session <- lessonSession(any.algorithm, lesson)
      } yield UiState(Page.Lesson(session, lesson, 0, None, size))
      opened.fold(
        error => UiState(Page.Home(Some(Notice(error, error)))),
        identity
      )
    case Route.Open(id) =>
      Registry.find(id) match {
        case None =>
          val text = s"No algorithm has the id '${id.value}'. Pick an algorithm to start a run."
          UiState(Page.Home(Some(Notice(text, text))))
        case Some(any) => fromResult(Session.open(any.algorithm), size)
      }
    case Route.Replay(link, step) =>
      val found =
        RunLink.algorithmOf(link).left.map(_.toList.map(_.render).mkString("; ")).flatMap { id =>
          Registry.find(id).toRight(s"No algorithm has the id '${id.value}'.")
        }
      found match {
        case Left(error) => UiState(Page.Home(Some(Notice(damagedLink, error))))
        case Right(any)  => fromResult(Session.fromLink(any.algorithm, link, step), size)
      }
  }

  /** The one line for each link or run that cannot open. The detail goes to the developer. */
  val damagedLink: String =
    "This link is damaged or from another version. Pick an algorithm to start a new run."

  private def fromResult(result: Either[String, Session[?, ?, ?, ?]], size: Size): UiState = {
    result match {
      case Left(error)    => UiState(Page.Home(Some(Notice(damagedLink, error))))
      case Right(session) => UiState(Page.Sandbox(session, View.initial.copy(size = size)))
    }
  }

  /** The whole run of a lesson, at step 0. */
  private def lessonSession[S, M, A, O](
      alg: cs553.core.registry.Algorithm[S, M, A, O],
      lesson: Lesson
  ): Either[String, Session[S, M, A, O]] = {
    for {
      preset <- alg.preset(lesson.preset).toRight(s"${alg.name} has no preset ${lesson.preset}.")
      spec = alg.spec(preset, lesson.seed)
      sim <- alg.simulate(spec).left.map(Session.render)
    } yield Session(alg, spec, sim, 0, None, None)
  }

  /** A lesson screen changes only by Next, Back, a click on a node at the question and a resize. */
  private def lessonStep(
      session: Session[?, ?, ?, ?],
      lesson: Lesson,
      index: Int,
      reply: Option[Reply],
      size: Size,
      event: UiEvent
  ): Page = {
    val asking = index == lesson.size - 1
    event match {
      case UiEvent.LessonNext =>
        Page.Lesson(session, lesson, math.min(lesson.size - 1, index + 1), None, size)
      case UiEvent.LessonBack => Page.Lesson(session, lesson, math.max(0, index - 1), None, size)
      case UiEvent.SelectNode(id) if asking =>
        val answered =
          if id == lesson.question.answer then { Reply.Right(lesson.question.right) }
          else { Reply.Wrong(lesson.question.wrong) }
        Page.Lesson(session, lesson, index, Some(answered), size)
      case UiEvent.Resized(next) => Page.Lesson(session, lesson, index, reply, next)
      case _                     => Page.Lesson(session, lesson, index, reply, size)
    }
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
      case UiEvent.ChoosePreset(name) =>
        Page.Sandbox(session.withPreset(name), view.copy(playing = false))
      case UiEvent.ChooseSeed(seed) =>
        Page.Sandbox(session.withSeed(seed), view.copy(playing = false))
      case UiEvent.ChooseSource(source) =>
        Page.Sandbox(session.withSource(source), view.copy(playing = false, panel = Panel.Closed))
      case UiEvent.ChooseOrder(order) =>
        Page.Sandbox(session.withOrder(order), view.copy(playing = false))
      case UiEvent.Copied        => Page.Sandbox(session, view.copy(copy = CopyState.Copied))
      case UiEvent.CopyFailed    => Page.Sandbox(session, view.copy(copy = CopyState.Failed))
      case UiEvent.Resized(size) =>
        if size == view.size then { Page.Sandbox(session, view) }
        else { Page.Sandbox(session, view.copy(size = size)) }
      case UiEvent.SetMode(mode)                   => Page.Sandbox(session, view.copy(mode = mode))
      case UiEvent.LessonNext | UiEvent.LessonBack => Page.Sandbox(session, view)
    }
  }

  private def toggle(open: Panel, wanted: Panel): Panel = if open == wanted then { Panel.Closed }
  else { wanted }

  /** The route that the URL fragment shows for this state. */
  def route(state: UiState): Route = state.page match {
    case Page.Home(_)                    => Route.Home
    case Page.Sandbox(session, _)        => Route.Replay(session.link, Some(session.cursor))
    case Page.Lesson(_, lesson, _, _, _) => Route.Lesson(lesson.algorithm)
  }

  /** The interval of the Play timer, or `None` when Play is off. */
  def tickInterval(state: UiState): Option[Int] = state.page match {
    case Page.Home(_) | Page.Lesson(_, _, _, _, _) => None
    case Page.Sandbox(_, view)                     =>
      if view.playing then { Some(view.speed.intervalMs) }
      else { None }
  }

  def notice(state: UiState): Option[Notice] = state.page match {
    case Page.Home(notice)                               => notice
    case Page.Sandbox(_, _) | Page.Lesson(_, _, _, _, _) => None
  }

  def kind(state: UiState): PageKind = state.page match {
    case Page.Home(_)               => PageKind.Home
    case Page.Sandbox(_, _)         => PageKind.Sandbox
    case Page.Lesson(_, _, _, _, _) => PageKind.Lesson
  }

  /** The lesson screen, or `None` when the page is not a lesson. */
  def lessonScreen(state: UiState): Option[LessonScreen] = state.page match {
    case Page.Lesson(session, lesson, index, reply, size) =>
      session.seek(lesson.stepAt(index)).frame(size).map { frame =>
        LessonScreen(
          session.alg.name,
          frame,
          lesson.textAt(index),
          index,
          lesson.size,
          index == lesson.size - 1,
          reply,
          lesson.algorithm
        )
      }
    case Page.Home(_) | Page.Sandbox(_, _) => None
  }

  /** The sandbox screen, or `None` on the home screen. */
  def screen(state: UiState): Option[Screen] = state.page match {
    case Page.Home(_) | Page.Lesson(_, _, _, _, _) => None
    case Page.Sandbox(session, view)               =>
      session.frame(view.size).map { frame =>
        val panel = view.panel match {
          case Panel.Closed       => None
          case Panel.Node(id)     => session.nodePanel(id)
          case Panel.Envelope(id) => session.envelopePanel(frame, id)
        }
        Screen(
          session.alg.name,
          frame,
          session.primary,
          panel,
          session.settings,
          session.branchLine,
          session.refused,
          view.playing,
          view.speed,
          view.copy,
          view.mode,
          Option.when(view.mode == ViewMode.SpaceTime)(session.spaceTime(view.size)),
          Option.when(Lessons.find(session.alg.id).isDefined)(Route.Lesson(session.alg.id))
        )
      }
  }
}
