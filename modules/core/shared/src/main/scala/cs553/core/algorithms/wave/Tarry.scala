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
import cs553.core.trace.EdgeStyle
import cs553.core.trace.Inspect
import cs553.core.trace.Mark
import cs553.core.trace.NodeFill
import cs553.core.trace.Overlay
import cs553.core.world.World
import io.circe.Codec

import scala.collection.immutable.SortedMap
import scala.collection.immutable.SortedSet

/** The one message of Tarry. */
enum TarryMsg derives Codec.AsObject {
  case Token
}

/** The spontaneous actions. The initiator wakes. A node that holds the token passes it on one
  * channel that the rules allow. The scheduler or the learner picks the channel.
  */
enum TarryAction derives Codec.AsObject {
  case Wake
  case Pass(to: NodeId)
}

/** The decision of the initiator: the traversal is complete. */
enum TarryOutput derives Codec.AsObject {
  case TraversalComplete
}

/** The phase of one node. */
enum TarryPhase derives Codec.AsObject {

  /** The node did not get the token yet. The initiator holds the token before it wakes. */
  case Idle

  /** The node holds the token and must pass it on. */
  case Holding

  /** The node passed the token on a channel that is not its parent channel, and waits for it. */
  case Waiting

  /** A non-initiator passed the token to its parent. It does not get the token again. */
  case Done

  /** The initiator got the token back and sent through each channel. It decided. */
  case Decided
}

/** The state of one node. `parent` is the neighbour of the first token. The initiator has no
  * parent. `sent` holds the neighbours that the node passed the token to, and `received` the
  * neighbours that passed the token to the node. `degree` is the number of neighbours.
  */
final case class TarryState(
    role: Role,
    phase: TarryPhase,
    parent: Option[NodeId],
    sent: SortedSet[NodeId],
    received: SortedSet[NodeId],
    degree: Int
) derives Codec.AsObject

/** Tarry's traversal algorithm (Fokkink, section 4.3). See `docs/algorithms/tarry.md`. */
object Tarry {

  val id: AlgorithmId = AlgorithmId("tarry")

  /** The rule ids. Each one names one case of a handler. */
  object Rules {
    val wake: RuleId = RuleId("wake")
    val wakeAlone: RuleId = RuleId("wake-alone")
    val firstToken: RuleId = RuleId("first-token")
    val tokenBack: RuleId = RuleId("token-back")
    val pass: RuleId = RuleId("pass")
    val passToParent: RuleId = RuleId("pass-to-parent")
    val decide: RuleId = RuleId("decide")
  }

  private type Result = Either[ProtocolError, Step[TarryState, TarryMsg, TarryOutput]]

  /** The number of tokens in the world: the buffered tokens, plus the node that holds the token.
    * The initiator holds the token before it wakes and after it decides.
    */
  def tokens(w: World[TarryState, TarryMsg]): Int = {
    val held = w.states.values.count { s =>
      s.phase match {
        case TarryPhase.Holding | TarryPhase.Decided => true
        case TarryPhase.Idle                         => s.role == Role.Initiator
        case TarryPhase.Waiting | TarryPhase.Done    => false
      }
    }
    w.buffered.size + held
  }

  /** The invariant that the web checks at each step: exactly one token exists, and a node passes
    * the token to its parent last. One token also means that no channel carries two tokens, so no
    * separate check for that exists.
    */
  def invariant(w: World[TarryState, TarryMsg]): Option[String] = {
    val count = tokens(w)
    val oneToken =
      if count == 1 then { None }
      else { Some(s"The world holds $count tokens, not exactly one.") }
    val parentLast = w.states.collectFirst {
      case (node, s)
          if s.parent.exists(s.sent.contains) && s.sent != w.topology.outNeighbours(node) =>
        s"Node ${node.value} passed the token to its parent before it used each other channel."
    }
    oneToken.orElse(parentLast)
  }

  /** The channels on which a node in phase `Holding` can pass the token. Rule 1: a node passes the
    * token at most once on each channel. Rule 2: a non-initiator passes the token to its parent
    * only when no other channel is left.
    */
  def candidates(ctx: NodeCtx, state: TarryState): List[NodeId] = {
    val unsent = ctx.out.toList.filterNot(state.sent.contains)
    val others = unsent.filterNot(state.parent.contains)
    if others.nonEmpty then { others }
    else { unsent }
  }

  /** A topology that `prepare` made ready for Tarry, and the nodes that it omitted. */
  final case class Prepared(topology: Topology, omitted: SortedSet[NodeId])

  /** Makes a loaded topology ready for Tarry, as the college fixtures need:
    *
    *   - It adds the reverse of each edge, so a digraph becomes undirected.
    *   - When `initiator` is given, that node becomes the only initiator.
    *   - It omits each node that has no edges and is not an initiator.
    *
    * Then it runs `protocol.check`.
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
      checked <- protocol.check(trimmed)
    } yield Prepared(checked, omitted)
  }

  val protocol: Protocol[TarryState, TarryMsg, TarryAction, TarryOutput] =
    new Protocol[TarryState, TarryMsg, TarryAction, TarryOutput] {
      val id: AlgorithmId = Tarry.id
      val order: OrderPolicy = OrderPolicy.Default(ChannelOrder.Fifo)

      /** An undirected connected graph with exactly one initiator. */
      override def check(topology: Topology): Either[NonEmptyList[TopologyError], Topology] = {
        val directed =
          if topology.isSymmetric then { Nil }
          else { List(TopologyError.Requirement("Tarry needs an undirected graph")) }
        val connected =
          if topology.isConnected then { Nil }
          else {
            val isolated = topology.isolated.toList.map(_.value)
            val detail =
              if isolated.isEmpty then { "" }
              else { s"; these nodes have no edges: ${isolated.mkString(", ")}" }
            List(TopologyError.Requirement(s"Tarry needs a connected graph$detail"))
          }
        val initiators = topology.initiators.toList
        val single =
          if initiators.size == 1 then { Nil }
          else {
            val found = initiators.map(_.value).mkString(", ")
            List(
              TopologyError.Requirement(
                s"Tarry needs exactly one initiator, not ${initiators.size} ($found)"
              )
            )
          }
        NonEmptyList.fromList(directed ++ connected ++ single) match {
          case Some(errors) => Left(errors)
          case None         => Right(topology)
        }
      }

      def init(ctx: NodeCtx, attrs: NodeAttrs): Either[TopologyError, TarryState] = {
        if ctx.in == ctx.out then {
          Right(
            TarryState(
              attrs.role,
              TarryPhase.Idle,
              None,
              SortedSet.empty,
              SortedSet.empty,
              ctx.out.size
            )
          )
        } else {
          Left(
            TopologyError.Requirement(
              s"Tarry needs an undirected graph, but node ${ctx.self.value} has different " +
                "in-neighbours and out-neighbours"
            )
          )
        }
      }

      def actions(ctx: NodeCtx, state: TarryState): List[TarryAction] = {
        state.phase match {
          case TarryPhase.Idle =>
            state.role match {
              case Role.Initiator    => List(TarryAction.Wake)
              case Role.NonInitiator => Nil
            }
          case TarryPhase.Holding => candidates(ctx, state).map(TarryAction.Pass(_))
          case TarryPhase.Waiting | TarryPhase.Done | TarryPhase.Decided => Nil
        }
      }

      def onAction(ctx: NodeCtx, state: TarryState, action: TarryAction): Result = {
        if actions(ctx, state).contains(action) then {
          action match {
            case TarryAction.Wake     => wake(ctx, state)
            case TarryAction.Pass(to) => pass(state, to)
          }
        } else {
          Left(
            ProtocolError.IllegalAction(
              s"$action is not legal for a ${state.role} in phase ${state.phase}"
            )
          )
        }
      }

      def onMessage(ctx: NodeCtx, state: TarryState, from: NodeId, msg: TarryMsg): Result = {
        msg match {
          case TarryMsg.Token =>
            state.phase match {
              case TarryPhase.Idle =>
                state.role match {
                  case Role.NonInitiator => firstToken(state, from)
                  case Role.Initiator    =>
                    Left(
                      ProtocolError.UnexpectedMessage(from, "the initiator got a token before Wake")
                    )
                }
              case TarryPhase.Waiting =>
                if state.received.contains(from) then {
                  Left(
                    ProtocolError.UnexpectedMessage(
                      from,
                      "the channel carried the token a second time"
                    )
                  )
                } else { tokenBack(ctx, state, from) }
              case TarryPhase.Holding =>
                Left(ProtocolError.UnexpectedMessage(from, "the node already holds the token"))
              case TarryPhase.Done | TarryPhase.Decided =>
                Left(
                  ProtocolError.UnexpectedMessage(from, "the node got a token after it finished")
                )
            }
        }
      }
    }

  /** The initiator wakes and holds the token. With no neighbour, it decides at once. */
  private def wake(ctx: NodeCtx, state: TarryState): Result = {
    if ctx.out.isEmpty then {
      Right(
        Step(
          state.copy(phase = TarryPhase.Decided),
          Nil,
          Some(TarryOutput.TraversalComplete),
          Rules.wakeAlone
        )
      )
    } else { Right(Step(state.copy(phase = TarryPhase.Holding), Nil, None, Rules.wake)) }
  }

  /** The node passes the token to `to`. When `to` is the parent, the node is done. */
  private def pass(state: TarryState, to: NodeId): Result = {
    val sent = state.copy(sent = state.sent + to)
    val (phase, rule) =
      if state.parent.contains(to) then { (TarryPhase.Done, Rules.passToParent) }
      else { (TarryPhase.Waiting, Rules.pass) }
    Right(Step(sent.copy(phase = phase), List(Send(to, TarryMsg.Token)), None, rule))
  }

  /** A non-initiator gets its first token. The sender becomes its parent. */
  private def firstToken(state: TarryState, from: NodeId): Result = {
    Right(
      Step(
        state
          .copy(phase = TarryPhase.Holding, parent = Some(from), received = state.received + from),
        Nil,
        None,
        Rules.firstToken
      )
    )
  }

  /** A waiting node gets the token back. The initiator decides when it sent through each channel.
    */
  private def tokenBack(ctx: NodeCtx, state: TarryState, from: NodeId): Result = {
    val after = state.copy(received = state.received + from)
    val allSent = ctx.out.forall(state.sent.contains)
    state.role match {
      case Role.Initiator if allSent =>
        Right(
          Step(
            after.copy(phase = TarryPhase.Decided),
            Nil,
            Some(TarryOutput.TraversalComplete),
            Rules.decide
          )
        )
      case Role.Initiator | Role.NonInitiator =>
        Right(Step(after.copy(phase = TarryPhase.Holding), Nil, None, Rules.tokenBack))
    }
  }
}

object TarryState {
  given Inspect[TarryState] = new Inspect[TarryState] {

    def label(s: TarryState): String = s.phase match {
      case TarryPhase.Holding => s"holds the token, ${s.sent.size} of ${s.degree} sent"
      case TarryPhase.Waiting => s"waiting, ${s.sent.size} of ${s.degree} sent"
      case TarryPhase.Idle | TarryPhase.Done | TarryPhase.Decided => phaseName(s.phase)
    }
    private def phaseName(phase: TarryPhase): String = phase match {
      case TarryPhase.Idle    => "idle"
      case TarryPhase.Holding => "holds the token"
      case TarryPhase.Waiting => "waiting"
      case TarryPhase.Done    => "done"
      case TarryPhase.Decided => "decided"
    }
    private def nodes(set: SortedSet[NodeId]): String = {
      if set.isEmpty then { "none" }
      else { set.toList.map(_.value).mkString(", ") }
    }
    def fields(s: TarryState): List[(String, String)] = {
      val role = s.role match {
        case Role.Initiator    => "initiator"
        case Role.NonInitiator => "not an initiator"
      }
      List(
        "role" -> role,
        "phase" -> phaseName(s.phase),
        "parent" -> s.parent.fold("none")(p => s"node ${p.value} (the parent is the last edge)"),
        "sent to" -> nodes(s.sent),
        "received from" -> nodes(s.received)
      )
    }
  }

  /** The used channels, the parent edges, the fill of the initiator when it decides, the `Passive`
    * fill of each node that is done, and one fact for rule 2.
    */
  given Overlay[TarryState] = new Overlay[TarryState] {
    def marks(states: SortedMap[NodeId, TarryState]): List[Mark] = {
      val used = states.toList.flatMap { case (node, s) =>
        s.sent.toList.map(Mark.OnEdge(node, _, EdgeStyle.Used))
      }
      val tree = states.toList.flatMap { case (node, s) => s.parent.map(Mark.treeEdge(node, _)) }
      val holder = states.toList.collect {
        case (node, s) if s.phase == TarryPhase.Holding => Mark.Badge(node, "token")
      }
      val fills = states.toList.collect {
        case (node, s) if s.role == Role.Initiator && s.phase == TarryPhase.Decided =>
          Mark.Fill(node, NodeFill.Chosen)
        case (node, s) if s.phase == TarryPhase.Done => Mark.Fill(node, NodeFill.Passive)
      }
      val rule = Option.when(tree.nonEmpty)(Mark.Fact("parent edge is sent last"))
      used ++ tree ++ holder ++ fills ++ rule.toList
    }
  }
}

object TarryMsg {
  given Inspect[TarryMsg] = new Inspect[TarryMsg] {
    def label(m: TarryMsg): String = m match {
      case TarryMsg.Token => "Token"
    }
    def fields(m: TarryMsg): List[(String, String)] = Nil
  }
}

object TarryAction {
  given Inspect[TarryAction] = new Inspect[TarryAction] {
    def label(a: TarryAction): String = a match {
      case TarryAction.Wake     => "Wake"
      case TarryAction.Pass(to) => s"Pass to ${to.value}"
    }
    def fields(a: TarryAction): List[(String, String)] = a match {
      case TarryAction.Wake     => Nil
      case TarryAction.Pass(to) => List("to" -> s"node ${to.value}")
    }
  }
}

object TarryOutput {
  given Inspect[TarryOutput] = new Inspect[TarryOutput] {
    def label(o: TarryOutput): String = o match {
      case TarryOutput.TraversalComplete => "the traversal is complete"
    }
    def fields(o: TarryOutput): List[(String, String)] = Nil
  }
}
