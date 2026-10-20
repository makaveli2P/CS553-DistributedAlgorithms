package cs553.actors

import akka.actor.testkit.typed.scaladsl.BehaviorTestKit
import akka.actor.testkit.typed.scaladsl.TestInbox
import cs553.core.ids.NodeId
import cs553.core.protocol.ChannelOrder
import cs553.core.trace.Envelope
import cs553.core.trace.EnvelopeId
import cs553.core.world.Buffered
import org.scalatest.funsuite.AnyFunSuite

final class ChannelSpec extends AnyFunSuite {

  private def item(seq: Int): Buffered[String] = {
    Buffered(Envelope(EnvelopeId(NodeId(3), NodeId(5), seq), s"m$seq"), seq + 1)
  }

  private final case class Fixture(
      kit: BehaviorTestKit[Channel.Command[String]],
      receiver: TestInbox[NodeAdapter.Deliver[String]],
      reports: TestInbox[Channel.Report]
  )

  /** A channel `3 -> 5` in `order` that holds the envelopes with seq 0, 1 and 2. */
  private def filled(order: ChannelOrder): Fixture = {
    val receiver = TestInbox[NodeAdapter.Deliver[String]]()
    val reports = TestInbox[Channel.Report]()
    val kit = BehaviorTestKit(Channel[String](order, receiver.ref, reports.ref))
    (0 to 2).foreach(seq => kit.run(Channel.Put(item(seq))))
    Fixture(kit, receiver, reports)
  }

  test("the channel reports one Enqueued for each Put, in arrival order") {
    val f = filled(ChannelOrder.Fifo)
    val expected = (0 to 2).map(seq => Channel.Report.Enqueued(item(seq).env.id))
    assert(f.reports.receiveAll() == expected)
    assert(!f.receiver.hasMessages)
  }

  test("in Fifo order, the channel refuses a Release of an envelope that is not the head") {
    val f = filled(ChannelOrder.Fifo)
    val _ = f.reports.receiveAll()
    f.kit.run(Channel.Release(item(1).env.id, 4))
    assert(
      f.reports.receiveAll() == List(
        Channel.Report.Refused(item(1).env.id, Channel.Refusal.NotHead(item(0).env.id))
      )
    )
    assert(!f.receiver.hasMessages)
    f.kit.run(Channel.Release(item(0).env.id, 4))
    f.kit.run(Channel.Release(item(1).env.id, 5))
    assert(
      f.receiver.receiveAll() == List(
        NodeAdapter.Deliver(item(0), 4),
        NodeAdapter.Deliver(item(1), 5)
      )
    )
    assert(!f.reports.hasMessages)
  }

  test("in AnyOrder, the channel releases each buffered envelope") {
    val f = filled(ChannelOrder.AnyOrder)
    val _ = f.reports.receiveAll()
    f.kit.run(Channel.Release(item(2).env.id, 1))
    f.kit.run(Channel.Release(item(0).env.id, 2))
    assert(
      f.receiver.receiveAll() == List(
        NodeAdapter.Deliver(item(2), 1),
        NodeAdapter.Deliver(item(0), 2)
      )
    )
    assert(!f.reports.hasMessages)
  }

  test("the channel refuses a Release of an envelope that it does not hold") {
    val f = filled(ChannelOrder.AnyOrder)
    val _ = f.reports.receiveAll()
    val unknown = EnvelopeId(NodeId(3), NodeId(5), 9)
    f.kit.run(Channel.Release(unknown, 1))
    assert(
      f.reports.receiveAll() == List(Channel.Report.Refused(unknown, Channel.Refusal.NotBuffered))
    )
    assert(!f.receiver.hasMessages)
  }

  test("Drop removes the envelope, so in Fifo order the next envelope becomes the head") {
    val f = filled(ChannelOrder.Fifo)
    val _ = f.reports.receiveAll()
    f.kit.run(Channel.Drop(item(0).env.id))
    f.kit.run(Channel.Release(item(0).env.id, 1))
    f.kit.run(Channel.Release(item(1).env.id, 2))
    assert(
      f.reports.receiveAll() == List(
        Channel.Report.Refused(item(0).env.id, Channel.Refusal.NotBuffered)
      )
    )
    assert(f.receiver.receiveAll() == List(NodeAdapter.Deliver(item(1), 2)))
  }

  test("in Fifo order, Drop removes an envelope that is not the head, and the order stays") {
    val f = filled(ChannelOrder.Fifo)
    val _ = f.reports.receiveAll()
    f.kit.run(Channel.Drop(item(1).env.id))
    f.kit.run(Channel.Release(item(0).env.id, 1))
    f.kit.run(Channel.Release(item(2).env.id, 2))
    assert(!f.reports.hasMessages)
    assert(
      f.receiver.receiveAll() == List(
        NodeAdapter.Deliver(item(0), 1),
        NodeAdapter.Deliver(item(2), 2)
      )
    )
  }
}
