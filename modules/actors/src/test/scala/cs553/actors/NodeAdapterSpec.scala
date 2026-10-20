package cs553.actors

import akka.actor.testkit.typed.scaladsl.BehaviorTestKit
import akka.actor.testkit.typed.scaladsl.TestInbox
import cs553.core.algorithms.election.CrAction
import cs553.core.algorithms.wave.EchoMsg
import cs553.core.ids.NodeId
import cs553.core.protocol.ProtocolError
import cs553.core.registry.Algorithm
import cs553.core.registry.Registry
import cs553.core.rng.Seed
import cs553.core.trace.Choice
import cs553.core.trace.Envelope
import cs553.core.trace.EnvelopeId
import cs553.core.trace.TraceEvent
import cs553.core.world.Buffered
import cs553.core.world.World
import org.scalatest.Assertion
import org.scalatest.EitherValues
import org.scalatest.OptionValues
import org.scalatest.funsuite.AnyFunSuite

/** One generic suite for `NodeAdapter` over each algorithm of the registry. The Simulator trace is
  * the oracle: one step of the adapter must give the `Put`s and the one `Handled` of that step.
  */
final class NodeAdapterSpec extends AnyFunSuite with EitherValues with OptionValues {

  /** A node adapter in a `BehaviorTestKit`, with one inbox for each outgoing channel. */
  private final case class Rig[S, M, A, O](
      kit: BehaviorTestKit[NodeAdapter.Command[M, A]],
      channels: Map[NodeId, TestInbox[Channel.In[M]]],
      reports: TestInbox[NodeAdapter.Report[S, M, A, O]]
  ) {

    /** All `Put`s, by receiver, in arrival order. */
    def puts: Map[NodeId, List[Channel.In[M]]] = {
      channels.map { case (to, inbox) => to -> inbox.receiveAll().toList }.filter(_._2.nonEmpty)
    }
  }

  /** A wired adapter for `node` in its initial state. The `Ready` report is checked and removed. */
  private def rig[S, M, A, O](a: Algorithm[S, M, A, O], world: World[S, M], node: NodeId) = {
    val ctx = world.ctxs.get(node).value
    val state = world.states.get(node).value
    val reports = TestInbox[NodeAdapter.Report[S, M, A, O]]()
    val channels = ctx.out.toList.map(to => to -> TestInbox[Channel.In[M]]()).toMap
    val kit = BehaviorTestKit(NodeAdapter(a.protocol, ctx, state, reports.ref))
    kit.run(NodeAdapter.Wire(channels.map { case (to, inbox) => to -> inbox.ref }))
    assert(
      reports.receiveAll() == List(NodeAdapter.Report.Ready(node, a.protocol.actions(ctx, state)))
    )
    Rig(kit, channels, reports)
  }

  /** The events of `step` in `trace`, without the observer events. */
  private def eventsOf[S, M, A, O](
      trace: Vector[TraceEvent[S, M, A, O]],
      step: Int
  ): List[TraceEvent[S, M, A, O]] = {
    trace.toList.filter {
      case TraceEvent.Stepped(at, _, _, _, _, _) => at.step == step
      case TraceEvent.Sent(at, _)                => at.step == step
      case TraceEvent.Decided(at, _, _)          => at.step == step
      case TraceEvent.Rejected(at, _, _, _)      => at.step == step
      case TraceEvent.Started(_, _, _) | TraceEvent.Dropped(_, _) | TraceEvent.Finished(_, _) =>
        false
    }
  }

  /** Checks that `rig` gave exactly the `Put`s and the one `Handled` of `step` in the trace. */
  private def assertStep[S, M, A, O](
      a: Algorithm[S, M, A, O],
      rig: Rig[S, M, A, O],
      trace: Vector[TraceEvent[S, M, A, O]],
      after: World[S, M],
      node: NodeId,
      step: Int
  ): Assertion = {
    val events = eventsOf(trace, step)
    val sent = events.collect { case TraceEvent.Sent(at, env) => Buffered(env, at.lamport) }
    val expectedPuts = sent.groupBy(_.env.id.to).map { case (to, items) =>
      to -> items.map(i => Channel.Put(i))
    }
    assert(rig.puts == expectedPuts)
    val ctx = after.ctxs.get(node).value
    val actions = a.protocol.actions(ctx, after.states.get(node).value)
    val handled = NodeAdapter.Report.Handled(node, events, sent.map(_.env.id), actions)
    assert(rig.reports.receiveAll() == List(handled))
    assert(rig.kit.isAlive)
  }

  private def generic[S, M, A, O](a: Algorithm[S, M, A, O]): Unit = {
    a.presets.foreach { preset =>
      val simulation = a.simulate(a.spec(preset, Seed(1))).value
      val trace = simulation.run.trace
      val first = simulation.worlds.headOption.value
      val steps = trace.toList.collect { case TraceEvent.Stepped(at, node, cause, _, _, _) =>
        (at.step, node, cause)
      }

      test(s"${a.name} on ${preset.name}: one Act gives the Puts and one Handled of the trace") {
        val (step, node, cause) = steps.collectFirst { case s @ (_, _, Choice.Fire(_, _)) =>
          s
        }.value
        val r = rig(a, first, node)
        cause match {
          case Choice.Fire(_, action)             => r.kit.run(NodeAdapter.Act[M, A](action, step))
          case Choice.Deliver(_) | Choice.Drop(_) => fail(s"step $step is not a Fire")
        }
        assertStep(a, r, trace, simulation.worlds.lift(step).value, node, step)
      }

      test(
        s"${a.name} on ${preset.name}: one Deliver gives the Puts and one Handled of the trace"
      ) {
        // The first delivery to a node that did not step before, so its state is the initial one.
        val fresh = steps.zipWithIndex.collectFirst {
          case ((step, node, Choice.Deliver(id)), i) if !steps.take(i).exists(_._2 == node) =>
            (step, node, id)
        }
        val (step, node, id) = fresh.value
        val item = trace.collectFirst {
          case TraceEvent.Sent(at, env) if env.id == id => Buffered(env, at.lamport)
        }.value
        val r = rig(a, first, node)
        r.kit.run(NodeAdapter.Deliver(item, step))
        assertStep(a, r, trace, simulation.worlds.lift(step).value, node, step)
      }
    }
  }

  generic(Registry.echo)
  generic(Registry.changRoberts)

  test("a protocol error gives one Rejected report and no Put, and the actor stays alive") {
    val a = Registry.echo
    val world =
      World.init(a.protocol, a.presets.headOption.value.topology, a.protocol.order.default)
    val initiator = world.value.topology.initiators.headOption.value
    val r = rig(a, world.value, initiator)
    val from = world.value.ctxs.get(initiator).value.in.headOption.value
    val id = EnvelopeId(from, initiator, 0)
    r.kit.run(NodeAdapter.Deliver(Buffered(Envelope(id, EchoMsg.Token), 1), 1))
    assert(r.puts.isEmpty)
    val reports = r.reports.receiveAll()
    assert(reports.size == 1)
    reports.headOption.value match {
      case NodeAdapter.Report.Handled(node, events, sent, _) =>
        assert(node == initiator)
        assert(sent.isEmpty)
        events match {
          case List(TraceEvent.Rejected(at, n, Choice.Deliver(`id`), error)) =>
            assert(n == initiator)
            assert(at.step == 1)
            assert(
              error == ProtocolError.UnexpectedMessage(
                from,
                "the initiator got a token before Wake"
              )
            )
          case other => fail(s"expected one Rejected event, got $other")
        }
      case other => fail(s"expected Handled, got $other")
    }
    assert(r.kit.isAlive)
  }

  test("an action that the state does not allow gives Rejected with IllegalAction") {
    val a = Registry.changRoberts
    val world =
      World.init(a.protocol, a.presets.headOption.value.topology, a.protocol.order.default)
    val passive = world.value.states.keySet.find(n => !world.value.topology.initiators.contains(n))
    val node = passive.value
    val r = rig(a, world.value, node)
    r.kit.run(NodeAdapter.Act[cs553.core.algorithms.election.CrMsg, CrAction](CrAction.Wake, 1))
    assert(r.puts.isEmpty)
    r.reports.receiveAll().toList match {
      case List(NodeAdapter.Report.Handled(_, List(TraceEvent.Rejected(_, n, _, error)), Nil, _)) =>
        assert(n == node)
        assert(error match {
          case ProtocolError.IllegalAction(_) => true
          case ProtocolError.UnexpectedMessage(_, _) | ProtocolError.NotANeighbour(_) |
              ProtocolError.InvariantBroken(_) =>
            false
        })
      case other => fail(s"expected one Handled with one Rejected, got $other")
    }
    assert(r.kit.isAlive)
  }
}
