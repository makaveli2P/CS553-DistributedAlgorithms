package cs553.core.algorithms.deadlock

import cs553.core.ids.NodeId
import cs553.core.ids.RuleId
import cs553.core.protocol.ChannelOrder
import cs553.core.protocol.NodeCtx
import cs553.core.protocol.ProtocolError
import cs553.core.protocol.Send
import cs553.core.registry.BrachaTouegEntry
import cs553.core.rng.Seed
import cs553.core.topology.NodeAttrs
import cs553.core.topology.Role
import cs553.core.topology.Topology
import cs553.core.trace.Choice
import cs553.core.trace.EdgeStyle
import cs553.core.trace.EnvelopeId
import cs553.core.trace.Inspect
import cs553.core.trace.Mark
import cs553.core.trace.NodeFill
import cs553.core.trace.Outcome
import cs553.core.trace.Overlay
import cs553.core.trace.Policy
import cs553.core.trace.RunSpec
import cs553.core.trace.StopRule
import cs553.core.trace.TraceEvent
import cs553.core.world.Graphs
import cs553.core.world.PropertyBudget
import cs553.core.world.Simulation
import cs553.core.world.Simulator
import cs553.core.world.World
import org.scalacheck.Gen
import org.scalatest.EitherValues
import org.scalatest.funsuite.AnyFunSuite

import scala.annotation.tailrec
import scala.collection.immutable.SortedMap
import scala.collection.immutable.SortedSet

final class BrachaTouegSpec extends AnyFunSuite with PropertyBudget with EitherValues {

  private type BtSim = Simulation[BtState, BtMsg, BtAction, BtOutput]
  private type BtEvent = TraceEvent[BtState, BtMsg, BtAction, BtOutput]
  private type Waits = Map[NodeId, (List[NodeId], Int)]

  private val orders: Gen[ChannelOrder] = Gen.oneOf(ChannelOrder.Fifo, ChannelOrder.AnyOrder)

  private def n(value: Int): NodeId = NodeId(value)

  /** A wait-for graph on the nodes `0 .. size-1`. `request` gives N for each node, or `None` for
    * N-of-N.
    */
  private def waitFor(
      edges: List[(Int, Int)],
      initiator: Int,
      request: Map[Int, Int] = Map.empty,
      size: Int = 0
  ): Topology = {
    val ids = (edges.flatMap { case (a, b) => List(a, b) } ++ (0 until size) :+ initiator).distinct
    val attrs = ids.map { id =>
      val role = if id == initiator then { Role.Initiator }
      else { Role.NonInitiator }
      val extra = request.get(id).map(k => NodeAttrs.Keys.request -> k.toString).toMap
      n(id) -> NodeAttrs(role, None, extra)
    }.toMap
    Graphs.fixed(
      Topology.directed(ids.map(n), edges.map { case (a, b) => (n(a), n(b)) }, attrs)
    )
  }

  /** A random directed wait-for graph with 1 to 8 nodes and one random initiator. With `kOfN`, each
    * node gets a random N in `0 .. M`. Without it, N is M (the AND model).
    */
  private def waitForGraphs(kOfN: Boolean): Gen[Topology] = for {
    size <- Gen.choose(1, 8)
    density <- Gen.choose(0.1, 0.5)
    flags <- Gen.listOfN(size * size, Gen.choose(0.0, 1.0))
    initiator <- Gen.choose(0, size - 1)
    picks <- Gen.listOfN(size, Gen.choose(0, 8))
    plain = {
      val edges = for {
        a <- (0 until size).toList
        b <- (0 until size).toList
        if a != b && flags.lift(a * size + b).exists(_ < density)
      } yield (a, b)
      val request =
        if kOfN then {
          (0 until size).map { a =>
            val m = edges.count(_._1 == a)
            a -> (picks.lift(a).getOrElse(0) % (m + 1))
          }.toMap
        } else { Map.empty[Int, Int] }
      waitFor(edges, initiator, request, size)
    }
    moved <- Gen.oneOf(Gen.const(plain), Graphs.relabel(plain))
  } yield moved

  private val anyWaitFor: Gen[Topology] = Gen.oneOf(waitForGraphs(false), waitForGraphs(true))

  private def simulate(topology: Topology, seed: Long, order: ChannelOrder): BtSim = {
    val spec = RunSpec(
      BrachaToueg.id,
      topology,
      order,
      Policy.Seeded(Seed(seed)),
      StopRule.AtQuiescence,
      100000
    )
    Simulator.run(BrachaToueg.protocol, spec).value
  }

  private def finalStates(sim: BtSim): SortedMap[NodeId, BtState] = {
    sim.last.map(_.states).getOrElse(SortedMap.empty)
  }

  private def waitsOf(topology: Topology): Waits = BrachaToueg.waitsOfTopology(topology).value

  /** The nodes that the initiator reaches along wait-for edges, the initiator too. */
  private def reachable(topology: Topology): Set[NodeId] = {
    @tailrec
    def loop(seen: Set[NodeId], frontier: List[NodeId]): Set[NodeId] = frontier match {
      case Nil          => seen
      case next :: rest =>
        val fresh = topology.outNeighbours(next).filterNot(seen.contains).toList
        loop(seen ++ fresh, fresh ++ rest)
    }
    val start = topology.initiators.toSet
    loop(start, start.toList)
  }

  private def sentMessages(events: Seq[BtEvent]): Map[EnvelopeId, BtMsg] = events.collect {
    case TraceEvent.Sent(_, env) => env.id -> env.msg
  }.toMap

  private def count(events: Seq[BtEvent], msg: BtMsg): Int = events.count {
    case TraceEvent.Sent(_, env) => env.msg == msg
    case _                       => false
  }

  private def granting(s: BtState): Boolean = s.grantPhase match {
    case GrantPhase.Granting(_, _) => true
    case _                         => false
  }

  /** The steps that end `notify` with `Done` to the parent, and still wait for an `Ack`. */
  private def earlyDone(events: Seq[BtEvent]): List[NodeId] = {
    val msgs = sentMessages(events)
    events.toList.collect {
      case TraceEvent.Stepped(_, node, _, rule, after, sent)
          if rule != BrachaToueg.Rules.repeatNotify &&
            sent.exists(id => msgs.get(id).contains(BtMsg.Done)) && granting(after) =>
        node
    }
  }

  /** A scheduler that always delivers the envelope with the lowest rank, in `AnyOrder`. Ties go to
    * the canonical order. It returns the events and the last world.
    */
  private def drive(
      topology: Topology,
      rank: (BtMsg, EnvelopeId) => Int
  ): (Vector[BtEvent], World[BtState, BtMsg]) = {
    val start = World.init(BrachaToueg.protocol, topology, ChannelOrder.AnyOrder).value
    @tailrec
    def loop(
        w: World[BtState, BtMsg],
        events: Vector[BtEvent]
    ): (Vector[BtEvent], World[BtState, BtMsg]) = {
      val enabled = World.enabled(BrachaToueg.protocol, w)
      def score(c: Choice[BtAction]): Int = c match {
        case Choice.Deliver(id) =>
          w.channel(id.channel).find(_.env.id == id).fold(1000)(b => rank(b.env.msg, id))
        case Choice.Fire(_, _) => -1
        case Choice.Drop(_)    => 1000
      }
      enabled.sortBy(score).headOption match {
        case None         => (events, w)
        case Some(choice) =>
          val (next, more) = World.next(BrachaToueg.protocol, w, choice).value
          loop(next, events ++ more)
      }
    }
    loop(start, Vector.empty)
  }

  private def decisions(events: Seq[BtEvent]): List[(NodeId, BtOutput)] = events.toList.collect {
    case TraceEvent.Decided(_, node, output) => node -> output
  }

  private def rules(events: Seq[BtEvent]): List[(NodeId, RuleId)] = events.toList.collect {
    case TraceEvent.Stepped(_, node, _, rule, _, _) => node -> rule
  }

  // -----------------------------------------------------------------------------------------------
  // The oracle.

  test("the oracle frees the sinks, then the nodes that get N grants, until nothing changes") {
    val waits: Waits = Map(
      n(0) -> (List(n(1), n(2)), 1),
      n(1) -> (List(n(3)), 1),
      n(3) -> (List(n(1)), 1),
      n(2) -> (List(n(4), n(5)), 2),
      n(4) -> (Nil, 0),
      n(5) -> (List(n(4)), 1)
    )
    assert(BrachaToueg.freeNodes(waits) == SortedSet(n(0), n(2), n(4), n(5)))
    assert(BrachaToueg.deadlockedNodes(waits) == SortedSet(n(1), n(3)))
    assert(BrachaToueg.freeNodes(Map(n(0) -> (List(n(1)), 0), n(1) -> (List(n(0)), 1))).size == 2)
  }

  // -----------------------------------------------------------------------------------------------
  // Properties over random wait-for graphs (N-of-N and k-of-n), seeds and both channel orders.

  test("property: each run ends quiescent, has no Rejected event, and only the initiator decides") {
    forAll(anyWaitFor, Gen.long, orders) { (topology: Topology, seed: Long, order: ChannelOrder) =>
      val sim = simulate(topology, seed, order)
      assert(sim.run.outcome.contains(Outcome.Quiescent))
      assert(sim.run.trace.collect { case r @ TraceEvent.Rejected(_, _, _, _) => r }.isEmpty)
      assert(sim.run.decisions.map(_._1).toList == topology.initiators.toList)
    }
  }

  test("property: the verdict and the deadlocked set equal the fixpoint reduction (N-of-N)") {
    forAll(waitForGraphs(false), Gen.long, orders) {
      (topology: Topology, seed: Long, order: ChannelOrder) =>
        verdictMatchesOracle(topology, seed, order)
    }
  }

  test("property: the verdict and the deadlocked set equal the fixpoint reduction (k-of-n)") {
    forAll(waitForGraphs(true), Gen.long, orders) {
      (topology: Topology, seed: Long, order: ChannelOrder) =>
        verdictMatchesOracle(topology, seed, order)
    }
  }

  private def verdictMatchesOracle(topology: Topology, seed: Long, order: ChannelOrder): Unit = {
    val sim = simulate(topology, seed, order)
    val states = finalStates(sim)
    val free = BrachaToueg.freeNodes(waitsOf(topology))
    val seen = reachable(topology)
    val initiator = topology.initiators.toList
    val expected = initiator.map { i =>
      val verdict = if free.contains(i) then { BtOutput.NotDeadlocked }
      else { BtOutput.Deadlocked }
      i -> verdict
    }
    assert(sim.run.decisions.toList == expected)
    // Each node that the initiator reaches is free exactly when the oracle frees it.
    seen.foreach(node => assert(states.get(node).map(_.isFree).contains(free.contains(node))))
    val deadlocked = states.collect { case (node, s) if seen(node) && !s.isFree => node }.toSet
    assert(deadlocked == BrachaToueg.deadlockedNodes(waitsOf(topology)).toSet.intersect(seen))
    // A node that the initiator does not reach is never free in error.
    states.foreach { case (node, s) => assert(!s.isFree || free.contains(node)) }
  }

  test("property: a node ends notify with Done only after it gets all its Acks") {
    forAll(anyWaitFor, Gen.long, orders) { (topology: Topology, seed: Long, order: ChannelOrder) =>
      val sim = simulate(topology, seed, order)
      assert(earlyDone(sim.run.trace) == Nil)
      // The initiator also decides only after its grant is complete.
      sim.run.trace.collect { case TraceEvent.Decided(_, node, _) => node }.foreach { node =>
        assert(finalStates(sim).get(node).exists(s => !granting(s)))
      }
    }
  }

  test("property: the grant runs at most once for each node: one Grant on each reverse edge") {
    forAll(anyWaitFor, Gen.long, orders) { (topology: Topology, seed: Long, order: ChannelOrder) =>
      val sim = simulate(topology, seed, order)
      val grants = sim.run.trace.collect {
        case TraceEvent.Sent(_, env) if env.msg == BtMsg.Grant => env.id.channel
      }
      assert(grants.distinct.size == grants.size)
      grants.foreach(edge =>
        assert(topology.edges.contains(cs553.core.topology.Edge(edge.to, edge.from)))
      )
      val starts = rules(sim.run.trace).collect { case (node, BrachaToueg.Rules.lastGrant) =>
        node
      }
      assert(starts.distinct.size == starts.size)
    }
  }

  test("property: the message counts follow from the reached edges and the free nodes") {
    forAll(anyWaitFor, Gen.long, orders) { (topology: Topology, seed: Long, order: ChannelOrder) =>
      val sim = simulate(topology, seed, order)
      val trace = sim.run.trace
      val reachedEdges = reachable(topology).toList.map(topology.outDegree).sum
      val grants = finalStates(sim).values.filter(_.isFree).map(_.waitedBy.size).sum
      assert(count(trace, BtMsg.Notify) == reachedEdges)
      assert(count(trace, BtMsg.Done) == reachedEdges)
      assert(count(trace, BtMsg.Grant) == grants)
      assert(count(trace, BtMsg.Ack) == grants)
    }
  }

  test("property: the invariant holds in each world of a run") {
    forAll(anyWaitFor, Gen.long, orders) { (topology: Topology, seed: Long, order: ChannelOrder) =>
      simulate(topology, seed, order).worlds.foreach { w =>
        assert(BrachaToueg.invariant(w) == None)
      }
    }
  }

  // -----------------------------------------------------------------------------------------------
  // The presets.

  private def preset(name: String): Topology = {
    Graphs.fixed(BrachaTouegEntry.algorithm.preset(name).map(_.topology).toRight(name))
  }

  test("mixed-6: nodes 1 and 3 are deadlocked, and the initiator is not, for 20 seeds") {
    (1L to 20L).foreach { seed =>
      val sim = simulate(preset("mixed-6"), seed, ChannelOrder.AnyOrder)
      assert(sim.run.decisions.toList == List(n(0) -> BtOutput.NotDeadlocked))
      val stuck = finalStates(sim).collect { case (node, s) if !s.isFree => node }.toList
      assert(stuck == List(n(1), n(3)))
    }
  }

  test("mixed-6: the overlay at the end marks the deadlocked set, the free nodes and the badges") {
    val sim = simulate(preset("mixed-6"), 1L, ChannelOrder.Fifo)
    val marks = Overlay[BtState].marks(finalStates(sim))
    val fills = marks.collect { case Mark.Fill(node, fill) => node -> fill }
    assert(fills.collect { case (node, NodeFill.Chosen) => node } == List(n(1), n(3)))
    // The last fill of a node wins, so the canvas shows the deadlocked set.
    assert(
      fills.toMap == Map(
        n(0) -> NodeFill.Free,
        n(1) -> NodeFill.Deadlocked,
        n(2) -> NodeFill.Free,
        n(3) -> NodeFill.Deadlocked,
        n(4) -> NodeFill.Free,
        n(5) -> NodeFill.Free
      )
    )
    val removed = marks.collect { case Mark.OnEdge(a, b, EdgeStyle.Removed) => (a.value, b.value) }
    assert(removed.toSet == Set((0, 2), (2, 4), (5, 4), (2, 5)))
    val badges = marks.collect { case Mark.Badge(node, text) => node.value -> text }.toMap
    assert(
      badges == Map(
        0 -> "1 of 2",
        1 -> "1 of 1",
        2 -> "2 of 2",
        3 -> "1 of 1",
        4 -> "0 of 0",
        5 -> "1 of 1"
      )
    )
  }

  test("college-deadlock: the cycle is deadlocked, and no node sends Grant") {
    val sim = simulate(preset("college-deadlock"), 7L, ChannelOrder.Fifo)
    assert(sim.run.decisions.toList == List(n(0) -> BtOutput.Deadlocked))
    assert(count(sim.run.trace, BtMsg.Grant) == 0)
    assert(finalStates(sim).values.forall(!_.isFree))
  }

  test("college-free: each node becomes free, and the initiator is not deadlocked") {
    val sim = simulate(preset("college-free"), 7L, ChannelOrder.Fifo)
    assert(sim.run.decisions.toList == List(n(0) -> BtOutput.NotDeadlocked))
    assert(finalStates(sim).values.forall(_.isFree))
  }

  // -----------------------------------------------------------------------------------------------
  // Regressions of the college code (docs/notes/2026-10-review-of-college-code.md, bracha-toueg).

  /** The review DAG `0 -> W, 0 -> X, W -> Y, W -> Z, X -> Y, Y -> Z` with W=1, X=2, Y=3, Z=4. */
  private val reviewDag: Topology =
    waitFor(List(0 -> 1, 0 -> 2, 1 -> 3, 1 -> 4, 2 -> 3, 3 -> 4), initiator = 0)

  test(
    "regression: on the review DAG a node sends Done only after all its Acks (no false deadlock)"
  ) {
    // The college schedule: Notify and Done go first, and Grant and Ack wait as long as possible.
    val (events, _) = drive(
      reviewDag,
      (msg, _) =>
        msg match {
          case BtMsg.Notify => 0
          case BtMsg.Done   => 1
          case BtMsg.Ack    => 2
          case BtMsg.Grant  => 3
        }
    )
    assert(earlyDone(events) == Nil)
    assert(decisions(events) == List(n(0) -> BtOutput.NotDeadlocked))
    // Z (4) frees W (1) and Y (3), so Z must wait for two Acks before its Done.
    // The Done to the parent of Z. A Done for a later Notify goes at once, as in Fokkink.
    val zDone = events.indexWhere {
      case TraceEvent.Stepped(_, node, _, rule, _, sent) =>
        node == n(4) && rule != BrachaToueg.Rules.repeatNotify &&
        sent.exists(id => sentMessages(events).get(id).contains(BtMsg.Done))
      case _ => false
    }
    val zLastAck = events.lastIndexWhere {
      case TraceEvent.Stepped(_, node, Choice.Deliver(id), _, _, _) =>
        node == n(4) && sentMessages(events).get(id).contains(BtMsg.Ack)
      case _ => false
    }
    // Z sends its Done in the step of its last Ack.
    assert(zLastAck >= 0 && zDone == zLastAck)
    (1L to 50L).foreach { seed =>
      val sim = simulate(reviewDag, seed, ChannelOrder.AnyOrder)
      assert(earlyDone(sim.run.trace) == Nil)
      assert(sim.run.decisions.toList == List(n(0) -> BtOutput.NotDeadlocked))
    }
  }

  test("regression: grant runs at most once when Grant comes before Notify (no false freedom)") {
    // 0 waits for 2, 1 and 3; 1 waits for 2; 3 and 4 wait for each other. Node 0 is deadlocked.
    val topology = waitFor(List(0 -> 2, 0 -> 1, 0 -> 3, 1 -> 2, 3 -> 4, 4 -> 3), initiator = 0)
    // Grant to node 1 goes before the Notify from node 0 to node 1.
    val (events, last) = drive(
      topology,
      (msg, id) =>
        (msg, id.to.value) match {
          case (BtMsg.Notify, 1) => 9
          case (BtMsg.Grant, _)  => 0
          case (BtMsg.Ack, _)    => 1
          case _                 => 2
        }
    )
    val node1 = rules(events).collect { case (node, rule) if node == n(1) => rule }
    assert(
      node1.indexOf(BrachaToueg.Rules.lastGrant) < node1.indexOf(BrachaToueg.Rules.firstNotify)
    )
    val grantsFrom1 = events.count {
      case TraceEvent.Sent(_, env) => env.id.from == n(1) && env.msg == BtMsg.Grant
      case _                       => false
    }
    assert(grantsFrom1 == 1)
    assert(decisions(events) == List(n(0) -> BtOutput.Deadlocked))
    assert(last.states.get(n(0)).exists(_.requests == 1))
  }

  test("regression: a Notify to a node that already grants starts no second grant") {
    val ctx = NodeCtx(n(1), SortedSet(n(0), n(2)), SortedSet(n(0), n(2)))
    val state = BtState(
      Role.NonInitiator,
      List(n(2)),
      List(n(0)),
      1,
      0,
      NotifyPhase.Idle,
      GrantPhase.Granting(1, Some(n(2)))
    )
    val step = BrachaToueg.protocol.onMessage(ctx, state, n(0), BtMsg.Notify).value
    assert(step.sends == List(Send(n(2), BtMsg.Notify)))
    assert(step.state.grantPhase == state.grantPhase)
  }

  test(
    "regression: In comes from Out, so the college deadlock data with In fixed grants correctly"
  ) {
    // The college data had In_0 = [] and In_2 = [], although 3 -> 0 and 1 -> 2. Here node 3
    // requests nothing, so each node becomes free only when In is correct.
    val topology = waitFor(List(0 -> 1, 0 -> 3, 1 -> 2, 2 -> 3, 3 -> 0), 0, Map(3 -> 0))
    val world = World.init(BrachaToueg.protocol, topology, ChannelOrder.Fifo).value
    assert(world.states.get(n(0)).map(_.waitedBy).contains(List(n(3))))
    assert(world.states.get(n(2)).map(_.waitedBy).contains(List(n(1))))
    val sim = simulate(topology, 3L, ChannelOrder.AnyOrder)
    assert(sim.run.decisions.toList == List(n(0) -> BtOutput.NotDeadlocked))
    assert(finalStates(sim).values.forall(_.isFree))
  }

  test("regression: an initiator with no edges decides at Wake") {
    val sim = simulate(waitFor(Nil, initiator = 0), 1L, ChannelOrder.Fifo)
    assert(rules(sim.run.trace) == List(n(0) -> BrachaToueg.Rules.wake))
    assert(sim.run.decisions.toList == List(n(0) -> BtOutput.NotDeadlocked))
    assert(sim.run.outcome.contains(Outcome.Quiescent))
  }

  test("regression: a Grant to a node that needs no more grants gets an Ack at once") {
    val ctx = NodeCtx(n(0), SortedSet(n(1), n(2)), SortedSet(n(1), n(2)))
    val state =
      BtState(Role.Initiator, List(n(1), n(2)), Nil, 1, 0, NotifyPhase.Notified, GrantPhase.Free)
    val step = BrachaToueg.protocol.onMessage(ctx, state, n(2), BtMsg.Grant).value
    assert(step.sends == List(Send(n(2), BtMsg.Ack)))
    assert(step.rule == BrachaToueg.Rules.extraGrant)
    assert(step.decision.isEmpty)
  }

  test("regression: the initiator decides exactly once, also when Acks come after its Dones") {
    // 1 waits for 0, and 0 waits for 2: the Ack from 1 can come after the Done from 2.
    val topology = waitFor(List(1 -> 0, 0 -> 2), initiator = 0)
    (1L to 20L).foreach { seed =>
      val sim = simulate(topology, seed, ChannelOrder.AnyOrder)
      assert(sim.run.decisions.toList == List(n(0) -> BtOutput.NotDeadlocked))
    }
  }

  // -----------------------------------------------------------------------------------------------
  // Topology checks, illegal input, the invariant and Inspect.

  test("check rejects two initiators and an N that is not in 0 to M, and makes the channels") {
    val two = Graphs.fixed(waitFor(List(0 -> 1), 0).withInitiators(Set(n(0), n(1))))
    assert(BrachaToueg.protocol.check(two).isLeft)
    assert(BrachaToueg.protocol.check(waitFor(List(0 -> 1), 0, Map(0 -> 2))).isLeft)
    val bad = Graphs.fixed(Topology.fromDot("digraph { 0 [initiator=true, request=x]; 1; 0 -> 1 }"))
    assert(BrachaToueg.protocol.check(bad).isLeft)
    val checked = BrachaToueg.protocol.check(waitFor(List(0 -> 1), 0)).value
    assert(checked.isSymmetric)
    assert(BrachaToueg.protocol.check(checked) == Right(checked))
  }

  test("illegal messages and a second Wake are rejected") {
    val ctx = NodeCtx(n(0), SortedSet(n(1)), SortedSet(n(1)))
    val idle = BtState(Role.Initiator, List(n(1)), Nil, 1, 1, NotifyPhase.Idle, GrantPhase.Blocked)
    val notifying = idle.copy(notifyPhase = NotifyPhase.Notifying(None, 1))
    val p = BrachaToueg.protocol
    assert(p.onMessage(ctx, idle, n(1), BtMsg.Done).isLeft)
    assert(p.onMessage(ctx, notifying, n(1), BtMsg.Notify).isLeft)
    assert(p.onMessage(ctx, notifying, n(1), BtMsg.Ack).isLeft)
    assert(p.onAction(ctx, notifying, BtAction.Wake).left.value match {
      case ProtocolError.IllegalAction(_) => true
      case _                              => false
    })
  }

  test("the invariant holds on a preset run and breaks on a forged world") {
    val topology = preset("college-deadlock")
    val world = World.init(BrachaToueg.protocol, topology, ChannelOrder.Fifo).value
    assert(BrachaToueg.invariant(world) == None)
    val forged = world.copy(states =
      world.states.updatedWith(n(0))(_.map(_.copy(grantPhase = GrantPhase.Free)))
    )
    assert(
      BrachaToueg.invariant(forged) == Some(
        "Node 0 is free, but the wait-for graph cannot free it."
      )
    )
    val counted = world.copy(states = world.states.updatedWith(n(1))(_.map(_.copy(requests = 5))))
    assert(BrachaToueg.invariant(counted).exists(_.contains("Node 1 needs 5 more grants")))
  }

  test("Inspect gives labels and fields for the state, the message, the action and the output") {
    val state = BtState(
      Role.NonInitiator,
      List(n(2), n(3)),
      List(n(0)),
      2,
      1,
      NotifyPhase.Notifying(Some(n(0)), 2),
      GrantPhase.Blocked
    )
    assert(Inspect[BtState].label(state) == "notifying, needs 1 grant")
    assert(
      Inspect[BtState].fields(state).take(3) == List(
        "role" -> "not an initiator",
        "request" -> "2 of 2",
        "waits for" -> "2, 3"
      )
    )
    assert(Inspect[BtState].label(state.copy(grantPhase = GrantPhase.Free)) == "notifying, free")
    assert(Inspect[BtMsg].label(BtMsg.Grant) == "Grant")
    assert(Inspect[BtAction].label(BtAction.Wake) == "Wake")
    assert(Inspect[BtOutput].label(BtOutput.Deadlocked) == "the initiator is deadlocked")
  }
}
