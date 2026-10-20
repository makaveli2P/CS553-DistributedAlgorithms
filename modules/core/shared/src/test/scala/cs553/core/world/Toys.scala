package cs553.core.world

import cs553.core.ids.AlgorithmId
import cs553.core.ids.NodeId
import cs553.core.ids.RuleId
import cs553.core.protocol.ChannelOrder
import cs553.core.protocol.NodeCtx
import cs553.core.protocol.OrderPolicy
import cs553.core.protocol.Protocol
import cs553.core.protocol.ProtocolError
import cs553.core.protocol.Send
import cs553.core.protocol.Step
import cs553.core.topology.NodeAttrs
import cs553.core.topology.Role
import cs553.core.topology.TopologyError
import io.circe.Codec

/** Toy protocols for the World and Simulator tests. They are not course algorithms. */
object Toys {

  // -----------------------------------------------------------------------------------------------
  // Flood: the initiator starts, and each node forwards the first Ping to all other neighbours.

  enum FloodState derives Codec.AsObject {
    case Idle(role: Role)
    case Reached(parent: Option[NodeId])
  }

  enum FloodMsg derives Codec.AsObject {
    case Ping
  }

  enum FloodAction derives Codec.AsObject {
    case Start
  }

  enum FloodOutput derives Codec.AsObject {
    case Covered
  }

  val flood: Protocol[FloodState, FloodMsg, FloodAction, FloodOutput] =
    new Protocol[FloodState, FloodMsg, FloodAction, FloodOutput] {
      val id: AlgorithmId = AlgorithmId("toy-flood")
      val order: OrderPolicy = OrderPolicy.Default(ChannelOrder.Fifo)

      def init(ctx: NodeCtx, attrs: NodeAttrs): Either[TopologyError, FloodState] = {
        Right(FloodState.Idle(attrs.role))
      }

      def actions(ctx: NodeCtx, state: FloodState): List[FloodAction] = {
        state match {
          case FloodState.Idle(Role.Initiator)    => List(FloodAction.Start)
          case FloodState.Idle(Role.NonInitiator) => Nil
          case FloodState.Reached(_)              => Nil
        }
      }

      def onAction(
          ctx: NodeCtx,
          state: FloodState,
          action: FloodAction
      ): Either[ProtocolError, Step[FloodState, FloodMsg, FloodOutput]] = {
        action match {
          case FloodAction.Start =>
            Right(
              Step(
                FloodState.Reached(None),
                ctx.out.toList.map(Send(_, FloodMsg.Ping)),
                Some(FloodOutput.Covered),
                RuleId("start")
              )
            )
        }
      }

      def onMessage(
          ctx: NodeCtx,
          state: FloodState,
          from: NodeId,
          msg: FloodMsg
      ): Either[ProtocolError, Step[FloodState, FloodMsg, FloodOutput]] = {
        (state, msg) match {
          case (FloodState.Idle(_), FloodMsg.Ping) =>
            Right(
              Step(
                FloodState.Reached(Some(from)),
                ctx.out.toList.filterNot(_ == from).map(Send(_, FloodMsg.Ping)),
                Some(FloodOutput.Covered),
                RuleId("first-ping")
              )
            )
          case (FloodState.Reached(_), FloodMsg.Ping) =>
            Right(Step(state, Nil, None, RuleId("later-ping")))
        }
      }
    }

  // -----------------------------------------------------------------------------------------------
  // Chatter: a node with budget sends two messages to each out-neighbour per Talk. It exercises
  // FIFO heads, rejected input and sends to a node that is not a neighbour.

  enum ChatMsg derives Codec.AsObject {
    case Hello(n: Int)
    case Poison
  }

  enum ChatAction derives Codec.AsObject {
    case Talk
    case Stray(to: NodeId)
    case SendPoison(to: NodeId)
  }

  /** `extras` holds the test-only actions that a test puts into the state. */
  final case class ChatState(budget: Int, heard: List[Int], extras: List[ChatAction])
      derives Codec.AsObject

  val chatter: Protocol[ChatState, ChatMsg, ChatAction, Int] =
    new Protocol[ChatState, ChatMsg, ChatAction, Int] {
      val id: AlgorithmId = AlgorithmId("toy-chatter")
      val order: OrderPolicy = OrderPolicy.Default(ChannelOrder.Fifo)

      def init(ctx: NodeCtx, attrs: NodeAttrs): Either[TopologyError, ChatState] = {
        attrs.role match {
          case Role.Initiator    => Right(ChatState(1, Nil, Nil))
          case Role.NonInitiator => Right(ChatState(0, Nil, Nil))
        }
      }

      def actions(ctx: NodeCtx, state: ChatState): List[ChatAction] = {
        (if state.budget > 0 then { List(ChatAction.Talk) }
         else { Nil }) ++ state.extras
      }

      def onAction(
          ctx: NodeCtx,
          state: ChatState,
          action: ChatAction
      ): Either[ProtocolError, Step[ChatState, ChatMsg, Int]] = {
        action match {
          case ChatAction.Talk =>
            Right(
              Step(
                state.copy(budget = state.budget - 1),
                ctx.out.toList.flatMap(to =>
                  List(Send(to, ChatMsg.Hello(1)), Send(to, ChatMsg.Hello(2)))
                ),
                None,
                RuleId("talk")
              )
            )
          case ChatAction.Stray(to) =>
            val after = state.copy(extras = state.extras.filterNot(_ == action))
            Right(Step(after, List(Send(to, ChatMsg.Hello(0))), None, RuleId("stray")))
          case ChatAction.SendPoison(to) =>
            val after = state.copy(extras = state.extras.filterNot(_ == action))
            Right(Step(after, List(Send(to, ChatMsg.Poison)), None, RuleId("poison")))
        }
      }

      def onMessage(
          ctx: NodeCtx,
          state: ChatState,
          from: NodeId,
          msg: ChatMsg
      ): Either[ProtocolError, Step[ChatState, ChatMsg, Int]] = {
        msg match {
          case ChatMsg.Hello(n) =>
            Right(Step(state.copy(heard = state.heard :+ n), Nil, Some(n), RuleId("hear")))
          case ChatMsg.Poison => Left(ProtocolError.UnexpectedMessage(from, "poison"))
        }
      }
    }

  /** A Chatter protocol that only accepts `AnyOrder`, to test `OrderPolicy.Locked`. */
  val lockedChatter: Protocol[ChatState, ChatMsg, ChatAction, Int] =
    new Protocol[ChatState, ChatMsg, ChatAction, Int] {
      val id: AlgorithmId = chatter.id
      val order: OrderPolicy = OrderPolicy.Locked(ChannelOrder.AnyOrder)
      def init(ctx: NodeCtx, attrs: NodeAttrs): Either[TopologyError, ChatState] = {
        chatter.init(ctx, attrs)
      }
      def actions(ctx: NodeCtx, state: ChatState): List[ChatAction] = chatter.actions(ctx, state)
      def onAction(
          ctx: NodeCtx,
          state: ChatState,
          action: ChatAction
      ): Either[ProtocolError, Step[ChatState, ChatMsg, Int]] = chatter.onAction(ctx, state, action)
      def onMessage(
          ctx: NodeCtx,
          state: ChatState,
          from: NodeId,
          msg: ChatMsg
      ): Either[ProtocolError, Step[ChatState, ChatMsg, Int]] = {
        chatter.onMessage(ctx, state, from, msg)
      }
    }
}
