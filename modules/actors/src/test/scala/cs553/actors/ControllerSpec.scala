package cs553.actors

import akka.actor.testkit.typed.scaladsl.BehaviorTestKit
import akka.actor.testkit.typed.scaladsl.TestInbox
import cs553.core.algorithms.election.CrAction
import cs553.core.algorithms.election.CrMsg
import cs553.core.algorithms.election.CrOutput
import cs553.core.algorithms.election.CrState
import cs553.core.ids.NodeId
import cs553.core.protocol.ChannelOrder
import cs553.core.registry.Registry
import cs553.core.rng.Seed
import cs553.core.topology.Edge
import cs553.core.trace.Choice
import cs553.core.trace.Engine
import cs553.core.trace.EnvelopeId
import cs553.core.trace.Outcome
import cs553.core.trace.Policy
import cs553.core.trace.RunSpec
import cs553.core.trace.TraceEvent
import cs553.core.world.Simulator
import cs553.core.world.World
import org.scalatest.OptionValues
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.immutable.SortedMap

/** The step barrier and the checks of the Controller, with inboxes in place of the node and the
  * channel actors. The tests send the reports that a faulty node or channel could send.
  */
final class ControllerSpec extends AnyFunSuite with OptionValues {

  private type Result = RunGuardian.Result[CrState, CrMsg, CrAction, CrOutput]

  private val n1 = NodeId(1)
  private val n2 = NodeId(2)
  private val edge = Edge(n1, n2)
  private val id0 = EnvelopeId(n1, n2, 0)
  private val id1 = EnvelopeId(n1, n2, 1)
  private val wake = CrAction.Wake

  private def specOf(script: List[Choice[CrAction]]): RunSpec[CrAction] = {
    val a = Registry.changRoberts
    a.spec(a.presets.headOption.value, Seed(1)).copy(policy = Policy.Script(script))
  }

  private final case class Rig(
      kit: BehaviorTestKit[Controller.Command[CrState, CrMsg, CrAction, CrOutput]],
      replies: TestInbox[Result],
      nodes: Map[NodeId, TestInbox[NodeAdapter.Command[CrMsg, CrAction]]],
      channel: TestInbox[Channel.Command[CrMsg]]
  ) {

    /** The one reply of the Controller, and a check that the Controller stopped. */
    def reply: Result = {
      val all = replies.receiveAll()
      assert(all.size == 1, s"replies: $all")
      assert(!kit.isAlive, "the Controller must stop after its reply")
      all.headOption.value
    }

    def failedWith: RunGuardian.EngineError[CrAction] = reply match {
      case RunGuardian.Failed(error) => error
      case RunGuardian.Finished(run) => fail(s"expected Failed, got Finished: $run")
    }
  }

  /** A Controller for the nodes 1 and 2 and the edge `1 -> 2`, after `Wired` and both `Ready`. */
  private def wired(script: List[Choice[CrAction]], actions: Map[NodeId, List[CrAction]]): Rig = {
    val spec = specOf(script)
    val replies = TestInbox[Result]()
    val started: TraceEvent[CrState, CrMsg, CrAction, CrOutput] =
      TraceEvent.Started(spec, Engine.AkkaMediated, SortedMap.empty[NodeId, CrState])
    val kit = BehaviorTestKit(
      Controller[CrState, CrMsg, CrAction, CrOutput](
        spec,
        ChannelOrder.Fifo,
        Set(n1, n2),
        Set(edge),
        started,
        replies.ref
      )
    )
    val nodes = List(n1, n2).map(n => n -> TestInbox[NodeAdapter.Command[CrMsg, CrAction]]()).toMap
    val channel = TestInbox[Channel.Command[CrMsg]]()
    kit.run(
      Controller.Wired(nodes.map { case (n, inbox) => n -> inbox.ref }, Map(edge -> channel.ref))
    )
    List(n1, n2).foreach(n => kit.run(NodeAdapter.Report.Ready(n, actions.getOrElse(n, Nil))))
    Rig(kit, replies, nodes, channel)
  }

  /** Step 1 is `Fire(1, Wake)`. Only node 1 offers an action. */
  private def firstStep(rest: List[Choice[CrAction]]): Rig = {
    val rig = wired(Choice.Fire(n1, wake) :: rest, Map(n1 -> List(wake)))
    assert(rig.nodes.get(n1).value.receiveAll() == List(NodeAdapter.Act[CrMsg, CrAction](wake, 1)))
    rig
  }

  private def unexpected(rig: Rig): String = rig.failedWith match {
    case RunGuardian.EngineError.UnexpectedReport(detail) => detail
    case other => fail(s"expected UnexpectedReport, got $other")
  }

  test("a Refused report during a step stops the run with Failed(Refused)") {
    val rig = firstStep(List(Choice.Deliver(id0)))
    rig.kit.run(NodeAdapter.Report.Handled(n1, Nil, List(id0), Nil))
    rig.kit.run(Channel.Report.Enqueued(id0))
    assert(rig.channel.receiveAll() == List(Channel.Release[CrMsg](id0, 2)))
    rig.kit.run(Channel.Report.Refused(id0, Channel.Refusal.NotHead(id1)))
    assert(rig.failedWith == RunGuardian.EngineError.Refused(id0, Channel.Refusal.NotHead(id1)))
  }

  test("a second Handled in one step stops the run with UnexpectedReport") {
    val rig = firstStep(Nil)
    // The step is not complete: the Enqueued of id0 is missing.
    rig.kit.run(NodeAdapter.Report.Handled(n1, Nil, List(id0), Nil))
    rig.kit.run(NodeAdapter.Report.Handled(n1, Nil, List(id0), Nil))
    assert(unexpected(rig).contains("a second Handled"))
  }

  test("a Handled from a node that the step does not target stops the run") {
    val rig = firstStep(Nil)
    rig.kit.run(NodeAdapter.Report.Handled(n2, Nil, Nil, Nil))
    assert(unexpected(rig) == "Handled from node 2, not from node 1")
  }

  test("an Enqueued of an id that the node did not send in the step stops the run") {
    val rig = firstStep(Nil)
    rig.kit.run(Channel.Report.Enqueued(id1))
    rig.kit.run(NodeAdapter.Report.Handled(n1, Nil, Nil, Nil))
    assert(unexpected(rig).contains("Enqueued of ids not sent"))
  }

  test("a Ready during a step stops the run") {
    val rig = firstStep(Nil)
    rig.kit.run(NodeAdapter.Report.Ready(n2, Nil))
    assert(unexpected(rig) == "Ready from node 2")
  }

  test("a second Wired stops the run") {
    val rig = firstStep(Nil)
    rig.kit.run(Controller.Wired[CrMsg, CrAction](Map.empty, Map.empty))
    assert(unexpected(rig) == "a second Wired")
  }

  test("an Unwired report stops the run with Failed(Unwired)") {
    val rig = firstStep(Nil)
    rig.kit.run(NodeAdapter.Report.Unwired(n1, List(id0)))
    assert(
      rig.failedWith == RunGuardian.EngineError.Unwired(s"node 1 has no channel for $id0")
    )
  }

  test("a Script Fire of an action that the node does not offer gives Failed(BadChoice)") {
    val bad = Choice.Fire(n1, wake)
    // Node 2 offers Wake, so the run is not quiescent, but node 1 offers nothing.
    val rig = wired(List(bad), Map(n2 -> List(wake)))
    assert(rig.failedWith == RunGuardian.EngineError.BadChoice(1, bad))
    assert(!rig.nodes.get(n1).value.hasMessages)
  }

  test("a Script Drop of an envelope that is not buffered gives Failed(BadChoice)") {
    val bad = Choice.Drop[CrAction](id0)
    val rig = wired(List(bad), Map(n2 -> List(wake)))
    assert(rig.failedWith == RunGuardian.EngineError.BadChoice(1, bad))
    assert(!rig.channel.hasMessages)
  }

  test("a Script Drop sends Drop to the channel and records the Dropped event of the step") {
    val rig = firstStep(List(Choice.Drop(id0)))
    rig.kit.run(NodeAdapter.Report.Handled(n1, Nil, List(id0), Nil))
    rig.kit.run(Channel.Report.Enqueued(id0))
    assert(rig.channel.receiveAll() == List(Channel.Drop[CrMsg](id0)))
    // After the Drop, no node offers an action and no channel holds an envelope.
    rig.reply match {
      case RunGuardian.Finished(run) =>
        assert(
          run.trace.drop(1) == Vector(
            World.droppedAt(2, id0),
            Simulator.finishedAt(2, Outcome.Quiescent)
          )
        )
      case RunGuardian.Failed(error) => fail(s"the run failed: $error")
    }
  }
}
