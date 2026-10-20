package cs553.core.world

import cats.data.NonEmptyList
import cs553.core.ids.NodeId
import cs553.core.protocol.ChannelOrder
import cs553.core.protocol.NodeCtx
import cs553.core.protocol.Protocol
import cs553.core.protocol.ProtocolError
import cs553.core.protocol.Step
import cs553.core.topology.Edge
import cs553.core.topology.Topology
import cs553.core.topology.TopologyError
import cs553.core.trace.Choice
import cs553.core.trace.Envelope
import cs553.core.trace.EnvelopeId
import cs553.core.trace.Stamp
import cs553.core.trace.TraceEvent

import scala.collection.immutable.SortedMap

/** The event counter and the Lamport clock of one node. */
final case class Clock(local: Int, lamport: Int) {

  /** The clock after one local event that receives a message with the Lamport time `received`. */
  def receive(received: Int): Clock = Clock(local + 1, math.max(lamport, received) + 1)

  /** The clock after one local event with no received message. */
  def tick: Clock = Clock(local + 1, lamport + 1)
}

object Clock {
  val zero: Clock = Clock(0, 0)
}

/** One envelope in a channel, with the Lamport time of its send. */
final case class Buffered[+M](env: Envelope[M], lamport: Int)

/** Why `World.next` refused a choice. The world does not change. */
enum ChoiceError {

  /** No channel holds the envelope. */
  case NotBuffered(id: EnvelopeId)

  /** The channel holds the envelope, but the channel order does not let it go first. */
  case NotDeliverable(id: EnvelopeId, head: EnvelopeId)

  /** The node is not in the topology. */
  case UnknownNode(node: NodeId)

  /** The protocol does not offer this action at the node now. */
  case ActionNotOffered(node: NodeId)
}

/** The global state of a run: the node states, the channel contents and the step number.
  *
  * Each directed edge of the topology is one channel. `nextSeq` holds the next sequence number of
  * each channel.
  */
final case class World[S, M](
    topology: Topology,
    order: ChannelOrder,
    ctxs: SortedMap[NodeId, NodeCtx],
    states: SortedMap[NodeId, S],
    channels: SortedMap[Edge, Vector[Buffered[M]]],
    nextSeq: Map[Edge, Int],
    clocks: Map[NodeId, Clock],
    step: Int
) {

  /** All buffered envelopes, by channel and then by age. */
  def buffered: Vector[Envelope[M]] = channels.values.toVector.flatMap(_.map(_.env))

  def channel(edge: Edge): Vector[Buffered[M]] = channels.getOrElse(edge, Vector.empty)

  def clockOf(node: NodeId): Clock = clocks.getOrElse(node, Clock.zero)
}

object World {

  /** The world before the first step. `Protocol.check` checks the topology, and then each node gets
    * its state from `Protocol.init`.
    */
  def init[S, M, A, O](
      p: Protocol[S, M, A, O],
      topology: Topology,
      order: ChannelOrder
  ): Either[NonEmptyList[TopologyError], World[S, M]] = {
    p.check(topology).flatMap(checked => initNodes(p, checked, order))
  }

  private def initNodes[S, M, A, O](
      p: Protocol[S, M, A, O],
      topology: Topology,
      order: ChannelOrder
  ): Either[NonEmptyList[TopologyError], World[S, M]] = {
    val ctxs = SortedMap.from(topology.nodes.toList.map(n => n -> NodeCtx.of(topology, n)))
    val results = ctxs.toList.map { case (n, ctx) => p.init(ctx, topology.attrsOf(n)).map(n -> _) }
    NonEmptyList.fromList(results.collect { case Left(e) => e }) match {
      case Some(errors) => Left(errors)
      case None         =>
        Right(
          World(
            topology,
            order,
            ctxs,
            SortedMap.from(results.collect { case Right(state) => state }),
            SortedMap.from(topology.edges.toList.map(e => e -> Vector.empty[Buffered[M]])),
            Map.empty,
            Map.empty,
            0
          )
        )
    }
  }

  /** The legal choices in a fixed canonical order: deliveries by `EnvelopeId`, then actions by node
    * and by the order of `Protocol.actions`. In `Fifo` order, only the head of each channel is
    * deliverable. In `AnyOrder`, each buffered envelope is deliverable. `Drop` is never in the
    * list. A `Script` can still give it.
    */
  def enabled[S, M, A, O](p: Protocol[S, M, A, O], w: World[S, M]): List[Choice[A]] = {
    val actions = w.states.toList.map { case (node, state) =>
      node -> w.ctxs.get(node).toList.flatMap(ctx => p.actions(ctx, state))
    }
    enabledOf(w.order, w.channels.values.toList.map(_.map(_.env.id)), actions)
  }

  /** The rule of `enabled` on plain data, so that an engine with no `World` gives the same list.
    *
    * `queues` holds the envelope ids of each channel, oldest first. `actions` holds the actions of
    * each node in the order of `Protocol.actions`. The mediated Akka Controller calls this function
    * with the data from the `Enqueued` and `Handled` reports.
    */
  def enabledOf[A](
      order: ChannelOrder,
      queues: List[Vector[EnvelopeId]],
      actions: List[(NodeId, List[A])]
  ): List[Choice[A]] = {
    val deliverable = order match {
      case ChannelOrder.Fifo     => queues.flatMap(_.headOption)
      case ChannelOrder.AnyOrder => queues.flatten
    }
    val deliveries = deliverable.sorted.map(id => Choice.Deliver(id))
    val fires = actions.sortBy(_._1).flatMap { case (node, list) =>
      list.map(a => Choice.Fire(node, a))
    }
    deliveries ++ fires
  }

  /** Applies one choice. Returns the next world and the trace events of the step. */
  def next[S, M, A, O](
      p: Protocol[S, M, A, O],
      w: World[S, M],
      choice: Choice[A]
  ): Either[ChoiceError, (World[S, M], List[TraceEvent[S, M, A, O]])] = {
    choice match {
      case Choice.Deliver(id) =>
        for {
          item <- find(w, id)
          _ <- checkOrder(w, id)
          node <- nodeOf(w, id.to)
          (ctx, state) = node
        } yield {
          val rest = remove(w, id)
          val clock = w.clockOf(id.to).receive(item.lamport)
          handle(rest, ctx, choice, clock, p.onMessage(ctx, state, id.from, item.env.msg))
        }
      case Choice.Fire(target, action) =>
        for {
          node <- nodeOf(w, target)
          (ctx, state) = node
          _ <- {
            if p.actions(ctx, state).contains(action) then { Right(()) }
            else { Left(ChoiceError.ActionNotOffered(target)) }
          }
        } yield {
          handle(w, ctx, choice, w.clockOf(target).tick, p.onAction(ctx, state, action))
        }
      case Choice.Drop(id) =>
        find(w, id).map { _ =>
          val step = w.step + 1
          (remove(w, id).copy(step = step), List(droppedAt(step, id)))
        }
    }
  }

  /** The `Dropped` event of the envelope `id` in the global step `step`. The mediated Akka
    * Controller calls this function too, so both engines write the same event.
    */
  def droppedAt(step: Int, id: EnvelopeId): TraceEvent[Nothing, Nothing, Nothing, Nothing] = {
    TraceEvent.Dropped(Stamp(step, 0, 0), id)
  }

  private def find[S, M](w: World[S, M], id: EnvelopeId): Either[ChoiceError, Buffered[M]] = {
    w.channel(id.channel).find(_.env.id == id).toRight(ChoiceError.NotBuffered(id))
  }

  private def checkOrder[S, M](w: World[S, M], id: EnvelopeId): Either[ChoiceError, Unit] = {
    w.order match {
      case ChannelOrder.AnyOrder => Right(())
      case ChannelOrder.Fifo     =>
        w.channel(id.channel).headOption.map(_.env.id) match {
          case Some(head) if head != id => Left(ChoiceError.NotDeliverable(id, head))
          case Some(_)                  => Right(())
          case None                     => Left(ChoiceError.NotBuffered(id))
        }
    }
  }

  private def nodeOf[S, M](w: World[S, M], node: NodeId): Either[ChoiceError, (NodeCtx, S)] = {
    w.ctxs.get(node).zip(w.states.get(node)).toRight(ChoiceError.UnknownNode(node))
  }

  private def remove[S, M](w: World[S, M], id: EnvelopeId): World[S, M] = {
    w.copy(channels =
      w.channels.updated(id.channel, w.channel(id.channel).filterNot(_.env.id == id))
    )
  }

  /** Records the result of one handler with `Effects.of`: the new state, the buffered sends and the
    * events.
    */
  private def handle[S, M, A, O](
      w: World[S, M],
      ctx: NodeCtx,
      cause: Choice[A],
      clock: Clock,
      result: Either[ProtocolError, Step[S, M, O]]
  ): (World[S, M], List[TraceEvent[S, M, A, O]]) = {
    val step = w.step + 1
    val effects = Effects.of(step, ctx, cause, clock, e => w.nextSeq.getOrElse(e, 0), result)
    val ticked = w.copy(step = step, clocks = w.clocks.updated(ctx.self, clock))
    val sent = effects.sent.foldLeft(ticked) { (world, item) =>
      val edge = item.env.id.channel
      world.copy(
        channels = world.channels.updated(edge, world.channel(edge) :+ item),
        nextSeq = world.nextSeq.updated(edge, item.env.id.seq + 1)
      )
    }
    val after = effects.state.fold(sent)(s => sent.copy(states = sent.states.updated(ctx.self, s)))
    (after, effects.events)
  }
}
