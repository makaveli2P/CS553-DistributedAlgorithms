package cs553.core.protocol

import cs553.core.ids.AlgorithmId
import cs553.core.ids.NodeId
import cs553.core.ids.RuleId
import cs553.core.topology.NodeAttrs
import cs553.core.topology.Topology
import cs553.core.topology.TopologyError
import io.circe.Codec

import scala.collection.immutable.SortedSet

/** The delivery order of the channels in a run. */
enum ChannelOrder derives Codec.AsObject {

  /** Only the oldest envelope of each channel is deliverable. */
  case Fifo

  /** Each buffered envelope is deliverable. */
  case AnyOrder
}

/** The channel order that a protocol accepts. */
enum OrderPolicy {

  /** The protocol is correct only with this order. A run with another order is an error. */
  case Locked(order: ChannelOrder)

  /** The protocol works with each order. This order is the default. */
  case Default(order: ChannelOrder)

  def default: ChannelOrder = this match {
    case Locked(order)  => order
    case Default(order) => order
  }

  /** Returns the order for a run, or `Left` when the policy locks another order. */
  def resolve(requested: ChannelOrder): Either[ChannelOrder, ChannelOrder] = this match {
    case Locked(order) =>
      if order == requested then { Right(order) }
      else { Left(order) }
    case Default(_) => Right(requested)
  }
}

/** What a node knows about its place in the network. */
final case class NodeCtx(self: NodeId, in: SortedSet[NodeId], out: SortedSet[NodeId])

object NodeCtx {
  def of(topology: Topology, node: NodeId): NodeCtx = {
    NodeCtx(node, topology.inNeighbours(node), topology.outNeighbours(node))
  }
}

/** One message that a step sends on the channel from the node to `to`. */
final case class Send[+M](to: NodeId, msg: M)

/** The result of one handler: the new state, the sends, an optional decision and the rule that
  * fired. The engine performs the sends. The handler does not.
  */
final case class Step[+S, +M, +O](state: S, sends: List[Send[M]], decision: Option[O], rule: RuleId)

/** An illegal input to a protocol. The engine records it as `Rejected`, and the run continues. */
enum ProtocolError derives Codec.AsObject {

  /** The message is not legal in the current state of the node. */
  case UnexpectedMessage(from: NodeId, detail: String)

  /** The action is not legal in the current state of the node. */
  case IllegalAction(detail: String)

  /** The step sends to a node that is not an out-neighbour. The engine detects this error. */
  case NotANeighbour(to: NodeId)
}

/** One distributed algorithm as pure functions.
  *
  * `S` is the state of one node, `M` the message, `A` the spontaneous action and `O` the output of
  * a decision. All functions are pure. They return effects as data in `Step`.
  */
trait Protocol[S, M, A, O] {
  def id: AlgorithmId
  def order: OrderPolicy

  /** The initial state of one node, or an error when the topology does not suit the protocol. */
  def init(ctx: NodeCtx, attrs: NodeAttrs): Either[TopologyError, S]

  /** The spontaneous actions that the node can do now, in a fixed order. */
  def actions(ctx: NodeCtx, state: S): List[A]

  def onAction(ctx: NodeCtx, state: S, action: A): Either[ProtocolError, Step[S, M, O]]

  def onMessage(ctx: NodeCtx, state: S, from: NodeId, msg: M): Either[ProtocolError, Step[S, M, O]]
}
