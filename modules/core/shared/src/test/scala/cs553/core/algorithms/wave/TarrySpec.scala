package cs553.core.algorithms.wave

import cs553.core.ids.NodeId
import cs553.core.ids.RuleId
import cs553.core.protocol.ChannelOrder
import cs553.core.protocol.NodeCtx
import cs553.core.protocol.ProtocolError
import cs553.core.registry.TarryEntry
import cs553.core.rng.Seed
import cs553.core.topology.Edge
import cs553.core.topology.NodeAttrs
import cs553.core.topology.Role
import cs553.core.topology.Topology
import cs553.core.trace.Choice
import cs553.core.trace.EdgeStyle
import cs553.core.trace.Inspect
import cs553.core.trace.Mark
import cs553.core.trace.NodeFill
import cs553.core.trace.Outcome
import cs553.core.trace.Overlay
import cs553.core.trace.Policy
import cs553.core.trace.Run
import cs553.core.trace.RunSpec
import cs553.core.trace.StopRule
import cs553.core.trace.TraceEvent
import cs553.core.world.Graphs
import cs553.core.world.PropertyBudget
import cs553.core.world.Simulator
import cs553.core.world.World
import org.scalacheck.Gen
import org.scalatest.EitherValues
import org.scalatest.funsuite.AnyFunSuite

import scala.annotation.tailrec
import scala.collection.immutable.SortedMap
import scala.collection.immutable.SortedSet

final class TarrySpec extends AnyFunSuite with PropertyBudget with EitherValues {

  private type TarryRun = Run[TarryState, TarryMsg, TarryAction, TarryOutput]

  private val orders: Gen[ChannelOrder] = Gen.oneOf(ChannelOrder.Fifo, ChannelOrder.AnyOrder)

  private def ids(values: Int*): List[NodeId] = values.toList.map(NodeId(_))

  private def spec(topology: Topology, seed: Long, order: ChannelOrder) = RunSpec(
    Tarry.id,
    topology,
    order,
    Policy.Seeded(Seed(seed)),
    StopRule.AtQuiescence,
    100000
  )

  private def runTarry(
      topology: Topology,
      seed: Long,
      order: ChannelOrder = ChannelOrder.Fifo
  ): TarryRun = Simulator.run(Tarry.protocol, spec(topology, seed, order)).value.run

  /** The worlds of a run, one for each step, from the Simulator. */
  private def worlds(
      topology: Topology,
      seed: Long,
      order: ChannelOrder
  ): Vector[World[TarryState, TarryMsg]] = {
    Simulator.run(Tarry.protocol, spec(topology, seed, order)).value.worlds
  }

  private def finalStates(run: TarryRun): Map[NodeId, TarryState] = {
    val initial = run.trace.collectFirst { case TraceEvent.Started(_, _, states) => states }
    run.trace.foldLeft(initial.getOrElse(Map.empty)) {
      case (states, TraceEvent.Stepped(_, node, _, _, after, _)) => states.updated(node, after)
      case (states, _)                                           => states
    }
  }

  /** The channels of the sends, in trace order. */
  private def sends(run: TarryRun): List[Edge] = run.trace.toList.collect {
    case TraceEvent.Sent(_, env) => env.id.channel
  }

  private def rules(run: TarryRun): List[RuleId] = run.trace.toList.collect {
    case TraceEvent.Stepped(_, _, _, rule, _, _) => rule
  }

  @tailrec
  private def rootOf(
      states: Map[NodeId, TarryState],
      node: NodeId,
      seen: Set[NodeId]
  ): Option[NodeId] = {
    if seen.contains(node) then { None }
    else {
      states.get(node).flatMap(_.parent) match {
        case None         => Some(node)
        case Some(parent) => rootOf(states, parent, seen + node)
      }
    }
  }

  private def line(size: Int): Topology = Graphs.fixed(
    Topology.undirected(
      (0 until size).map(NodeId(_)),
      (1 until size).map(i => NodeId(i - 1) -> NodeId(i)),
      Graphs.initiatorAttrs
    )
  )

  private val ring4: Topology = Graphs.fixed(
    Topology.undirected(
      ids(1, 2, 3, 4),
      List(1 -> 2, 2 -> 3, 3 -> 4, 4 -> 1).map { case (a, b) => NodeId(a) -> NodeId(b) },
      Map(NodeId(1) -> NodeAttrs(Role.Initiator, None, Map.empty))
    )
  )

  // -----------------------------------------------------------------------------------------------
  // Properties over random connected graphs, random initiators, seeds and both channel orders.

  test("property: each run ends quiescent, with no Rejected event") {
    forAll(Graphs.connectedAnyInitiator, Gen.long, orders) {
      (topology: Topology, seed: Long, order: ChannelOrder) =>
        val run = runTarry(topology, seed, order)
        assert(run.outcome.contains(Outcome.Quiescent))
        assert(run.trace.collect { case r @ TraceEvent.Rejected(_, _, _, _) => r }.isEmpty)
    }
  }

  test("property: exactly one token exists in each world of the run") {
    forAll(Graphs.connectedAnyInitiator, Gen.long, orders) {
      (topology: Topology, seed: Long, order: ChannelOrder) =>
        val all = worlds(topology, seed, order)
        assert(all.nonEmpty)
        all.foreach { w =>
          assert(Tarry.tokens(w) == 1)
          assert(w.buffered.size <= 1)
          assert(Tarry.invariant(w).isEmpty)
        }
    }
  }

  test("property: each channel carries the token exactly once in each direction (2|E| sends)") {
    forAll(Graphs.connectedAnyInitiator, Gen.long, orders) {
      (topology: Topology, seed: Long, order: ChannelOrder) =>
        val used = sends(runTarry(topology, seed, order))
        // An undirected topology holds each edge in both directions, so edges.size == 2|E|.
        assert(used.size == topology.edges.size)
        assert(used.toSet == topology.edges.toSet)
    }
  }

  test("property: a non-initiator passes the token to its parent last") {
    forAll(Graphs.connectedAnyInitiator, Gen.long, orders) {
      (topology: Topology, seed: Long, order: ChannelOrder) =>
        val run = runTarry(topology, seed, order)
        val states = finalStates(run)
        val used = sends(run)
        topology.nodes.filterNot(topology.initiators.contains).foreach { node =>
          val out = used.filter(_.from == node).map(_.to)
          assert(out.lastOption == states.get(node).flatMap(_.parent), s"node $node")
          assert(states.get(node).map(_.phase).contains(TarryPhase.Done))
        }
    }
  }

  test("property: the parent edges form a spanning tree rooted at the initiator") {
    forAll(Graphs.connectedAnyInitiator, Gen.long, orders) {
      (topology: Topology, seed: Long, order: ChannelOrder) =>
        val states = finalStates(runTarry(topology, seed, order))
        val initiator = topology.initiators.toList
        assert(initiator.size == 1)
        initiator.foreach { root =>
          assert(states.get(root).flatMap(_.parent).isEmpty)
          topology.nodes.foreach { node =>
            assert(rootOf(states, node, Set.empty).contains(root), s"node $node")
            states.get(node).flatMap(_.parent).foreach { parent =>
              assert(topology.outNeighbours(node).contains(parent))
            }
          }
        }
    }
  }

  test("property: only the initiator decides, exactly once, as the last step of the run") {
    forAll(Graphs.connectedAnyInitiator, Gen.long, orders) {
      (topology: Topology, seed: Long, order: ChannelOrder) =>
        val run = runTarry(topology, seed, order)
        assert(run.decisions.map(_._1).toList == topology.initiators.toList)
        val events = run.trace.toList
        val decidedAt = events.indexWhere {
          case TraceEvent.Decided(_, _, _) => true
          case _                           => false
        }
        assert(events.drop(decidedAt + 1).forall {
          case TraceEvent.Finished(_, _) => true
          case _                         => false
        })
    }
  }

  test("property: at the end each node sent and received the token through each of its channels") {
    forAll(Graphs.connectedAnyInitiator, Gen.long, orders) {
      (topology: Topology, seed: Long, order: ChannelOrder) =>
        val states = finalStates(runTarry(topology, seed, order))
        topology.nodes.foreach { node =>
          val neighbours = topology.outNeighbours(node)
          assert(states.get(node).map(_.sent).contains(neighbours), s"node $node")
          assert(states.get(node).map(_.received).contains(neighbours), s"node $node")
        }
    }
  }

  // -----------------------------------------------------------------------------------------------
  // Examples.

  test("on the line 0-1-2 the rules fire in one fixed order and the token goes 0-1-2-1-0") {
    val run = runTarry(line(3), 1L)
    assert(
      rules(run) == List(
        Tarry.Rules.wake,
        Tarry.Rules.pass,
        Tarry.Rules.firstToken,
        Tarry.Rules.pass,
        Tarry.Rules.firstToken,
        Tarry.Rules.passToParent,
        Tarry.Rules.tokenBack,
        Tarry.Rules.passToParent,
        Tarry.Rules.decide
      )
    )
    assert(sends(run).map(e => (e.from.value, e.to.value)) == List(0 -> 1, 1 -> 2, 2 -> 1, 1 -> 0))
  }

  test("on a ring of 4, different seeds give different traversals of 8 messages") {
    val traversals = (0L until 20L).map { seed =>
      val run = runTarry(ring4, seed)
      assert(sends(run).size == 8)
      assert(run.decisions.toList == List(NodeId(1) -> TarryOutput.TraversalComplete))
      sends(run)
    }
    assert(traversals.toSet.size > 1)
  }

  test("an initiator with no neighbours decides at Wake") {
    val alone = Graphs.fixed(Topology.undirected(ids(0), Nil, Graphs.initiatorAttrs))
    val run = runTarry(alone, 0L)
    assert(rules(run) == List(Tarry.Rules.wakeAlone))
    assert(run.decisions.toList == List(NodeId(0) -> TarryOutput.TraversalComplete))
  }

  test("actions: a holding node may pass to the parent only when no other channel is left") {
    val ctx = NodeCtx(NodeId(1), SortedSet(NodeId(0), NodeId(2)), SortedSet(NodeId(0), NodeId(2)))
    val fresh = TarryState(
      Role.NonInitiator,
      TarryPhase.Holding,
      Some(NodeId(0)),
      SortedSet.empty,
      SortedSet(NodeId(0)),
      2
    )
    assert(Tarry.protocol.actions(ctx, fresh) == List(TarryAction.Pass(NodeId(2))))
    assert(Tarry.protocol.onAction(ctx, fresh, TarryAction.Pass(NodeId(0))).isLeft)
    val back = fresh.copy(sent = SortedSet(NodeId(2)), received = SortedSet(NodeId(0), NodeId(2)))
    assert(Tarry.protocol.actions(ctx, back) == List(TarryAction.Pass(NodeId(0))))
    assert(Tarry.protocol.onAction(ctx, back, TarryAction.Pass(NodeId(2))).isLeft)
  }

  test("a second token, a token before Wake and a token after Done are rejected") {
    val ctx = NodeCtx(NodeId(1), SortedSet(NodeId(0)), SortedSet(NodeId(0)))
    def state(role: Role, phase: TarryPhase) =
      TarryState(role, phase, None, SortedSet.empty, SortedSet.empty, 1)
    List(
      state(Role.NonInitiator, TarryPhase.Holding),
      state(Role.Initiator, TarryPhase.Idle),
      state(Role.NonInitiator, TarryPhase.Done),
      state(Role.Initiator, TarryPhase.Decided),
      state(Role.NonInitiator, TarryPhase.Waiting).copy(received = SortedSet(NodeId(0)))
    ).foreach { s =>
      assert(
        Tarry.protocol.onMessage(ctx, s, NodeId(0), TarryMsg.Token).left.value match {
          case ProtocolError.UnexpectedMessage(from, _) => from == NodeId(0)
          case _                                        => false
        },
        s.toString
      )
    }
  }

  test("check rejects a directed graph, a disconnected graph and a wrong number of initiators") {
    val directed = Graphs.fixed(Topology.ring(3, Graphs.initiatorAttrs))
    val disconnected = Graphs.fixed(
      Topology.undirected(ids(0, 1, 2), List(NodeId(1) -> NodeId(2)), Graphs.initiatorAttrs)
    )
    val none = Graphs.fixed(Topology.undirected(ids(0, 1), List(NodeId(0) -> NodeId(1))))
    val two = Graphs.fixed(line(3).withInitiators(Set(NodeId(0), NodeId(2))))
    assert(Tarry.protocol.check(directed).left.value.size == 1)
    assert(Tarry.protocol.check(disconnected).left.value.size == 1)
    assert(Tarry.protocol.check(none).left.value.size == 1)
    assert(Tarry.protocol.check(two).left.value.size == 1)
    assert(Tarry.protocol.check(line(4)) == Right(line(4)))
    val ctx = NodeCtx(NodeId(0), SortedSet.empty, SortedSet(NodeId(1)))
    assert(Tarry.protocol.init(ctx, NodeAttrs(Role.Initiator, None, Map.empty)).isLeft)
  }

  // -----------------------------------------------------------------------------------------------
  // Overlay and invariant.

  test("overlay: at the end of each preset run, 2|E| used marks, |V| - 1 tree edges, one fill") {
    TarryEntry.algorithm.presets.foreach { preset =>
      val topology = preset.topology
      val states = SortedMap.from(finalStates(runTarry(topology, 7L)))
      val marks = Overlay[TarryState].marks(states)
      val used = marks.collect { case Mark.OnEdge(a, b, EdgeStyle.Used) => Edge(a, b) }
      val tree = marks.collect { case m @ Mark.OnEdge(_, _, EdgeStyle.Tree) => m }
      assert(used.toSet == topology.edges.toSet, preset.name)
      assert(tree.size == topology.nodes.size - 1, preset.name)
      assert(
        marks.collect { case Mark.Fill(n, NodeFill.Chosen) => n } == topology.initiators.toList
      )
      assert(marks.collect { case b @ Mark.Badge(_, _) => b }.isEmpty)
    }
  }

  test("invariant: it holds on the preset runs and breaks on forged worlds") {
    val topology = line(3)
    val all = worlds(topology, 3L, ChannelOrder.Fifo)
    assert(all.forall(w => Tarry.invariant(w).isEmpty))
    val start = all.headOption.getOrElse(fail("no world"))
    // Two nodes hold the token.
    val twoTokens = start.copy(states =
      start.states.updated(
        NodeId(2),
        TarryState(Role.NonInitiator, TarryPhase.Holding, None, SortedSet.empty, SortedSet.empty, 1)
      )
    )
    assert(Tarry.invariant(twoTokens).contains("The world holds 2 tokens, not exactly one."))
    // Node 1 passed the token to its parent 0 before it used the channel to 2.
    val end = all.lastOption.getOrElse(fail("no world"))
    val early = end.copy(states =
      end.states.updated(
        NodeId(1),
        TarryState(
          Role.NonInitiator,
          TarryPhase.Done,
          Some(NodeId(0)),
          SortedSet(NodeId(0)),
          SortedSet(NodeId(0)),
          2
        )
      )
    )
    assert(
      Tarry
        .invariant(early)
        .contains(
          "Node 1 passed the token to its parent before it used each other channel."
        )
    )
  }

  // -----------------------------------------------------------------------------------------------
  // Regressions for the verified bugs of the college code (docs/notes/2026-10-review-of-college-code.md).

  test("regression: the mutants that the college tests let pass fail here (ring of 4)") {
    // Mutant A stops at the first return, mutant B returns to the parent at once, and mutant C
    // picks the parent first. Each one sends fewer than 8 messages, or sends to the parent early.
    (0L until 20L).foreach { seed =>
      val run = runTarry(ring4, seed)
      assert(sends(run).size == 8)
      assert(sends(run).toSet == ring4.edges.toSet)
      val states = finalStates(run)
      ids(2, 3, 4).foreach { node =>
        assert(sends(run).filter(_.from == node).map(_.to).lastOption == states(node).parent)
      }
    }
  }

  test("regression: the initiator has no parent, so the parent edges form a tree") {
    val states = finalStates(runTarry(line(3), 1L))
    assert(states.get(NodeId(0)).flatMap(_.parent).isEmpty)
    assert(states.get(NodeId(1)).flatMap(_.parent).contains(NodeId(0)))
    assert(states.get(NodeId(2)).flatMap(_.parent).contains(NodeId(1)))
  }

  test("regression: the initiator comes from the topology, and the run replays from its seed") {
    val preset = TarryEntry.algorithm.presets.find(_.name == "inputTarry").getOrElse(fail("preset"))
    assert(preset.topology.initiators.toList == ids(1))
    assert(!preset.topology.nodes.contains(NodeId(0)))
    assert(runTarry(preset.topology, 7L) == runTarry(preset.topology, 7L))
  }

  test("regression: Wake is legal only for the initiator, so one source of truth gives the role") {
    val ctx = NodeCtx(NodeId(1), SortedSet(NodeId(0)), SortedSet(NodeId(0)))
    val idle =
      TarryState(Role.NonInitiator, TarryPhase.Idle, None, SortedSet.empty, SortedSet.empty, 1)
    assert(Tarry.protocol.actions(ctx, idle).isEmpty)
    assert(Tarry.protocol.onAction(ctx, idle, TarryAction.Wake).left.value match {
      case ProtocolError.IllegalAction(_) => true
      case _                              => false
    })
  }

  test("regression: the run gives a typed trace, a parent tree and a decision, not log lines") {
    val run = runTarry(line(3), 1L)
    assert(run.decisions.toList == List(NodeId(0) -> TarryOutput.TraversalComplete))
    assert(run.trace.exists {
      case TraceEvent.Stepped(_, _, Choice.Fire(_, TarryAction.Pass(_)), _, _, _) => true
      case _                                                                      => false
    })
  }

  test("regression: a duplicate edge in the DOT file gives one channel, so 2|E| still holds") {
    val dot = "digraph g { 1 -> 2; 2 -> 1; 2 -> 3 }"
    val topology = Graphs.fixed(
      Topology.fromDot(dot).left.map(_.toList).flatMap { t =>
        Tarry.prepare(t, Some(NodeId(1))).left.map(_.toList).map(_.topology)
      }
    )
    assert(topology.edges.size == 4)
    assert(sends(runTarry(topology, 1L)).size == 4)
  }

  test("regression: a disconnected graph is refused before the run, not partly visited") {
    val dot = "digraph g { 1 -> 2; 3 -> 4 }"
    val loaded = Graphs.fixed(Topology.fromDot(dot))
    val errors = Tarry.prepare(loaded, Some(NodeId(1))).left.value.toList.map(_.render)
    assert(errors.exists(_.contains("Tarry needs a connected graph")))
  }

  test("Inspect gives labels and fields for the state, the message, the action and the output") {
    val state = TarryState(
      Role.NonInitiator,
      TarryPhase.Holding,
      Some(NodeId(3)),
      SortedSet(NodeId(4)),
      SortedSet(NodeId(3), NodeId(4)),
      3
    )
    assert(Inspect[TarryState].label(state) == "holds the token, 1 of 3 sent")
    assert(
      Inspect[TarryState].fields(state) == List(
        "role" -> "not an initiator",
        "phase" -> "holds the token",
        "parent" -> "node 3 (the parent is the last edge)",
        "sent to" -> "4",
        "received from" -> "3, 4"
      )
    )
    assert(Inspect[TarryMsg].label(TarryMsg.Token) == "Token")
    assert(Inspect[TarryAction].label(TarryAction.Pass(NodeId(2))) == "Pass to 2")
    assert(Inspect[TarryAction].label(TarryAction.Wake) == "Wake")
    assert(Inspect[TarryOutput].label(TarryOutput.TraversalComplete) == "the traversal is complete")
  }
}
