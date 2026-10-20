package cs553.actors

import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import cs553.core.protocol.ChannelOrder
import cs553.core.trace.EnvelopeId
import cs553.core.world.Buffered

/** One directed channel `i -> j` of the mediated engine, as an actor.
  *
  * The sender node puts envelopes into the channel with `!`. The channel keeps them in arrival
  * order and tells the Controller `Enqueued` for each one. The channel gives an envelope to the
  * receiver only when the Controller sends `Release`. In `Fifo` order, only the oldest envelope is
  * releasable. In `AnyOrder`, each buffered envelope is releasable.
  */
object Channel {

  /** All messages of a channel. */
  sealed trait Command[M]

  /** The messages that the sender node can send. A node holds only `ActorRef[In[M]]`. */
  sealed trait In[M] extends Command[M]

  /** Buffer this envelope. `item` holds the Lamport time of the send. */
  final case class Put[M](item: Buffered[M]) extends In[M]

  /** The messages that only the Controller sends. They are internal to the engine (coding rule 23).
    */
  private[actors] sealed trait Control[M] extends Command[M]

  /** Give the envelope `id` to the receiver for the global step `step`. */
  private[actors] final case class Release[M](id: EnvelopeId, step: Int) extends Control[M]

  /** Remove the envelope `id`. The receiver never gets it. */
  private[actors] final case class Drop[M](id: EnvelopeId) extends Control[M]

  /** What a channel tells the Controller. */
  enum Report {

    /** The channel buffered the envelope `id`. */
    case Enqueued(id: EnvelopeId)

    /** The channel did not do a `Release` or a `Drop` of `id`, for this reason. */
    case Refused(id: EnvelopeId, reason: Refusal)
  }

  /** Why a channel refused a `Release` or a `Drop`. */
  enum Refusal {

    /** The channel does not hold the envelope. */
    case NotBuffered

    /** The order is `Fifo`, and an older envelope is at the head of the channel. */
    case NotHead(head: EnvelopeId)
  }

  /** An empty channel that gives envelopes to `receiver` and reports to `reportTo`. */
  def apply[M](
      order: ChannelOrder,
      receiver: ActorRef[NodeAdapter.Deliver[M]],
      reportTo: ActorRef[Report]
  ): Behavior[Command[M]] = {
    running(order, receiver, reportTo, Vector.empty)
  }

  private def running[M](
      order: ChannelOrder,
      receiver: ActorRef[NodeAdapter.Deliver[M]],
      reportTo: ActorRef[Report],
      queue: Vector[Buffered[M]]
  ): Behavior[Command[M]] = {
    Behaviors.receiveMessage {
      case Put(item) =>
        reportTo ! Report.Enqueued(item.env.id)
        running(order, receiver, reportTo, queue :+ item)
      case Release(id, step) =>
        take(order, queue, id) match {
          case Left(reason) =>
            reportTo ! Report.Refused(id, reason)
            Behaviors.same
          case Right((item, rest)) =>
            receiver ! NodeAdapter.Deliver(item, step)
            running(order, receiver, reportTo, rest)
        }
      case Drop(id) =>
        take(ChannelOrder.AnyOrder, queue, id) match {
          case Left(reason) =>
            reportTo ! Report.Refused(id, reason)
            Behaviors.same
          case Right((_, rest)) => running(order, receiver, reportTo, rest)
        }
    }
  }

  /** The envelope `id` and the queue without it, or the reason that `order` does not allow it. */
  private def take[M](
      order: ChannelOrder,
      queue: Vector[Buffered[M]],
      id: EnvelopeId
  ): Either[Refusal, (Buffered[M], Vector[Buffered[M]])] = {
    val index = queue.indexWhere(_.env.id == id)
    val found = queue.lift(index).toRight(Refusal.NotBuffered)
    found.flatMap { item =>
      (order, queue.headOption.map(_.env.id)) match {
        case (ChannelOrder.Fifo, Some(head)) if head != id => Left(Refusal.NotHead(head))
        case (ChannelOrder.Fifo, Some(_) | None) | (ChannelOrder.AnyOrder, _) =>
          Right((item, queue.patch(index, Nil, 1)))
      }
    }
  }
}
