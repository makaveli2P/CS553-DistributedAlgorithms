package cs553.core.world

import cs553.core.ids.NodeId
import cs553.core.ids.RuleId
import cs553.core.protocol.ChannelOrder
import cs553.core.protocol.ProtocolError
import cs553.core.topology.Topology
import cs553.core.trace.Choice
import cs553.core.trace.Envelope
import cs553.core.trace.EnvelopeId
import cs553.core.trace.Stamp
import cs553.core.trace.TraceEvent
import cs553.core.world.Toys.ChatAction
import cs553.core.world.Toys.ChatMsg
import cs553.core.world.Toys.ChatState
import cs553.core.world.Toys.chatter
import org.scalatest.EitherValues
import org.scalatest.funsuite.AnyFunSuite

final class WorldSpec extends AnyFunSuite with EitherValues {

  private val n0 = NodeId(0)
  private val n1 = NodeId(1)
  private val id0 = EnvelopeId(n0, n1, 0)
  private val id1 = EnvelopeId(n0, n1, 1)

  /** The directed line 0 -> 1. Node 0 is the initiator, so it has budget for one Talk. */
  private val line: Topology =
    Graphs.fixed(Topology.directed(List(n0, n1), List(n0 -> n1), Graphs.initiatorAttrs))

  private def start(order: ChannelOrder): World[ChatState, ChatMsg] = {
    World.init(chatter, line, order).value
  }

  /** The world after node 0 talks: two envelopes wait on the channel 0 -> 1. */
  private def afterTalk(order: ChannelOrder): World[ChatState, ChatMsg] = {
    World.next(chatter, start(order), Choice.Fire(n0, ChatAction.Talk)).value._1
  }

  private def withExtras(w: World[ChatState, ChatMsg], extras: List[ChatAction]) = {
    w.copy(states = w.states.updated(n0, ChatState(0, Nil, extras)))
  }

  test("init gives each node its state and an empty channel for each edge") {
    val w = start(ChannelOrder.Fifo)
    assert(w.states.get(n0) == Some(ChatState(1, Nil, Nil)))
    assert(w.states.get(n1) == Some(ChatState(0, Nil, Nil)))
    assert(w.buffered.isEmpty)
    assert(w.step == 0)
  }

  test("enabled lists the actions when no envelope waits") {
    assert(
      World.enabled(chatter, start(ChannelOrder.Fifo)) == List(Choice.Fire(n0, ChatAction.Talk))
    )
  }

  test("a Fire step records Stepped and one Sent for each send, with fresh sequence numbers") {
    val (w, events) =
      World.next(chatter, start(ChannelOrder.Fifo), Choice.Fire(n0, ChatAction.Talk)).value
    val at = Stamp(1, 1, 1)
    assert(
      events == List(
        TraceEvent.Stepped(
          at,
          n0,
          Choice.Fire(n0, ChatAction.Talk),
          RuleId("talk"),
          ChatState(0, Nil, Nil),
          List(id0, id1)
        ),
        TraceEvent.Sent(at, Envelope(id0, ChatMsg.Hello(1))),
        TraceEvent.Sent(at, Envelope(id1, ChatMsg.Hello(2)))
      )
    )
    assert(w.step == 1)
    assert(w.buffered.map(_.id) == Vector(id0, id1))
  }

  test("enabled in Fifo order lists only the head of each channel") {
    assert(World.enabled(chatter, afterTalk(ChannelOrder.Fifo)) == List(Choice.Deliver(id0)))
  }

  test("enabled in AnyOrder lists each buffered envelope, by EnvelopeId") {
    assert(
      World.enabled(chatter, afterTalk(ChannelOrder.AnyOrder)) == List(
        Choice.Deliver(id0),
        Choice.Deliver(id1)
      )
    )
  }

  test("next refuses a delivery that is not the channel head in Fifo order") {
    assert(
      World.next(chatter, afterTalk(ChannelOrder.Fifo), Choice.Deliver(id1)).left.value ==
        ChoiceError.NotDeliverable(id1, id0)
    )
  }

  test("next accepts a delivery out of order in AnyOrder") {
    val (w, events) =
      World.next(chatter, afterTalk(ChannelOrder.AnyOrder), Choice.Deliver(id1)).value
    assert(w.states.get(n1).map(_.heard) == Some(List(2)))
    assert(w.buffered.map(_.id) == Vector(id0))
    assert(events.lastOption == Some(TraceEvent.Decided(Stamp(2, 1, 2), n1, 2)))
  }

  test("a delivery advances the Lamport clock past the send time") {
    val (_, events) = World.next(chatter, afterTalk(ChannelOrder.Fifo), Choice.Deliver(id0)).value
    assert(events.headOption.collect { case TraceEvent.Stepped(at, _, _, _, _, _) =>
      at
    } == Some(Stamp(2, 1, 2)))
  }

  test("next refuses an envelope that no channel holds") {
    val missing = EnvelopeId(n0, n1, 7)
    assert(
      World
        .next(chatter, afterTalk(ChannelOrder.Fifo), Choice.Deliver(missing))
        .left
        .value == ChoiceError.NotBuffered(missing)
    )
  }

  test("next refuses an action that the protocol does not offer") {
    assert(
      World.next(chatter, start(ChannelOrder.Fifo), Choice.Fire(n1, ChatAction.Talk)).left.value ==
        ChoiceError.ActionNotOffered(n1)
    )
  }

  test("next refuses an unknown node") {
    assert(
      World
        .next(chatter, start(ChannelOrder.Fifo), Choice.Fire(NodeId(9), ChatAction.Talk))
        .left
        .value ==
        ChoiceError.UnknownNode(NodeId(9))
    )
  }

  test("Drop removes any buffered envelope, also in Fifo order, and records Dropped") {
    val (w, events) = World.next(chatter, afterTalk(ChannelOrder.Fifo), Choice.Drop(id1)).value
    assert(events == List(TraceEvent.Dropped(Stamp(2, 0, 0), id1)))
    assert(w.buffered.map(_.id) == Vector(id0))
    assert(w.step == 2)
  }

  test("a protocol error gives Rejected, keeps the state and consumes the envelope") {
    val poisoned = withExtras(start(ChannelOrder.Fifo), List(ChatAction.SendPoison(n1)))
    val (sent, _) = World.next(chatter, poisoned, Choice.Fire(n0, ChatAction.SendPoison(n1))).value
    val (w, events) = World.next(chatter, sent, Choice.Deliver(id0)).value
    assert(
      events == List(
        TraceEvent.Rejected(
          Stamp(2, 1, 2),
          n1,
          Choice.Deliver(id0),
          ProtocolError.UnexpectedMessage(n0, "poison")
        )
      )
    )
    assert(w.states.get(n1) == sent.states.get(n1))
    assert(w.buffered.isEmpty)
  }

  test("a send to a node that is not an out-neighbour gives Rejected and sends nothing") {
    val stray = withExtras(start(ChannelOrder.Fifo), List(ChatAction.Stray(NodeId(9))))
    val (w, events) = World.next(chatter, stray, Choice.Fire(n0, ChatAction.Stray(NodeId(9)))).value
    assert(
      events == List(
        TraceEvent.Rejected(
          Stamp(1, 1, 1),
          n0,
          Choice.Fire(n0, ChatAction.Stray(NodeId(9))),
          ProtocolError.NotANeighbour(NodeId(9))
        )
      )
    )
    assert(w.buffered.isEmpty)
    assert(w.states.get(n0) == stray.states.get(n0))
    assert(w.step == 1)
  }
}
