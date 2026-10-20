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

  /** The seed that picks "Deliver next". */
  def seed: Seed = spec.policy match {
    case Policy.Seeded(seed) => seed
    case Policy.Script(_)    => Algorithm.defaultSeed
  }

  def link: String = alg.encodeLink(SharedRun(spec, sim.choices))

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

  private def stepOf(e: TraceEvent[S, M, A, O]): Int = e match {
    case TraceEvent.Started(_, _, _)           => 0
    case TraceEvent.Sent(at, _)                => at.step
    case TraceEvent.Stepped(at, _, _, _, _, _) => at.step
    case TraceEvent.Rejected(at, _, _, _)      => at.step
    case TraceEvent.Dropped(at, _)             => at.step
    case TraceEvent.Decided(at, _, _)          => at.step
    case TraceEvent.Finished(at, _)            => at.step
  }

  private def shown: Vector[TraceEvent[S, M, A, O]] = events.filter(e => stepOf(e) <= cursor)

  private def sentMessages: Map[EnvelopeId, (Int, M)] = events.collect {
    case TraceEvent.Sent(at, env) => env.id -> (at.step, env.msg)
  }.toMap

  private def msgLabel(id: EnvelopeId): String = {
    sentMessages.get(id).fold("a message") { case (_, m) => alg.inspectMsg.label(m) }
  }

  private def actionLabel(a: A): String = alg.inspectAction.label(a)

  private def describe(choice: Choice[A]): String = choice match {
    case Choice.Deliver(id) =>
      s"Node ${id.to.value} handled ${msgLabel(id)} from node ${id.from.value}."
    case Choice.Fire(node, action) => s"Node ${node.value} did ${actionLabel(action)}."
    case Choice.Drop(id)           => s"${msgLabel(id)} from node ${id.from.value} was lost."
  }

  private def status: String = {
    val here = events.filter(e => stepOf(e) == cursor)
    val handled = here.collectFirst {
      case TraceEvent.Stepped(_, _, cause, _, _, _) => describe(cause)
      case TraceEvent.Rejected(_, node, cause, _)   =>
        s"Node ${node.value} refused: ${describe(cause).stripSuffix(".")}."
      case TraceEvent.Dropped(_, id) => describe(Choice.Drop(id))
    }
    val decided = here.collect { case TraceEvent.Decided(_, node, output) =>
      s"Node ${node.value} decided ${alg.inspectOutput.label(output)}."
    }
    (handled.toList ++ decided).mkString(" ") match {
      case "" if cursor == 0 => "Nothing has happened yet."
      case ""                => ""
      case text              => text
    }
  }

  private def current: Option[NodeId] = events.filter(e => stepOf(e) == cursor).collectFirst {
    case TraceEvent.Stepped(_, node, _, _, _, _) => node
    case TraceEvent.Rejected(_, node, _, _)      => node
  }

  private def decidedNodes: Map[NodeId, O] = shown.collect {
    case TraceEvent.Decided(_, node, output) => node -> output
  }.toMap

  private def envelopeViews(w: World[S, M]): List[ChannelView] = {
    val enabled = World.enabled(alg.protocol, w).collect { case Choice.Deliver(id) => id }.toSet
    val next = if live then { nextChoice }
    else { None }
    w.channels.toList.map { case (edge, queue) =>
      val head = queue.headOption.map(b => alg.inspectMsg.label(b.env.msg))
      ChannelView(
        edge.from,
        edge.to,
        w.topology.edges.contains(Edge(edge.to, edge.from)),
        queue.map { b =>
          val id = b.env.id
          val blocked =
            if !live then { Some(Blocked.Past) }
            else if enabled.contains(id) then { None }
            else { Some(Blocked.Behind(head.getOrElse(""))) }
          EnvelopeView(
            id,
            alg.inspectMsg.label(b.env.msg),
            alg.inspectMsg.fields(b.env.msg),
            sentMessages.get(id).map(_._1),
            blocked,
            next.contains(Choice.Deliver(id))
          )
        }
      )
    }
  }

  private def edgeViews(w: World[S, M]): List[EdgeView] = {
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
      else { List(EdgeView(e.from, e.to, !back, tree.contains(Set(e.from, e.to)))) }
    }
  }

  def frame: Option[Frame] = sim.worlds.lift(cursor).map { w =>
    val points = Layout.positions(w.topology)
    val active = current
    val decided = decidedNodes
    val nodes = w.states.toList.map { case (node, state) =>
      val actions = if live then {
        w.ctxs.get(node).toList.flatMap { ctx =>
          alg.protocol.actions(ctx, state).zipWithIndex.map { case (a, i) =>
            ActionView(i, alg.inspectAction.label(a))
          }
        }
      } else { Nil }
      NodeView(
        node,
        points.getOrElse(node, Point(0, 0)),
        alg.inspectState.label(state),
        active.contains(node),
        decided.contains(node),
        actions
      )
    }
    val firstDecision = events.collectFirst { case TraceEvent.Decided(at, _, _) => at.step }
    val finish = events.collectFirst { case TraceEvent.Finished(at, outcome) => (at.step, outcome) }
    def finishedAs(want: Outcome): Option[Int] = finish.collect {
      case (step, outcome) if outcome == want && step <= cursor => step
    }
    Frame(
      Layout.box(points.values),
      nodes,
      edgeViews(w),
      envelopeViews(w),
      cursor,
      last,
      status,
      firstDecision.filter(_ <= cursor),
      finishedAs(Outcome.Quiescent),
      finishedAs(Outcome.StepLimit)
    )
  }

  def nodePanel(node: NodeId): Option[PanelView] = {
    sim.worlds.lift(cursor).flatMap(_.states.get(node)).map { state =>
      val mine = shown.collect { case TraceEvent.Stepped(at, `node`, cause, rule, _, _) =>
        (at.step, cause, rule.value)
      }
      val lastMessage = mine.reverse.collectFirst { case (step, Choice.Deliver(id), _) =>
        s"${msgLabel(id)} from node ${id.from.value} at step $step"
      }
      val lastRule = mine.lastOption.map { case (step, _, rule) => s"$rule at step $step" }
      PanelView.NodeInfo(
        node,
        alg.inspectState.label(state),
        alg.inspectState.fields(state),
        lastMessage,
        lastRule,
        decidedNodes.get(node).map(alg.inspectOutput.label)
      )
    }
  }

  def envelopePanel(id: EnvelopeId): Option[PanelView] = {
    frame.flatMap(_.channels.flatMap(_.queue).find(_.id == id)).map { env =>
      PanelView.EnvelopeInfo(env, id.from, id.to, env.blocked.isEmpty)
    }
  }

  def settings: SettingsView = {
    val lock = alg.protocol.order match {
      case OrderPolicy.Locked(order) =>
        Some(s"${alg.name} needs ${Session.orderName(order)} channels.")
      case OrderPolicy.Default(_) => None
    }
    SettingsView(
      alg.presets.map(_.name),
      presetName,
      seed.value,
      spec.order == ChannelOrder.Fifo,
      lock
    )
  }

  /** The one primary action: a seeded step, a branch from a past step, or a new run at the end. */
  def primary: Option[Primary] = {
    if !live then { Some(Primary(s"Continue from step $cursor", UiEvent.ContinueHere)) }
    else {
      nextChoice match {
        case Some(Choice.Deliver(_)) | Some(Choice.Drop(_)) =>
          Some(Primary("Deliver next", UiEvent.Forward))
        case Some(Choice.Fire(node, action)) =>
          Some(Primary(s"${actionLabel(action)} node ${node.value}", UiEvent.Forward))
        case None => Some(Primary("Run again", UiEvent.Restart))
      }
    }
  }

  def branchLine: Option[String] = branchedAt.map { k =>
    s"New branch from step $k. The run restarted and replayed the first $k choices."
  }
}

object Session {

  /** A new run of `spec` at step 0. */
  def start[S, M, A, O](
      alg: Algorithm[S, M, A, O],
      spec: RunSpec[A]
  ): Either[String, Session[S, M, A, O]] = {
    Simulator.start(alg.protocol, spec) match {
      case Left(error) => Left(render(error))
      case Right(sim)  => Right(Session(alg, spec, sim, 0, None, None))
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
