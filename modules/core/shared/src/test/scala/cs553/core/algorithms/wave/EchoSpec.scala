package cs553.core.algorithms.wave

import cs553.core.Fixtures
import cs553.core.ids.NodeId
import cs553.core.ids.RuleId
import cs553.core.protocol.ChannelOrder
import cs553.core.protocol.NodeCtx
import cs553.core.protocol.ProtocolError
import cs553.core.rng.Seed
import cs553.core.topology.NodeAttrs
import cs553.core.topology.Role
import cs553.core.topology.Topology
import cs553.core.trace.Inspect
import cs553.core.trace.Outcome
import cs553.core.trace.Policy
import cs553.core.trace.Run
import cs553.core.trace.RunSpec
import cs553.core.trace.StopRule
import cs553.core.trace.TraceEvent
import cs553.core.world.Graphs
import cs553.core.world.PropertyBudget
import cs553.core.world.Simulator
import org.scalacheck.Gen
import org.scalatest.EitherValues
import org.scalatest.funsuite.AnyFunSuite

import scala.annotation.tailrec
import scala.collection.immutable.SortedSet

final class EchoSpec extends AnyFunSuite with PropertyBudget with EitherValues {

  private type EchoRun = Run[EchoState, EchoMsg, EchoAction, EchoOutput]

  private val orders: Gen[ChannelOrder] = Gen.oneOf(ChannelOrder.Fifo, ChannelOrder.AnyOrder)

  private def ids(values: Int*): List[NodeId] = values.toList.map(NodeId(_))

  private def runEcho(
      topology: Topology,
      seed: Long,
      order: ChannelOrder = ChannelOrder.Fifo
  ): EchoRun = {
    val spec = RunSpec(
      Echo.id,
      topology,
      order,
      Policy.Seeded(Seed(seed)),
      StopRule.AtQuiescence,
      100000
    )
    Simulator.run(Echo.protocol, spec).value.run
  }

  private def finalStates(run: EchoRun): Map[NodeId, EchoState] = {
    val initial = run.trace.collectFirst { case TraceEvent.Started(_, _, states) => states }
    run.trace.foldLeft(initial.getOrElse(Map.empty)) {
      case (states, TraceEvent.Stepped(_, node, _, _, after, _)) => states.updated(node, after)
      case (states, _)                                           => states
    }
  }

  private def sentCount(run: EchoRun): Int = run.trace.count {
    case TraceEvent.Sent(_, _) => true
    case _                     => false
  }

  private def rules(run: EchoRun): List[RuleId] = run.trace.toList.collect {
    case TraceEvent.Stepped(_, _, _, rule, _, _) => rule
  }

  /** The path of parents from `node`, or `None` when the path has a cycle or a missing parent. */
  @tailrec
  private def rootOf(
      states: Map[NodeId, EchoState],
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
    Topology
      .undirected(
        (0 until size).map(NodeId(_)),
        (1 until size).map(i => NodeId(i - 1) -> NodeId(i)),
        Graphs.initiatorAttrs
      )
  )

  // -----------------------------------------------------------------------------------------------
  // Properties over random connected graphs, random initiators, seeds and both channel orders.

  test("property: each run ends quiescent, with no Rejected event") {
    forAll(Graphs.connectedAnyInitiator, Gen.long, orders) {
      (topology: Topology, seed: Long, order: ChannelOrder) =>
        val run = runEcho(topology, seed, order)
        assert(run.outcome.contains(Outcome.Quiescent))
        assert(run.trace.collect { case r @ TraceEvent.Rejected(_, _, _, _) => r }.isEmpty)
    }
  }

  test("property: the run sends exactly 2|E| messages") {
    forAll(Graphs.connectedAnyInitiator, Gen.long, orders) {
      (topology: Topology, seed: Long, order: ChannelOrder) =>
        // An undirected topology holds each edge in both directions, so edges.size == 2|E|.
        assert(sentCount(runEcho(topology, seed, order)) == topology.edges.size)
    }
  }

  test("property: the parent edges form a spanning tree rooted at the initiator") {
    forAll(Graphs.connectedAnyInitiator, Gen.long, orders) {
      (topology: Topology, seed: Long, order: ChannelOrder) =>
        val states = finalStates(runEcho(topology, seed, order))
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
        assert(states.values.count(_.parent.isEmpty) == 1)
    }
  }

  test("property: only the initiator decides, exactly once, as the last step of the run") {
    forAll(Graphs.connectedAnyInitiator, Gen.long, orders) {
      (topology: Topology, seed: Long, order: ChannelOrder) =>
        val run = runEcho(topology, seed, order)
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

  test("property: each node ends Echoed, the initiator ends Decided, and each node counts deg") {
    forAll(Graphs.connectedAnyInitiator, Gen.long, orders) {
      (topology: Topology, seed: Long, order: ChannelOrder) =>
        val states = finalStates(runEcho(topology, seed, order))
        topology.nodes.foreach { node =>
          val expected = topology.attrsOf(node).role match {
            case Role.Initiator    => EchoPhase.Decided
            case Role.NonInitiator => EchoPhase.Echoed
          }
          assert(states.get(node).map(_.phase).contains(expected))
          assert(states.get(node).map(_.received).contains(topology.outNeighbours(node).size))
        }
    }
  }

  // -----------------------------------------------------------------------------------------------
  // Examples on small fixtures.

  test("on the line 0-1-2 the rules fire in one fixed order") {
    val run = runEcho(line(3), 1L)
    assert(
      rules(run) == List(
        Echo.Rules.wake,
        Echo.Rules.firstToken,
        Echo.Rules.firstTokenLeaf,
        Echo.Rules.echoToParent,
        Echo.Rules.decide
      )
    )
    assert(sentCount(run) == 4)
    assert(finalStates(run).get(NodeId(2)).flatMap(_.parent).contains(NodeId(1)))
  }

  test("on a triangle 20 different schedules fire the same rules and send 6 messages") {
    val triangle = Graphs.fixed(
      Topology.undirected(
        ids(0, 1, 2),
        List(NodeId(0) -> NodeId(1), NodeId(1) -> NodeId(2), NodeId(0) -> NodeId(2)),
        Graphs.initiatorAttrs
      )
    )
    val schedules = (0L until 20L).map { seed =>
      val run = runEcho(triangle, seed)
      assert(sentCount(run) == 6)
      assert(
        rules(run).groupBy(identity).view.mapValues(_.size).toMap == Map(
          Echo.Rules.wake -> 1,
          Echo.Rules.firstToken -> 2,
          Echo.Rules.countToken -> 1,
          Echo.Rules.echoToParent -> 2,
          Echo.Rules.decide -> 1
        )
      )
      assert(run.decisions.toList == List(NodeId(0) -> EchoOutput.WaveComplete))
      run.trace.toList.collect { case TraceEvent.Stepped(_, _, cause, _, _, _) => cause }
    }
    // The rule counts are the same, but the seeds give different schedules.
    assert(schedules.toSet.size > 1)
  }

  test("an initiator with no neighbours decides at Wake") {
    val alone = Graphs.fixed(Topology.undirected(ids(0), Nil, Graphs.initiatorAttrs))
    val run = runEcho(alone, 0L)
    assert(rules(run) == List(Echo.Rules.wakeAlone))
    assert(run.decisions.toList == List(NodeId(0) -> EchoOutput.WaveComplete))
  }

  test("a token after the echo, a token before Wake and a second Wake are rejected") {
    val ctx = NodeCtx(NodeId(1), SortedSet(NodeId(0)), SortedSet(NodeId(0)))
    val echoed = EchoState(Role.NonInitiator, EchoPhase.Echoed, Some(NodeId(0)), 1)
    val idleInitiator = EchoState(Role.Initiator, EchoPhase.Idle, None, 0)
    val waiting = EchoState(Role.Initiator, EchoPhase.Waiting, None, 0)
    assert(
      Echo.protocol.onMessage(ctx, echoed, NodeId(0), EchoMsg.Token).left.value match {
        case ProtocolError.UnexpectedMessage(from, _) => from == NodeId(0)
        case _                                        => false
      }
    )
    assert(Echo.protocol.onMessage(ctx, idleInitiator, NodeId(0), EchoMsg.Token).isLeft)
    assert(Echo.protocol.onAction(ctx, waiting, EchoAction.Wake).isLeft)
    assert(Echo.protocol.actions(ctx, waiting).isEmpty)
  }

  test("init rejects a node whose in-neighbours and out-neighbours differ") {
    val ctx = NodeCtx(NodeId(0), SortedSet.empty, SortedSet(NodeId(1)))
    assert(Echo.protocol.init(ctx, NodeAttrs(Role.Initiator, None, Map.empty)).isLeft)
  }

  test("check rejects a directed graph, a disconnected graph and a wrong number of initiators") {
    val directed = Graphs.fixed(Topology.ring(3, Graphs.initiatorAttrs))
    val disconnected = Graphs.fixed(
      Topology.undirected(ids(0, 1, 2), List(NodeId(1) -> NodeId(2)), Graphs.initiatorAttrs)
    )
    val none = Graphs.fixed(Topology.undirected(ids(0, 1), List(NodeId(0) -> NodeId(1))))
    val two = Graphs.fixed(line(3).withInitiators(Set(NodeId(0), NodeId(2))))
    assert(Echo.check(directed).left.value.size == 1)
    assert(Echo.check(disconnected).left.value.size == 1)
    assert(Echo.check(none).left.value.size == 1)
    assert(Echo.check(two).left.value.size == 1)
    assert(Echo.check(line(4)) == Right(line(4)))
  }

  // -----------------------------------------------------------------------------------------------
  // The college fixture inputEcho.dot.

  private val inputEcho: Topology = Graphs.fixed(
    Fixtures.topologies
      .collectFirst { case ("inputEcho.dot", text) => text }
      .toRight("inputEcho.dot is missing")
      .flatMap(text => Topology.fromDot(text).left.map(_.toList.mkString(", ")))
  )

  test("inputEcho.dot: check rejects the file as it is, because the Init node 0 has no edges") {
    val errors = Echo.check(inputEcho.symmetric).left.value.toList
    assert(errors.size == 1)
    assert(errors.map(_.render).exists(_.contains("these nodes have no edges: 0")))
  }

  test("inputEcho.dot: without node 0 and with initiator 1, Echo sends 14 messages and decides") {
    val topology = Graphs.fixed(
      inputEcho.symmetric.without(Set(NodeId(0))).flatMap(_.withInitiators(Set(NodeId(1))))
    )
    assert(Echo.check(topology).isRight)
    val run = runEcho(topology, 7L)
    assert(topology.nodes.toList == ids(1, 2, 3, 4, 5))
    assert(sentCount(run) == 14)
    assert(run.outcome.contains(Outcome.Quiescent))
    assert(run.decisions.toList == List(NodeId(1) -> EchoOutput.WaveComplete))
    val states = finalStates(run)
    assert(ids(2, 3, 4, 5).forall(n => rootOf(states, n, Set.empty).contains(NodeId(1))))
  }

  test("Inspect gives labels and fields for the state, the message, the action and the output") {
    val state = EchoState(Role.NonInitiator, EchoPhase.Waiting, Some(NodeId(3)), 2)
    assert(Inspect[EchoState].label(state) == "waiting, received 2")
    assert(
      Inspect[EchoState].fields(state) ==
        List("role" -> "NonInitiator", "phase" -> "Waiting", "parent" -> "3", "received" -> "2")
    )
    assert(Inspect[EchoMsg].label(EchoMsg.Token) == "Token")
    assert(Inspect[EchoAction].label(EchoAction.Wake) == "Wake")
    assert(Inspect[EchoOutput].label(EchoOutput.WaveComplete) == "WaveComplete")
  }
}
