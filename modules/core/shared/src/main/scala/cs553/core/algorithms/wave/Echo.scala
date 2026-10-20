package cs553.core.algorithms.wave

import cats.data.NonEmptyList
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
import cs553.core.topology.Topology
import cs553.core.topology.TopologyError
import cs553.core.trace.Inspect
import io.circe.Codec

import scala.collection.immutable.SortedSet

/** The one message of Echo. */
enum EchoMsg derives Codec.AsObject {
  case Token
}

/** The spontaneous action of the initiator. */
enum EchoAction derives Codec.AsObject {
  case Wake
}

/** The decision of the initiator: the wave is complete. */
enum EchoOutput derives Codec.AsObject {
  case WaveComplete
}

/** The phase of one node. */
enum EchoPhase derives Codec.AsObject {

  /** The node did not send or receive a token. */
  case Idle

  /** The node sent its tokens and waits for a token from each neighbour. */
  case Waiting

  /** A non-initiator got a token from each neighbour and sent the echo to its parent. */
  case Echoed

  /** The initiator got a token from each neighbour and decided. */
  case Decided
}

/** The state of one node. `parent` is the neighbour of the first token. The initiator has no
  * parent. `received` counts the tokens that the node got. `expected` is the degree of the node:
  * the node waits for that many tokens.
  */
final case class EchoState(
    role: Role,
    phase: EchoPhase,
    parent: Option[NodeId],
    received: Int,
    expected: Int
) derives Codec.AsObject

/** The Echo algorithm (Fokkink, section 4.3). See `docs/algorithms/echo.md`. */
object Echo {

  val id: AlgorithmId = AlgorithmId("echo")

  /** The rule ids. Each one names one case of a handler. */
  object Rules {
    val wake: RuleId = RuleId("wake")
    val wakeAlone: RuleId = RuleId("wake-alone")
    val firstToken: RuleId = RuleId("first-token")
    val firstTokenLeaf: RuleId = RuleId("first-token-leaf")
    val countToken: RuleId = RuleId("count-token")
    val echoToParent: RuleId = RuleId("echo-to-parent")
    val decide: RuleId = RuleId("decide")
  }

  private type Result = Either[ProtocolError, Step[EchoState, EchoMsg, EchoOutput]]

  /** A topology that `prepare` made ready for Echo, and the nodes that it omitted. */
  final case class Prepared(topology: Topology, omitted: SortedSet[NodeId])

  /** Makes a loaded topology ready for Echo, as the college fixtures need:
    *
    *   - It adds the reverse of each edge, so a digraph becomes undirected.
    *   - When `initiator` is given, that node becomes the only initiator.
    *   - It omits each node that has no edges and is not an initiator.
    *
    * Then it runs `protocol.check`. When an initiator has no edges, the errors say so.
    */
  def prepare(
      topology: Topology,
      initiator: Option[NodeId]
  ): Either[NonEmptyList[TopologyError], Prepared] = {
    val undirected = topology.symmetric
    for {
      chosen <- initiator.fold[Either[NonEmptyList[TopologyError], Topology]](Right(undirected)) {
        n => undirected.withInitiators(Set(n))
      }
      omitted = chosen.isolated.filterNot(chosen.initiators.contains)
      trimmed <- chosen.without(omitted.toSet)
      checked <- protocol.check(trimmed).left.map { errors =>
        val alone = trimmed.initiators.filter(trimmed.isolated.contains).toList.map { n =>
          TopologyError.Requirement(
            s"the initiator ${n.value} has no edges; choose an initiator that has edges"
          )
        }
        errors ++ alone
      }
    } yield Prepared(checked, omitted)
  }

  val protocol: Protocol[EchoState, EchoMsg, EchoAction, EchoOutput] =
    new Protocol[EchoState, EchoMsg, EchoAction, EchoOutput] {
      val id: AlgorithmId = Echo.id
      val order: OrderPolicy = OrderPolicy.Default(ChannelOrder.Fifo)

      /** An undirected connected graph with exactly one initiator. `init` checks only what one node
        * can see.
        */
      override def check(topology: Topology): Either[NonEmptyList[TopologyError], Topology] = {
        val directed =
          if topology.isSymmetric then { Nil }
          else { List(TopologyError.Requirement("Echo needs an undirected graph")) }
        val connected =
          if topology.isConnected then { Nil }
          else {
            val isolated = topology.isolated.toList.map(_.value)
            val detail =
              if isolated.isEmpty then { "" }
              else { s"; these nodes have no edges: ${isolated.mkString(", ")}" }
            List(TopologyError.Requirement(s"Echo needs a connected graph$detail"))
          }
        val initiators = topology.initiators.toList
        val single =
          if initiators.size == 1 then { Nil }
          else {
            val found = initiators.map(_.value).mkString(", ")
            List(
              TopologyError.Requirement(
                s"Echo needs exactly one initiator, not ${initiators.size} ($found)"
              )
            )
          }
        NonEmptyList.fromList(directed ++ connected ++ single) match {
          case Some(errors) => Left(errors)
          case None         => Right(topology)
        }
      }

      def init(ctx: NodeCtx, attrs: NodeAttrs): Either[TopologyError, EchoState] = {
        if ctx.in == ctx.out then {
          Right(EchoState(attrs.role, EchoPhase.Idle, None, 0, ctx.out.size))
        } else {
          Left(
            TopologyError.Requirement(
              s"Echo needs an undirected graph, but node ${ctx.self.value} has different " +
                "in-neighbours and out-neighbours"
            )
          )
        }
      }

      def actions(ctx: NodeCtx, state: EchoState): List[EchoAction] = {
        state.phase match {
          case EchoPhase.Idle =>
            state.role match {
              case Role.Initiator    => List(EchoAction.Wake)
              case Role.NonInitiator => Nil
            }
          case EchoPhase.Waiting | EchoPhase.Echoed | EchoPhase.Decided => Nil
        }
      }

      def onAction(ctx: NodeCtx, state: EchoState, action: EchoAction): Result = {
        action match {
          case EchoAction.Wake =>
            actions(ctx, state) match {
              case Nil =>
                Left(
                  ProtocolError.IllegalAction(
                    s"Wake is not legal for a ${state.role} in phase ${state.phase}"
                  )
                )
              case _ :: _ => wake(ctx, state)
            }
        }
      }

      def onMessage(ctx: NodeCtx, state: EchoState, from: NodeId, msg: EchoMsg): Result = {
        msg match {
          case EchoMsg.Token =>
            state.phase match {
              case EchoPhase.Idle =>
                state.role match {
                  case Role.NonInitiator => firstToken(ctx, state, from)
                  case Role.Initiator    =>
                    Left(
                      ProtocolError.UnexpectedMessage(from, "the initiator got a token before Wake")
                    )
                }
              case EchoPhase.Waiting                    => countToken(state)
              case EchoPhase.Echoed | EchoPhase.Decided =>
                Left(ProtocolError.UnexpectedMessage(from, "the node got a token after its echo"))
            }
        }
      }
    }

  /** The initiator wakes and sends a token to each neighbour. With no neighbour, it decides. */
  private def wake(ctx: NodeCtx, state: EchoState): Result = {
    if ctx.out.isEmpty then {
      Right(
        Step(
          state.copy(phase = EchoPhase.Decided),
          Nil,
          Some(EchoOutput.WaveComplete),
          Rules.wakeAlone
        )
      )
    } else {
      Right(
        Step(
          state.copy(phase = EchoPhase.Waiting),
          ctx.out.toList.map(Send(_, EchoMsg.Token)),
          None,
          Rules.wake
        )
      )
    }
  }

  /** A non-initiator gets its first token. The sender becomes its parent. */
  private def firstToken(ctx: NodeCtx, state: EchoState, from: NodeId): Result = {
    val others = ctx.out.toList.filterNot(_ == from)
    val after = state.copy(parent = Some(from), received = 1)
    if others.isEmpty then {
      Right(
        Step(
          after.copy(phase = EchoPhase.Echoed),
          List(Send(from, EchoMsg.Token)),
          None,
          Rules.firstTokenLeaf
        )
      )
    } else {
      Right(
        Step(
          after.copy(phase = EchoPhase.Waiting),
          others.map(Send(_, EchoMsg.Token)),
          None,
          Rules.firstToken
        )
      )
    }
  }

  /** A node in phase `Waiting` gets one more token. */
  private def countToken(state: EchoState): Result = {
    val received = state.received + 1
    val counted = state.copy(received = received)
    if received < state.expected then { Right(Step(counted, Nil, None, Rules.countToken)) }
    else {
      state.role match {
        case Role.Initiator =>
          Right(
            Step(
              counted.copy(phase = EchoPhase.Decided),
              Nil,
              Some(EchoOutput.WaveComplete),
              Rules.decide
            )
          )
        case Role.NonInitiator =>
          state.parent match {
            case Some(parent) =>
              Right(
                Step(
                  counted.copy(phase = EchoPhase.Echoed),
                  List(Send(parent, EchoMsg.Token)),
                  None,
                  Rules.echoToParent
                )
              )
            case None =>
              Left(ProtocolError.InvariantBroken("a waiting non-initiator has no parent"))
          }
      }
    }
  }
}

object EchoState {
  given Inspect[EchoState] = new Inspect[EchoState] {
    def label(s: EchoState): String = s.phase match {
      case EchoPhase.Idle    => "idle"
      case EchoPhase.Waiting => s"waiting, received ${s.received} of ${s.expected}"
      case EchoPhase.Echoed  => "echoed"
      case EchoPhase.Decided => "decided"
    }
    def fields(s: EchoState): List[(String, String)] = List(
      "role" -> s.role.toString,
      "phase" -> s.phase.toString,
      "parent" -> s.parent.fold("none")(_.value.toString),
      "received" -> s.received.toString,
      "expected" -> s.expected.toString
    )
  }
}

object EchoMsg {
  given Inspect[EchoMsg] = new Inspect[EchoMsg] {
    def label(m: EchoMsg): String = m match {
      case EchoMsg.Token => "Token"
    }
    def fields(m: EchoMsg): List[(String, String)] = Nil
  }
}

object EchoAction {
  given Inspect[EchoAction] = new Inspect[EchoAction] {
    def label(a: EchoAction): String = a match {
      case EchoAction.Wake => "Wake"
    }
    def fields(a: EchoAction): List[(String, String)] = Nil
  }
}

object EchoOutput {
  given Inspect[EchoOutput] = new Inspect[EchoOutput] {
    def label(o: EchoOutput): String = o match {
      case EchoOutput.WaveComplete => "WaveComplete"
    }
    def fields(o: EchoOutput): List[(String, String)] = Nil
  }
}
