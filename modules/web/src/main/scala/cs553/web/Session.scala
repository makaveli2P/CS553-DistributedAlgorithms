package cs553.web

import cs553.core.codec.SharedRun
import cs553.core.ids.NodeId
import cs553.core.protocol.ChannelOrder
import cs553.core.protocol.OrderPolicy
import cs553.core.registry.Algorithm
import cs553.core.registry.Recordings
import cs553.core.rng.Seed
import cs553.core.topology.Edge
import cs553.core.trace.ChannelRecord
import cs553.core.trace.Choice
import cs553.core.trace.EdgeStyle
import cs553.core.trace.Engine
import cs553.core.trace.EnvelopeId
import cs553.core.trace.Mark
import cs553.core.trace.Outcome
import cs553.core.trace.Overlay
import cs553.core.trace.Policy
import cs553.core.trace.RunSpec
import cs553.core.trace.TraceEvent
import cs553.core.world.Buffered
import cs553.core.world.ChoiceError
import cs553.core.world.RunError
import cs553.core.world.Simulation
import cs553.core.world.Simulator
import cs553.core.world.World

/** One sandbox run of one algorithm: the spec, the simulation so far and the shown step.
  *
  * `sim.worlds(k)` is the world after `k` steps. A scrub only moves `cursor`. It never runs the
  * engine backward. All functions are pure.
  */
final case class Session[S, M, A, O](
    alg: Algorithm[S, M, A, O],
    spec: RunSpec[A],
    sim: Simulation[S, M, A, O],
    cursor: Int,
    branchedAt: Option[Int],
    refused: Option[String],
    source: RunSource = RunSource.Browser
) {

  /** A recording of real Akka actors. The learner can scrub and play it, and nothing else. */
  def recorded: Boolean = source != RunSource.Browser

  /** The index of the newest world. */
  def last: Int = sim.worlds.size - 1

  def live: Boolean = cursor == last

  def finished: Boolean = sim.run.outcome.isDefined

  /** The seed that picks "Deliver next". `Session.start` and `Session.fromLink` refuse a spec with
    * a `Script` policy, so the second case does not occur.
    */
  def seed: Seed = spec.policy match {
    case Policy.Seeded(seed) => seed
    case Policy.Script(_)    => Algorithm.defaultSeed
  }

  lazy val link: String = alg.encodeLink(SharedRun(spec, sim.choices))

  def presetName: Option[String] = alg.presets.find(_.topology == spec.topology).map(_.name)

  /** The choice that "Deliver next" applies at the newest world. */
  def nextChoice: Option[Choice[A]] = {
    if finished || recorded then { None }
    else {
      sim.last.flatMap { w =>
        Simulator.seededChoice(seed, w.step, World.enabled(alg.protocol, w))
      }
    }
  }

  def deliverNext: Session[S, M, A, O] = {
    if live then { nextChoice.fold(this)(c => applyChoice(c)) }
    else { this }
  }

  def deliver(id: EnvelopeId): Session[S, M, A, O] = {
    if live && !recorded then { applyChoice(Choice.Deliver(id)) }
    else { this }
  }

  def fire(node: NodeId, index: Int): Session[S, M, A, O] = {
    val offered = for {
      w <- sim.last
      ctx <- w.ctxs.get(node)
      state <- w.states.get(node)
      action <- alg.protocol.actions(ctx, state).lift(index)
    } yield action
    offered match {
      case Some(action) if live && !recorded => applyChoice(Choice.Fire(node, action))
      case Some(_) | None                    => this
    }
  }

  def seek(step: Int): Session[S, M, A, O] = {
    copy(cursor = math.max(0, math.min(last, step)), refused = None)
  }

  /** One step forward: the next recorded world, or a seeded step at the newest world. */
  def forward: Session[S, M, A, O] = if recorded then { seek(cursor + 1) }
  else if live then { deliverNext }
  else { seek(cursor + 1) }

  /** Starts a branch at the shown step: a new run that replays the first `cursor` choices. */
  def continueHere: Session[S, M, A, O] = {
    if live || recorded then { this }
    else {
      val k = cursor
      Simulator.replay(alg.protocol, spec, sim.choices.take(k)) match {
        case Left(error) => copy(refused = Some(Session.render(error)))
        case Right(next) => copy(sim = next, cursor = k, branchedAt = Some(k), refused = None)
      }
    }
  }

  def restart: Session[S, M, A, O] = {
    if recorded then { seek(0) }
    else { Session.start(alg, spec).getOrElse(this) }
  }

  /** The run from another source: the browser simulator on the default preset, or a recording. */
  def withSource(next: RunSource): Session[S, M, A, O] = {
    if next == source then { this }
    else {
      val result = next.engine match {
        case None         => Session.open(alg)
        case Some(engine) => Session.recorded(alg, next, engine)
      }
      result.fold(error => copy(refused = Some(error)), identity)
    }
  }

  def withPreset(name: String): Session[S, M, A, O] = {
    alg.preset(name).fold(this)(p => rebuild(spec.copy(topology = p.topology)))
  }

  def withSeed(value: Long): Session[S, M, A, O] = rebuild(
    spec.copy(policy = Policy.Seeded(Seed(value)))
  )

  def withOrder(order: ChannelOrder): Session[S, M, A, O] = rebuild(spec.copy(order = order))

  private def rebuild(next: RunSpec[A]): Session[S, M, A, O] = {
    if recorded then { this }
    else {
      Session.start(alg, next) match {
        case Left(error)    => copy(refused = Some(error))
        case Right(session) => session
      }
    }
  }

  private def applyChoice(choice: Choice[A]): Session[S, M, A, O] = {
    Simulator.advance(alg.protocol, spec, sim, List(choice)) match {
      case Left(error) => copy(refused = Some(Session.render(error)))
      case Right(next) => copy(sim = next, cursor = next.worlds.size - 1, refused = None)
    }
  }

  // ---------------------------------------------------------------------------------------------
  // The view model. Strings come only from `Inspect` and from trace events (coding rules 5 and 6).
  // ---------------------------------------------------------------------------------------------

  private def events: Vector[TraceEvent[S, M, A, O]] = sim.run.trace

  // Session is immutable, so each index is computed at most once for each session value.
  private lazy val byStep: Map[Int, Vector[TraceEvent[S, M, A, O]]] = events.groupBy(Session.stepOf)

  private lazy val sentMessages: Map[EnvelopeId, (Int, M)] = events.collect {
    case TraceEvent.Sent(at, env) => env.id -> (at.step, env.msg)
  }.toMap

  private lazy val firstDecision: Option[Int] = events.collectFirst {
    case TraceEvent.Decided(at, _, _) => at.step
  }

  private lazy val finish: Option[(Int, Outcome)] = events.collectFirst {
    case TraceEvent.Finished(at, outcome) => (at.step, outcome)
  }

  private def eventsAt(step: Int): Vector[TraceEvent[S, M, A, O]] =
    byStep.getOrElse(step, Vector.empty)

  private def shown: Vector[TraceEvent[S, M, A, O]] = (0 to cursor).toVector.flatMap(eventsAt)

  private def msgLabel(id: EnvelopeId): String = {
    sentMessages.get(id).fold("a message") { case (_, m) => alg.inspectMsg.label(m) }
  }

  private def actionLabel(a: A): String = alg.inspectAction.label(a)

  private def describe(choice: Choice[A]): String = choice match {
    case Choice.Deliver(id) =>
      s"Node ${id.to.value} handled ${msgLabel(id)} from node ${id.from.value}"
    case Choice.Fire(node, action) => s"Node ${node.value} ran ${actionLabel(action)}"
    case Choice.Drop(id)           => s"${msgLabel(id)} from node ${id.from.value} was lost"
  }

  /** " and sent Token to nodes 3, 4 and 5", or nothing, from the ids of one step. */
  private def sends(sent: List[EnvelopeId]): String = {
    val labelled = sent.map(id => msgLabel(id) -> id.to)
    val parts = labelled.map(_._1).distinct.map { label =>
      s"$label to ${Session.nodes(labelled.collect { case (`label`, to) => to })}"
    }
    if parts.isEmpty then { "" }
    else { s" and sent ${Frame.list(parts)}" }
  }

  /** The decisions of the shown step, one line each. */
  private def decisions: List[String] = eventsAt(cursor).toList.collect {
    case TraceEvent.Decided(_, node, output) =>
      s"Node ${node.value} decided: ${alg.inspectOutput.label(output)}."
  }

  private def status(w: World[S, M]): String = {
    val here = eventsAt(cursor)
    val handled = here.collectFirst {
      case TraceEvent.Stepped(_, _, cause, _, _, sent) => s"${describe(cause)}${sends(sent)}."
      case TraceEvent.Rejected(_, node, cause, _)      =>
        s"Node ${node.value} refused this: ${describe(cause)}."
      case TraceEvent.Dropped(_, id) => s"${describe(Choice.Drop(id))}."
    }
    handled.getOrElse("") match {
      case "" if cursor == 0 =>
        w.topology.initiators.toList match {
          case Nil        => "No messages yet."
          case one :: Nil => s"Node ${one.value} is the initiator. No messages yet."
          case initiators =>
            s"Nodes ${Frame.list(initiators.map(_.value.toString))} are initiators. No messages yet."
        }
      case ""   => ""
      case text => text
    }
  }

  private def current: Option[NodeId] = eventsAt(cursor).collectFirst {
    case TraceEvent.Stepped(_, node, _, _, _, _) => node
    case TraceEvent.Rejected(_, node, _, _)      => node
  }

  /** The envelopes that the shown step sent. */
  private def fresh: Set[EnvelopeId] = eventsAt(cursor)
    .collect { case TraceEvent.Stepped(_, _, _, _, _, sent) =>
      sent
    }
    .flatten
    .toSet

  private def decidedNodes: Map[NodeId, O] = shown.collect {
    case TraceEvent.Decided(_, node, output) => node -> output
  }.toMap

  /** Each channel of `w` with its queue, oldest first, and the reason that each envelope waits. */
  private def mailboxes(w: World[S, M]): List[(Edge, Vector[(Buffered[M], Option[Blocked])])] = {
    val enabled = World.enabled(alg.protocol, w).collect { case Choice.Deliver(id) => id }.toSet
    w.channels.toList.map { case (edge, queue) =>
      val head = queue.headOption.map(b => alg.inspectMsg.label(b.env.msg))
      edge -> queue.map { b =>
        val blocked =
          if recorded then { Some(Blocked.Recorded) }
          else if !live then { Some(Blocked.Past) }
          else if enabled.contains(b.env.id) then { None }
          else { head.map(Blocked.Behind(_)) }
        b -> blocked
      }
    }
  }

  /** The envelopes of each queue. Each queue keeps clear of the label cards, of the circles of the
    * other nodes, of the channel notes and of the envelopes of the queues before it.
    */
  private def envelopeViews(
      w: World[S, M],
      at: Map[NodeId, Point],
      cards: List[Box],
      obstacles: List[Box]
  ): (List[EnvelopeView], List[MoreView]) = {
    val next = if live then { nextChoice }
    else { None }
    val sentNow = fresh
    val start = (List.empty[(List[EnvelopeView], List[MoreView])], obstacles)
    val (drawn, _) = mailboxes(w).foldLeft(start) { case ((done, avoid), (edge, queue)) =>
      at.get(edge.from).zip(at.get(edge.to)) match {
        case None         => (done, avoid)
        case Some((a, b)) =>
          val chips = queue.map { case (buffered, _) => alg.inspectMsg.chip(buffered.env.msg) }
          val widths = chips.map(Layout.chipWidth)
          val shared = w.topology.edges.contains(Edge(edge.to, edge.from))
          val others = at.collect {
            case (node, p) if node != edge.from && node != edge.to => Layout.nodeBox(p)
          }.toList
          val spots = Layout.queue(a, b, widths, shared, cards, avoid ++ others)
          // When the queue does not fit, the last spot shows "+n" for the rest.
          val fits = spots.size >= queue.size
          val placed = if fits then { spots.size }
          else { math.max(0, spots.size - 1) }
          val views = queue.zip(chips).zip(spots).take(placed).zipWithIndex.toList.map {
            case ((((buffered, blocked), chip), spot), place) =>
              val id = buffered.env.id
              EnvelopeView(
                id,
                alg.inspectMsg.label(buffered.env.msg),
                chip,
                alg.inspectMsg.fields(buffered.env.msg),
                sentMessages.get(id).map(_._1),
                place,
                blocked,
                next.contains(Choice.Deliver(id)),
                sentNow.contains(id),
                spot,
                b
              )
          }
          val more = if fits then { Nil }
          else { spots.lastOption.toList.map(MoreView(edge.from, edge.to, _, queue.size - placed)) }
          val boxes = spots.zip(widths).map { case (p, wd) => Layout.chipBox(p, wd) }.toList
          ((views, more) :: done, boxes ++ avoid)
      }
    }
    (drawn.reverse.flatMap(_._1), drawn.reverse.flatMap(_._2))
  }

  /** For each node with a `Cut` mark: the step of the first world with the mark. */
  private lazy val cuts: Map[NodeId, Int] = Overlay.cutSteps(alg.overlay, sim.worlds.map(_.states))

  private def edgeViews(
      w: World[S, M],
      at: Map[NodeId, Point],
      marks: List[Mark]
  ): List[EdgeView] = {
    val styles = marks.collect {
      case Mark.OnEdge(from, to, style) if style != EdgeStyle.WaitsFor => Set(from, to) -> style
    }.toMap
    val waits = marks.collect { case Mark.OnEdge(from, to, EdgeStyle.WaitsFor) => (from, to) }.toSet
    val records = marks.collect { case Mark.Channel(from, to, record, _) =>
      Set(from, to) -> record
    }
    def record(pair: Set[NodeId]): Option[ChannelRecord] = {
      val mine = records.collect { case (`pair`, r) => r }
      if mine.contains(ChannelRecord.Recording) then { Some(ChannelRecord.Recording) }
      else { mine.headOption }
    }
    val edges = w.topology.edges
    edges.toList.flatMap { e =>
      val back = edges.contains(Edge(e.to, e.from))
      if back && e.from.value > e.to.value then { Nil }
      else {
        val pair = Set(e.from, e.to)
        // A wait-for mark in one direction only gives the pair of channels a direction.
        val (from, to, directed) =
          if !back then { (e.from, e.to, true) }
          else if waits(e.from -> e.to) && !waits(e.to -> e.from) then { (e.from, e.to, true) }
          else if waits(e.to -> e.from) && !waits(e.from -> e.to) then { (e.to, e.from, true) }
          else { (e.from, e.to, false) }
        at.get(from).zip(at.get(to)).toList.map { case (a, b) =>
          EdgeView(from, to, a, b, directed, styles.get(pair), record(pair))
        }
      }
    }
  }

  /** The non-empty `Channel` marks as text next to their channels, near the receiver, which records
    * the channel. Each note takes the free spot that covers the fewest obstacles.
    */
  private def noteViews(
      at: Map[NodeId, Point],
      marks: List[Mark],
      obstacles: List[Box]
  ): List[NoteView] = {
    val wanted = marks.collect {
      case Mark.Channel(from, to, _, recorded) if recorded.nonEmpty => (from, to, recorded)
    }
    val nodes = at.values.toList.map(Layout.nodeBox)
    val start = (List.empty[NoteView], obstacles ++ nodes)
    val (notes, _) = wanted.foldLeft(start) { case ((done, avoid), (from, to, recorded)) =>
      at.get(from).zip(at.get(to)) match {
        case None         => (done, avoid)
        case Some((a, b)) =>
          val text = recorded.mkString(", ")
          val spot = Layout.noteSpot(a, b, text, avoid)
          (NoteView(from, to, spot, text) :: done, Layout.noteBox(spot, text) :: avoid)
      }
    }
    notes.reverse
  }

  private def actionViews(
      w: World[S, M],
      node: NodeId,
      state: S,
      next: Option[Choice[A]]
  ): List[ActionView] = {
    if live && !recorded then {
      w.ctxs.get(node).toList.flatMap { ctx =>
        alg.protocol.actions(ctx, state).zipWithIndex.map { case (a, i) =>
          ActionView(i, actionLabel(a), next.contains(Choice.Fire(node, a)))
        }
      }
    } else { Nil }
  }

  def frame(size: Size): Option[Frame] = sim.worlds.lift(cursor).map { w =>
    val plan = Layout.plan(w.topology, size)
    val points = plan.positions
    val active = current
    val decided = decidedNodes
    val marks = alg.overlay.marks(w.states)
    val fills = marks.collect { case Mark.Fill(node, fill) => node -> fill }.toMap
    val badges = marks.collect { case Mark.Badge(node, text) => node -> text }
    val next = if live then { nextChoice }
    else { None }
    val nodes = w.states.toList.map { case (node, state) =>
      val actions = actionViews(w, node, state, next)
      val at = points.getOrElse(node, Point(0, 0))
      NodeView(
        node,
        at,
        plan.side(at),
        alg.inspectState.label(state).split(", ").toList ++
          badges.collect { case (`node`, text) => text },
        active.contains(node),
        decided.contains(node),
        fills.get(node),
        cuts.get(node).filter(_ <= cursor),
        actions
      )
    }
    def finishedAs(want: Outcome): Option[Int] = finish.collect {
      case (step, outcome) if outcome == want && step <= cursor => step
    }
    val spots = nodes.map(n => n -> Layout.labelSpot(n.at, n.side, n.lines.size))
    val cards = spots.map { case (n, spot) => Layout.labelCard(spot, n.lines) }
    // The view box grows to hold each label and action chip, so that nothing is ever cut.
    val box = Layout.fit(
      plan.box,
      cards ++ spots.flatMap { case (n, spot) => Layout.actionBox(spot, n.actions) },
      8.0
    )
    val notes = noteViews(points, marks, cards)
    val noteBoxes = notes.map(n => Layout.noteBox(n.at, n.text))
    val (envelopes, more) = envelopeViews(w, points, cards, noteBoxes)
    Frame(
      box,
      nodes,
      edgeViews(w, points, marks),
      envelopes,
      more,
      notes,
      cursor,
      last,
      status(w),
      decisions,
      marks.collect { case Mark.Fact(text) => text },
      firstDecision.filter(_ <= cursor),
      finishedAs(Outcome.Quiescent),
      finishedAs(Outcome.StepLimit),
      alg.invariant(w)
    )
  }

  /** The run up to the shown step, in the terms of the space-time view. */
  def spaceModel: SpaceModel = {
    val delivered: Map[EnvelopeId, Int] = events.collect {
      case TraceEvent.Stepped(at, _, Choice.Deliver(id), _, _, _) => id -> at.step
      case TraceEvent.Rejected(at, _, Choice.Deliver(id), _)      => id -> at.step
    }.toMap
    val decisions = events.collect { case TraceEvent.Decided(at, node, output) =>
      SpaceDot(
        node,
        at.step,
        DotKind.Decided,
        s"Node ${node.value} decided: ${alg.inspectOutput.label(output)}"
      )
    }.toList
    val decidedHere = decisions.map(d => (d.node, d.step)).toSet
    val steps = events.toList.collect {
      case TraceEvent.Stepped(at, node, cause, _, _, _) if !decidedHere((node, at.step)) =>
        SpaceDot(node, at.step, DotKind.Step, s"Step ${at.step}: ${describe(cause)}")
      case TraceEvent.Rejected(at, node, cause, _) if !decidedHere((node, at.step)) =>
        SpaceDot(node, at.step, DotKind.Rejected, s"Step ${at.step}: refused, ${describe(cause)}")
    }
    val messages = events.toList.collect {
      case TraceEvent.Sent(at, env) if at.step <= cursor =>
        SpaceMessage(env.id, msgLabel(env.id), at.step, delivered.get(env.id).filter(_ <= cursor))
    }
    def finishedAs(want: Outcome): Option[Int] = finish.collect {
      case (step, outcome) if outcome == want && step <= cursor => step
    }
    SpaceModel(
      sim.worlds.headOption.fold(List.empty[NodeId])(_.states.keys.toList),
      last,
      cursor,
      (steps ++ decisions).filter(_.step <= cursor).sortBy(_.step),
      messages,
      cuts.filter(_._2 <= cursor),
      firstDecision.filter(_ <= cursor),
      finishedAs(Outcome.Quiescent)
    )
  }

  def spaceTime(size: Size): SpaceLayout = SpaceTime.layout(spaceModel, size)

  def nodePanel(node: NodeId): Option[PanelView] = {
    sim.worlds.lift(cursor).flatMap(w => w.states.get(node).map(w -> _)).map { case (w, state) =>
      val mine = shown.collect { case TraceEvent.Stepped(at, `node`, cause, rule, _, _) =>
        (at.step, cause, rule.value)
      }
      val lastMessage = mine.reverse.collectFirst { case (step, Choice.Deliver(id), _) =>
        s"${msgLabel(id)} from node ${id.from.value}, step $step"
      }
      val lastRule = mine.lastOption.map { case (step, _, rule) => s"$rule, step $step" }
      val waiting =
        mailboxes(w).collect { case (edge, queue) if edge.to == node => queue }.flatMap { queue =>
          queue.toList.map { case (buffered, blocked) =>
            val text =
              s"${alg.inspectMsg.label(buffered.env.msg)} from node ${buffered.env.id.from.value}"
            blocked match {
              case Some(Blocked.Behind(_))                            => s"$text (behind the first)"
              case Some(Blocked.Past) | Some(Blocked.Recorded) | None => text
            }
          }
        }
      PanelView.NodeInfo(
        node,
        alg.inspectState.fields(state),
        waiting,
        lastMessage,
        lastRule,
        decidedNodes.get(node).map(alg.inspectOutput.label),
        actionViews(
          w,
          node,
          state,
          if live then { nextChoice }
          else { None }
        )
      )
    }
  }

  def envelopePanel(frame: Frame, id: EnvelopeId): Option[PanelView] = {
    frame.envelopes.find(_.id == id).map(env => PanelView.EnvelopeInfo(env, env.blocked.isEmpty))
  }

  def settings: SettingsView = {
    val lock = alg.protocol.order match {
      case OrderPolicy.Locked(order) =>
        Some(s"${alg.name} needs ${Session.orderName(order)} channels.")
      case OrderPolicy.Default(_) => None
    }
    val sources = RunSource.values.toList.filter { src =>
      src.engine.forall(engine => Recordings.text(alg.id, engine).isDefined)
    }
    val line = source match {
      case RunSource.Browser          => None
      case RunSource.RecordedMediated => Some(Session.recordingLine)
      case RunSource.RecordedFree     =>
        Some(
          s"${Session.recordingLine} No node saw the whole network, so it stopped at the first decision."
        )
    }
    SettingsView(
      source,
      sources,
      line,
      alg.presets.map(p => PresetChoice(p.name, p.title)),
      presetName,
      seed.value,
      spec.order,
      lock
    )
  }

  /** The one primary action: a seeded step, a branch from a past step, or a new run at the end. */
  def primary: Option[Primary] = {
    if recorded then {
      if live then { Some(Primary("Replay from the start", UiEvent.Restart, None)) }
      else { Some(Primary("Next step", UiEvent.Forward, None)) }
    } else if !live then {
      Some(
        Primary(
          s"Continue from step $cursor",
          UiEvent.ContinueHere,
          Some("Deliver next then repeats this run. Click an envelope to take another path.")
        )
      )
    } else {
      nextChoice match {
        case Some(Choice.Deliver(_)) | Some(Choice.Drop(_)) =>
          Some(Primary("Deliver next", UiEvent.Forward, None))
        case Some(Choice.Fire(node, action)) =>
          Some(Primary(s"Node ${node.value}: ${actionLabel(action)}", UiEvent.Forward, None))
        case None => Some(Primary("Run again", UiEvent.Restart, None))
      }
    }
  }

  def branchLine: Option[String] = branchedAt.map { k =>
    s"New branch from step $k. The run restarted and replayed the first $k choices."
  }
}

object Session {

  /** The one quiet line of a recording. */
  val recordingLine: String = "This is a recording of real Akka actors."

  /** A recording of `alg` by the engine of `source`, at step 0. */
  def recorded[S, M, A, O](
      alg: Algorithm[S, M, A, O],
      source: RunSource,
      engine: Engine
  ): Either[String, Session[S, M, A, O]] = {
    alg.recording(engine) match {
      case None              => Left(s"${alg.name} has no ${source.label.toLowerCase}.")
      case Some(Left(error)) => Left(error)
      case Some(Right(sim))  =>
        sim.run.trace
          .collectFirst { case TraceEvent.Started(spec, _, _) => spec }
          .toRight("The recording has no start.")
          .flatMap(seededOnly)
          .map(spec => Session(alg, spec, sim, 0, None, None, source))
    }
  }

  def stepOf[S, M, A, O](e: TraceEvent[S, M, A, O]): Int = e match {
    case TraceEvent.Started(_, _, _)           => 0
    case TraceEvent.Sent(at, _)                => at.step
    case TraceEvent.Stepped(at, _, _, _, _, _) => at.step
    case TraceEvent.Rejected(at, _, _, _)      => at.step
    case TraceEvent.Dropped(at, _)             => at.step
    case TraceEvent.Decided(at, _, _)          => at.step
    case TraceEvent.Finished(at, _)            => at.step
  }

  /** "node 3" or "nodes 3, 4 and 5". */
  def nodes(ids: List[NodeId]): String = ids match {
    case one :: Nil => s"node ${one.value}"
    case many       => s"nodes ${Frame.list(many.map(_.value.toString))}"
  }

  /** The web runs only seeded specs: "Deliver next" needs a seed. */
  private def seededOnly[A](spec: RunSpec[A]): Either[String, RunSpec[A]] = spec.policy match {
    case Policy.Seeded(_) => Right(spec)
    case Policy.Script(_) => Left("This link holds a scripted run, which the browser cannot open.")
  }

  /** A new run of `spec` at step 0. */
  def start[S, M, A, O](
      alg: Algorithm[S, M, A, O],
      spec: RunSpec[A]
  ): Either[String, Session[S, M, A, O]] = {
    seededOnly(spec).flatMap { seeded =>
      Simulator.start(alg.protocol, seeded) match {
        case Left(error) => Left(render(error))
        case Right(sim)  => Right(Session(alg, seeded, sim, 0, None, None))
      }
    }
  }

  def open[S, M, A, O](alg: Algorithm[S, M, A, O]): Either[String, Session[S, M, A, O]] = {
    alg.defaultSpec.toRight(s"${alg.name} has no topology preset.").flatMap(start(alg, _))
  }

  /** The run of a shared link, shown at `step`, or at the newest step when `step` is absent. */
  def fromLink[S, M, A, O](
      alg: Algorithm[S, M, A, O],
      link: String,
      step: Option[Int]
  ): Either[String, Session[S, M, A, O]] = {
    for {
      shared <- alg.decodeLink(link).left.map(_.toList.map(_.render).mkString("; "))
      _ <- seededOnly(shared.spec)
      sim <- alg.replay(shared).left.map(render)
    } yield {
      val last = sim.worlds.size - 1
      Session(
        alg,
        shared.spec,
        sim,
        step.fold(last)(s => math.max(0, math.min(last, s))),
        None,
        None
      )
    }
  }

  def orderName(order: ChannelOrder): String = order match {
    case ChannelOrder.Fifo     => "FIFO"
    case ChannelOrder.AnyOrder => "any-order"
  }

  def render[A](error: RunError[A]): String = error match {
    case RunError.AlgorithmMismatch(expected, found) =>
      s"The run is for ${found.value}, not ${expected.value}."
    case RunError.OrderLocked(required, _) =>
      s"This algorithm needs ${orderName(required)} channels."
    case RunError.BadStepLimit(limit)     => s"The step limit $limit is not valid."
    case RunError.BadTopology(errors)     => errors.toList.map(_.render).mkString("; ")
    case RunError.BadChoice(step, _, why) => s"Step $step is not legal: ${render(why)}."
    case RunError.AfterStop(step, _, _)   => s"The run stopped before step $step."
  }

  def render(error: ChoiceError): String = error match {
    case ChoiceError.NotBuffered(id) =>
      s"no channel holds the envelope ${id.seq} from node ${id.from.value}"
    case ChoiceError.NotDeliverable(_, _)   => "the channel delivers its oldest envelope first"
    case ChoiceError.UnknownNode(node)      => s"node ${node.value} is not in the topology"
    case ChoiceError.ActionNotOffered(node) => s"node ${node.value} does not offer that action now"
  }
}
