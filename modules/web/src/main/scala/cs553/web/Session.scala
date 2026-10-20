package cs553.web

import cs553.core.codec.SharedRun
import cs553.core.ids.NodeId
import cs553.core.protocol.ChannelOrder
import cs553.core.protocol.OrderPolicy
import cs553.core.registry.Algorithm
import cs553.core.rng.Seed
import cs553.core.topology.Edge
import cs553.core.trace.Choice
import cs553.core.trace.EnvelopeId
import cs553.core.trace.Mark
import cs553.core.trace.Outcome
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
    refused: Option[String]
) {

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
    if finished then { None }
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
    if live then { applyChoice(Choice.Deliver(id)) }
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
      case Some(action) if live => applyChoice(Choice.Fire(node, action))
      case Some(_) | None       => this
    }
  }

  def seek(step: Int): Session[S, M, A, O] = {
    copy(cursor = math.max(0, math.min(last, step)), refused = None)
  }

  /** One step forward: the next recorded world, or a seeded step at the newest world. */
  def forward: Session[S, M, A, O] = if live then { deliverNext }
  else { seek(cursor + 1) }

  /** Starts a branch at the shown step: a new run that replays the first `cursor` choices. */
  def continueHere: Session[S, M, A, O] = {
    if live then { this }
    else {
      val k = cursor
      Simulator.replay(alg.protocol, spec, sim.choices.take(k)) match {
        case Left(error) => copy(refused = Some(Session.render(error)))
        case Right(next) => copy(sim = next, cursor = k, branchedAt = Some(k), refused = None)
      }
    }
  }

  def restart: Session[S, M, A, O] = {
    Session.start(alg, spec).getOrElse(this)
  }

  def withPreset(name: String): Session[S, M, A, O] = {
    alg.preset(name).fold(this)(p => rebuild(spec.copy(topology = p.topology)))
  }

  def withSeed(value: Long): Session[S, M, A, O] = rebuild(
    spec.copy(policy = Policy.Seeded(Seed(value)))
  )

  def withOrder(order: ChannelOrder): Session[S, M, A, O] = rebuild(spec.copy(order = order))

  private def rebuild(next: RunSpec[A]): Session[S, M, A, O] = {
    Session.start(alg, next) match {
      case Left(error)    => copy(refused = Some(error))
      case Right(session) => session
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

  /** "and sent Token to nodes 3, 4 and 5" or "and sent nothing", from the ids of one step. */
  private def sends(sent: List[EnvelopeId]): String = {
    val labelled = sent.map(id => msgLabel(id) -> id.to)
    val parts = labelled.map(_._1).distinct.map { label =>
      s"$label to ${Session.nodes(labelled.collect { case (`label`, to) => to })}"
    }
    if parts.isEmpty then { "and sent nothing" }
    else { s"and sent ${Frame.list(parts)}" }
  }

  private def status(w: World[S, M]): String = {
    val here = eventsAt(cursor)
    val handled = here.collectFirst {
      case TraceEvent.Stepped(_, _, cause, _, _, sent) => s"${describe(cause)} ${sends(sent)}."
      case TraceEvent.Rejected(_, node, cause, _)      =>
        s"Node ${node.value} refused this: ${describe(cause)}."
      case TraceEvent.Dropped(_, id) => s"${describe(Choice.Drop(id))}."
    }
    val decided = here.collect { case TraceEvent.Decided(_, node, output) =>
      s"Node ${node.value} decided: ${alg.inspectOutput.label(output)}."
    }
    (handled.toList ++ decided).mkString(" ") match {
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
          if !live then { Some(Blocked.Past) }
          else if enabled.contains(b.env.id) then { None }
          else { head.map(Blocked.Behind(_)) }
        b -> blocked
      }
    }
  }

  private def envelopeViews(
      w: World[S, M],
      at: Map[NodeId, Point],
      cards: List[Box]
  ): (List[EnvelopeView], List[MoreView]) = {
    val next = if live then { nextChoice }
    else { None }
    val sentNow = fresh
    val drawn = mailboxes(w).flatMap { case (edge, queue) =>
      at.get(edge.from).zip(at.get(edge.to)).toList.map { case (a, b) =>
        val labels = queue.map { case (buffered, _) => alg.inspectMsg.label(buffered.env.msg) }
        val shared = w.topology.edges.contains(Edge(edge.to, edge.from))
        val spots = Layout.queue(a, b, labels.map(Layout.envelopeWidth), shared, cards)
        // When the queue does not fit, the last spot shows "+n" for the rest.
        val fits = spots.size >= queue.size
        val placed = if fits then { spots.size }
        else { math.max(0, spots.size - 1) }
        val views = queue.zip(labels).zip(spots).take(placed).zipWithIndex.toList.map {
          case ((((buffered, blocked), label), spot), place) =>
            val id = buffered.env.id
            EnvelopeView(
              id,
              label,
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
        (views, more)
      }
    }
    (drawn.flatMap(_._1), drawn.flatMap(_._2))
  }

  private def edgeViews(w: World[S, M], at: Map[NodeId, Point]): List[EdgeView] = {
    val tree = alg.overlay
      .marks(w.states)
      .collect { case Mark.TreeEdge(child, parent) =>
        Set(child, parent)
      }
      .toSet
    val edges = w.topology.edges
    edges.toList.flatMap { e =>
      val back = edges.contains(Edge(e.to, e.from))
      if back && e.from.value > e.to.value then { Nil }
      else {
        at.get(e.from).zip(at.get(e.to)).toList.map { case (a, b) =>
          EdgeView(e.from, e.to, a, b, !back, tree.contains(Set(e.from, e.to)))
        }
      }
    }
  }

  def frame(size: Size): Option[Frame] = sim.worlds.lift(cursor).map { w =>
    val plan = Layout.plan(w.topology, size)
    val points = plan.positions
    val active = current
    val decided = decidedNodes
    val first = shown.collectFirst { case TraceEvent.Decided(_, node, _) => node }
    val next = if live then { nextChoice }
    else { None }
    val nodes = w.states.toList.map { case (node, state) =>
      val actions = if live then {
        w.ctxs.get(node).toList.flatMap { ctx =>
          alg.protocol.actions(ctx, state).zipWithIndex.map { case (a, i) =>
            ActionView(i, actionLabel(a), next.contains(Choice.Fire(node, a)))
          }
        }
      } else { Nil }
      val at = points.getOrElse(node, Point(0, 0))
      NodeView(
        node,
        at,
        plan.side(at),
        alg.inspectState.label(state).split(", ").toList,
        active.contains(node),
        decided.contains(node),
        first.contains(node),
        actions
      )
    }
    def finishedAs(want: Outcome): Option[Int] = finish.collect {
      case (step, outcome) if outcome == want && step <= cursor => step
    }
    val cards =
      nodes.map(n => Layout.labelCard(Layout.labelSpot(n.at, n.side, n.lines.size), n.lines))
    val (envelopes, more) = envelopeViews(w, points, cards)
    Frame(
      plan.box,
      nodes,
      edgeViews(w, points),
      envelopes,
      more,
      cursor,
      last,
      status(w),
      firstDecision.filter(_ <= cursor),
      finishedAs(Outcome.Quiescent),
      finishedAs(Outcome.StepLimit)
    )
  }

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
              case Some(Blocked.Behind(_))   => s"$text (behind the first)"
              case Some(Blocked.Past) | None => text
            }
          }
        }
      PanelView.NodeInfo(
        node,
        alg.inspectState.fields(state),
        waiting,
        lastMessage,
        lastRule,
        decidedNodes.get(node).map(alg.inspectOutput.label)
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
    SettingsView(
      alg.presets.map(p => PresetChoice(p.name, p.title)),
      presetName,
      seed.value,
      spec.order,
      lock
    )
  }

  /** The one primary action: a seeded step, a branch from a past step, or a new run at the end. */
  def primary: Option[Primary] = {
    if !live then {
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
          Some(Primary(s"${actionLabel(action)} node ${node.value}", UiEvent.Forward, None))
        case None => Some(Primary("Run again", UiEvent.Restart, None))
      }
    }
  }

  def branchLine: Option[String] = branchedAt.map { k =>
    s"New branch from step $k. The run restarted and replayed the first $k choices."
  }
}

object Session {

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
