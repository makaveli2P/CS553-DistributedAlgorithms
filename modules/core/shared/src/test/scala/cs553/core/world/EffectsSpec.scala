package cs553.core.world

import cs553.core.ids.NodeId
import cs553.core.ids.RuleId
import cs553.core.protocol.NodeCtx
import cs553.core.protocol.ProtocolError
import cs553.core.protocol.Send
import cs553.core.protocol.Step
import cs553.core.topology.Edge
import cs553.core.trace.Choice
import cs553.core.trace.Envelope
import cs553.core.trace.EnvelopeId
import cs553.core.trace.Stamp
import cs553.core.trace.TraceEvent
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.immutable.SortedSet

final class EffectsSpec extends AnyFunSuite {

  private val ctx = NodeCtx(NodeId(0), SortedSet(NodeId(1)), SortedSet(NodeId(1), NodeId(2)))
  private val cause: Choice[String] = Choice.Fire(NodeId(0), "go")
  private val clock = Clock(3, 7)
  private val rule = RuleId("r")
  private val seqs: Edge => Int = {
    case Edge(_, to) if to == NodeId(1) => 4
    case _                              => 0
  }

  private def of(result: Either[ProtocolError, Step[String, String, String]]) = {
    Effects.of[String, String, String, String](5, ctx, cause, clock, seqs, result)
  }

  test("two sends on one channel get consecutive seq numbers from the next seq of the channel") {
    val step =
      Step("s", List(Send(NodeId(1), "a"), Send(NodeId(2), "b"), Send(NodeId(1), "c")), None, rule)
    val effects = of(Right(step))
    assert(
      effects.sent.map(_.env.id) == List(
        EnvelopeId(NodeId(0), NodeId(1), 4),
        EnvelopeId(NodeId(0), NodeId(2), 0),
        EnvelopeId(NodeId(0), NodeId(1), 5)
      )
    )
    assert(effects.sent.forall(_.lamport == 7))
    assert(effects.state.contains("s"))
  }

  test("the events are Stepped, then Sent in send order, then Decided, all with the step stamp") {
    val step = Step("s", List(Send(NodeId(2), "b")), Some("done"), rule)
    val at = Stamp(5, 3, 7)
    val id = EnvelopeId(NodeId(0), NodeId(2), 0)
    assert(
      of(Right(step)).events == List(
        TraceEvent.Stepped(at, NodeId(0), cause, rule, "s", List(id)),
        TraceEvent.Sent(at, Envelope(id, "b")),
        TraceEvent.Decided(at, NodeId(0), "done")
      )
    )
  }

  test("a send to a node that is not an out-neighbour gives Rejected, no sends and no state") {
    val step = Step("s", List(Send(NodeId(2), "b"), Send(NodeId(9), "x")), Some("done"), rule)
    val effects = of(Right(step))
    val error = ProtocolError.NotANeighbour(NodeId(9))
    assert(effects.events == List(TraceEvent.Rejected(Stamp(5, 3, 7), NodeId(0), cause, error)))
    assert(effects.sent.isEmpty)
    assert(effects.state.isEmpty)
  }

  test("a protocol error gives one Rejected event with the error") {
    val error = ProtocolError.IllegalAction("no")
    val effects = of(Left(error))
    assert(effects.events == List(TraceEvent.Rejected(Stamp(5, 3, 7), NodeId(0), cause, error)))
    assert(effects.sent.isEmpty && effects.state.isEmpty)
  }
}
