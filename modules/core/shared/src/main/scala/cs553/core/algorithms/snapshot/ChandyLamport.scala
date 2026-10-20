package cs553.core.algorithms.snapshot

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
import cs553.core.trace.ChannelRecord
import cs553.core.trace.Chip
import cs553.core.trace.Inspect
import cs553.core.trace.Mark
import cs553.core.trace.NodeFill
import cs553.core.trace.Overlay
import cs553.core.world.World
import io.circe.Codec

import scala.collection.immutable.SortedMap

/** The messages of Chandy-Lamport: the bank application and the snapshot marker. */
enum ClMsg derives Codec.AsObject {

  /** The application moves `amount` from the sender to the receiver. */
  case Transfer(amount: Int)

  /** The sender took its local snapshot before it sent this message. */
  case Marker
}

/** The spontaneous actions of one node. */
enum ClAction derives Codec.AsObject {

  /** The node sends `amount` of its balance to the out-neighbour `to`. */
  case Spend(to: NodeId, amount: Int)

  /** An initiator takes its local snapshot and starts the snapshot. */
  case Initiate
}

/** The record of one incoming channel at a node that took its local snapshot. The amounts are the
  * transfers that arrived on the channel while it was open, oldest first.
  */
enum ChannelLog derives Codec.AsObject {

  /** The node records each transfer that arrives on the channel. */
  case Open(from: NodeId, amounts: List[Int])

  /** The marker came on the channel. The record is final. */
  case Closed(from: NodeId, amounts: List[Int])

  def sender: NodeId = this match {
    case Open(from, _)   => from
    case Closed(from, _) => from
  }

  def recorded: List[Int] = this match {
    case Open(_, amounts)   => amounts
    case Closed(_, amounts) => amounts
  }
}

/** The snapshot phase of one node. */
enum ClPhase derives Codec.AsObject {

  /** The node did not take its local snapshot. */
  case Waiting

  /** The node saved its balance, and one or more incoming channels are still open. */
  case Recording(saved: Int, channels: List[ChannelLog])

  /** The node saved its balance, and each incoming channel is closed. */
  case Recorded(saved: Int, channels: List[ChannelLog])
}

/** The state of one node: the bank balance, the number of transfers that it can still start and the
  * snapshot phase.
  */
final case class ClState(role: Role, balance: Int, budget: Int, phase: ClPhase)
    derives Codec.AsObject

/** The recorded state of one incoming channel in the output. */
final case class RecordedChannel(from: NodeId, amounts: List[Int]) derives Codec.AsObject

/** The decision of one node: its part of the global snapshot. */
enum ClOutput derives Codec.AsObject {
  case Snapshot(balance: Int, channels: List[RecordedChannel])
}

/** The Chandy-Lamport snapshot (Fokkink, section 3.1) on a strongly connected directed graph, with
  * a bank application. See `docs/algorithms/chandy-lamport.md`.
  */
object ChandyLamport {

  val id: AlgorithmId = AlgorithmId("chandy-lamport")

  /** The balance of a node that has no `balance` attribute. */
  val defaultBalance: Int = 10

  /** The transfer budget of a node that has no `budget` attribute. */
  val defaultBudget: Int = 3

  /** The largest amount of one `Spend` action. */
  val maxSpend: Int = 3

  /** The rule ids. Each one names one case of a handler. */
  object Rules {
    val spend: RuleId = RuleId("spend")
    val initiate: RuleId = RuleId("initiate")
    val firstMarker: RuleId = RuleId("first-marker")
    val closeChannel: RuleId = RuleId("close-channel")
    val receive: RuleId = RuleId("receive")
    val receiveRecord: RuleId = RuleId("receive-record")
  }

  private type Result = Either[ProtocolError, Step[ClState, ClMsg, ClOutput]]

  /** The initial balance of each node, from the attributes. A bad attribute counts as the default,
    * because `init` refuses it before a world exists.
    */
  def initialTotal(topology: Topology): Int = {
    topology.nodes.toList.map { n =>
      topology.attrsOf(n).int(n, NodeAttrs.Keys.balance).toOption.flatten.getOrElse(defaultBalance)
    }.sum
  }

  /** The invariant that the web checks at each step.
    *
    *   - The balances plus the transfers in the channels equal the initial total.
    *   - A marker is in a channel only when the sender took its snapshot.
    *   - A node records only its incoming channels, each one once.
    *   - When each node recorded, the saved balances plus the recorded channels equal the initial
    *     total.
    */
  def invariant(w: World[ClState, ClMsg]): Option[String] = {
    val total = initialTotal(w.topology)
    val inFlight = w.buffered.map(_.msg).collect { case ClMsg.Transfer(a) => a }.sum
    val live = w.states.values.map(_.balance).sum + inFlight
    val earlyMarker = w.buffered.collectFirst {
      case env if env.msg == ClMsg.Marker && w.states.get(env.id.from).exists(waiting) =>
        env.id.from
    }
    val badChannels = w.states.toList.collectFirst {
      case (node, s) if !channelsMatch(w, node, s.phase) => node
    }
    val snapshots = w.states.values.toList.map(_.phase).collect {
      case ClPhase.Recorded(saved, channels) => saved + channels.flatMap(_.recorded).sum
    }
    val recordedTotal = Option.when(snapshots.size == w.states.size)(snapshots.sum)
    if live != total then {
      Some(s"Total $live is not $total: money appeared or disappeared.")
    } else {
      earlyMarker
        .map(n => s"Node ${n.value} sent a marker before it took its snapshot.")
        .orElse(badChannels.map(n => s"Node ${n.value} records a channel that is not incoming."))
        .orElse(recordedTotal.filter(_ != total).map { t =>
          s"The snapshot total $t is not $total: the cut is not consistent."
        })
    }
  }

  private def waiting(s: ClState): Boolean = s.phase == ClPhase.Waiting

  private def channelsMatch(w: World[ClState, ClMsg], node: NodeId, phase: ClPhase): Boolean = {
    val incoming = w.ctxs.get(node).map(_.in.toList).getOrElse(Nil)
    phase match {
      case ClPhase.Waiting                => true
      case ClPhase.Recording(_, channels) => channels.map(_.sender) == incoming
      case ClPhase.Recorded(_, channels)  =>
        channels.map(_.sender) == incoming && channels.forall(isClosed)
    }
  }

  private def isClosed(c: ChannelLog): Boolean = c match {
    case ChannelLog.Open(_, _)   => false
    case ChannelLog.Closed(_, _) => true
  }

  val protocol: Protocol[ClState, ClMsg, ClAction, ClOutput] =
    new Protocol[ClState, ClMsg, ClAction, ClOutput] {
      val id: AlgorithmId = ChandyLamport.id
      val order: OrderPolicy = OrderPolicy.Locked(ChannelOrder.Fifo)

      /** A strongly connected directed graph with one or more initiators. */
      override def check(topology: Topology): Either[NonEmptyList[TopologyError], Topology] = {
        val shape =
          if topology.isStronglyConnected then { Nil }
          else {
            List(
              TopologyError.Requirement(
                "Chandy-Lamport needs a strongly connected directed graph, so that a marker " +
                  "reaches each node"
              )
            )
          }
        val initiators =
          if topology.initiators.isEmpty then {
            List(TopologyError.Requirement("Chandy-Lamport needs one or more initiators"))
          } else { Nil }
        NonEmptyList.fromList(shape ++ initiators) match {
          case Some(errors) => Left(errors)
          case None         => Right(topology)
        }
      }

      def init(ctx: NodeCtx, attrs: NodeAttrs): Either[TopologyError, ClState] = {
        for {
          balance <- attr(ctx.self, attrs, NodeAttrs.Keys.balance, defaultBalance)
          budget <- attr(ctx.self, attrs, NodeAttrs.Keys.budget, defaultBudget)
        } yield ClState(attrs.role, balance, budget, ClPhase.Waiting)
      }

      def actions(ctx: NodeCtx, state: ClState): List[ClAction] = {
        val spends =
          if state.budget > 0 then {
            for {
              to <- ctx.out.toList
              amount <- (1 to math.min(state.balance, maxSpend)).toList
            } yield ClAction.Spend(to, amount)
          } else { Nil }
        val start = if mayInitiate(state) then { List(ClAction.Initiate) }
        else { Nil }
        spends ++ start
      }

      def onAction(ctx: NodeCtx, state: ClState, action: ClAction): Result = {
        action match {
          case ClAction.Spend(to, amount) =>
            if state.budget <= 0 then {
              Left(ProtocolError.IllegalAction("Spend is not legal: the budget is 0"))
            } else if amount < 1 || amount > state.balance then {
              Left(
                ProtocolError.IllegalAction(
                  s"Spend($amount) is not legal: the balance is " +
                    s"${state.balance}"
                )
              )
            } else if !ctx.out.contains(to) then {
              Left(ProtocolError.IllegalAction(s"node ${to.value} is not an out-neighbour"))
            } else {
              val after = state.copy(balance = state.balance - amount, budget = state.budget - 1)
              Right(Step(after, List(Send(to, ClMsg.Transfer(amount))), None, Rules.spend))
            }
          case ClAction.Initiate =>
            if mayInitiate(state) then {
              Right(takeSnapshot(ctx, state, None, Rules.initiate))
            } else {
              Left(ProtocolError.IllegalAction(s"Initiate is not legal in state $state"))
            }
        }
      }

      def onMessage(ctx: NodeCtx, state: ClState, from: NodeId, msg: ClMsg): Result = {
        msg match {
          case ClMsg.Transfer(amount) => Right(onTransfer(state, from, amount))
          case ClMsg.Marker           => onMarker(ctx, state, from)
        }
      }
    }

  /** Only an initiator that did not take its snapshot can initiate. */
  private def mayInitiate(state: ClState): Boolean = {
    state.role == Role.Initiator && state.phase == ClPhase.Waiting
  }

  /** Whether the channel from `from` is open in `channels`. */
  private def isOpen(channels: List[ChannelLog], from: NodeId): Boolean = channels.exists {
    case ChannelLog.Open(n, _)   => n == from
    case ChannelLog.Closed(_, _) => false
  }

  /** Changes the open record of the channel from `from` with `f`. */
  private def updateOpen(channels: List[ChannelLog], from: NodeId)(
      f: List[Int] => ChannelLog
  ): List[ChannelLog] = channels.map {
    case ChannelLog.Open(n, amounts) if n == from => f(amounts)
    case c @ ChannelLog.Open(_, _)                => c
    case c @ ChannelLog.Closed(_, _)              => c
  }

  /** Reads one non-negative integer attribute, or the default when it is absent. */
  private def attr(
      node: NodeId,
      attrs: NodeAttrs,
      key: String,
      default: Int
  ): Either[TopologyError, Int] = {
    attrs.int(node, key).flatMap {
      case None              => Right(default)
      case Some(v) if v >= 0 => Right(v)
      case Some(v)           => Left(TopologyError.BadAttribute(node, key, v.toString))
    }
  }

  /** The node saves its balance, sends a marker on each outgoing channel and opens a record for
    * each incoming channel. The channel of the first marker (`first`) is closed and empty.
    */
  private def takeSnapshot(
      ctx: NodeCtx,
      state: ClState,
      first: Option[NodeId],
      rule: RuleId
  ): Step[ClState, ClMsg, ClOutput] = {
    val channels = ctx.in.toList.map { n =>
      if first.contains(n) then { ChannelLog.Closed(n, Nil) }
      else { ChannelLog.Open(n, Nil) }
    }
    val markers = ctx.out.toList.map(Send(_, ClMsg.Marker))
    val (phase, decision) = settle(state.balance, channels)
    Step(state.copy(phase = phase), markers, decision, rule)
  }

  /** The phase after a change to the records: `Recorded`, with the decision, when each channel is
    * closed.
    */
  private def settle(saved: Int, channels: List[ChannelLog]): (ClPhase, Option[ClOutput]) = {
    if channels.forall(isClosed) then {
      val out = channels.map(c => RecordedChannel(c.sender, c.recorded))
      (ClPhase.Recorded(saved, channels), Some(ClOutput.Snapshot(saved, out)))
    } else { (ClPhase.Recording(saved, channels), None) }
  }

  private def onTransfer(
      state: ClState,
      from: NodeId,
      amount: Int
  ): Step[ClState, ClMsg, ClOutput] = {
    val credited = state.copy(balance = state.balance + amount)
    state.phase match {
      case ClPhase.Recording(saved, channels) =>
        if isOpen(channels, from) then {
          val updated = updateOpen(channels, from)(a => ChannelLog.Open(from, a :+ amount))
          Step(
            credited.copy(phase = ClPhase.Recording(saved, updated)),
            Nil,
            None,
            Rules.receiveRecord
          )
        } else { Step(credited, Nil, None, Rules.receive) }
      case ClPhase.Waiting | ClPhase.Recorded(_, _) => Step(credited, Nil, None, Rules.receive)
    }
  }

  private def onMarker(ctx: NodeCtx, state: ClState, from: NodeId): Result = {
    state.phase match {
      case ClPhase.Waiting =>
        Right(takeSnapshot(ctx, state, Some(from), Rules.firstMarker))
      case ClPhase.Recording(saved, channels) =>
        if isOpen(channels, from) then {
          val updated = updateOpen(channels, from)(ChannelLog.Closed(from, _))
          val (phase, decision) = settle(saved, updated)
          Right(Step(state.copy(phase = phase), Nil, decision, Rules.closeChannel))
        } else {
          Left(
            ProtocolError.UnexpectedMessage(
              from,
              s"node ${ctx.self.value} got a second marker on the channel from ${from.value}"
            )
          )
        }
      case ClPhase.Recorded(_, _) =>
        Left(
          ProtocolError.UnexpectedMessage(
            from,
            s"node ${ctx.self.value} recorded each channel and got one more marker"
          )
        )
    }
  }
}

object ClState {

  /** The marks of a snapshot: the cut, the record of each incoming channel, an "initiator" badge
    * until the initiator decides, the `Chosen` fill of an initiator that decided, and the snapshot
    * total when each node recorded its channels.
    */
  given Overlay[ClState] = new Overlay[ClState] {
    def marks(states: SortedMap[NodeId, ClState]): List[Mark] = {
      val perNode = states.toList.flatMap { case (node, s) =>
        val (saved, channels) = s.phase match {
          case ClPhase.Waiting             => (None, Nil)
          case ClPhase.Recording(v, chans) => (Some(v), chans)
          case ClPhase.Recorded(v, chans)  => (Some(v), chans)
        }
        val role = (s.role, s.phase) match {
          case (Role.Initiator, ClPhase.Recorded(_, _)) => List(Mark.Fill(node, NodeFill.Chosen))
          case (Role.Initiator, _)                      => List(Mark.Badge(node, "initiator"))
          case (Role.NonInitiator, _)                   => Nil
        }
        val cut = saved.toList.map(_ => Mark.Cut(node))
        val records = channels.map {
          case ChannelLog.Open(from, amounts) =>
            Mark.Channel(from, node, ChannelRecord.Recording, amounts.map(_.toString))
          case ChannelLog.Closed(from, amounts) =>
            Mark.Channel(from, node, ChannelRecord.Closed, amounts.map(_.toString))
        }
        role ++ cut ++ records
      }
      val totals = states.values.toList.map(_.phase).collect {
        case ClPhase.Recorded(saved, channels) => saved + channels.flatMap(_.recorded).sum
      }
      val total = Option.when(states.nonEmpty && totals.size == states.size) {
        Mark.Fact(s"snapshot total ${totals.sum}")
      }
      perNode ++ total.toList
    }
  }

  given Inspect[ClState] = new Inspect[ClState] {

    /** One line for the live balance, and one line for the saved balance after the snapshot. */
    def label(s: ClState): String = s.phase match {
      case ClPhase.Waiting             => s"balance ${s.balance}"
      case ClPhase.Recording(saved, _) => s"balance ${s.balance}, saved $saved"
      case ClPhase.Recorded(saved, _)  => s"balance ${s.balance}, saved $saved"
    }
    def fields(s: ClState): List[(String, String)] = {
      val base = List("balance" -> s.balance.toString, "spends left" -> s.budget.toString)
      def chans(channels: List[ChannelLog]): List[(String, String)] = {
        channels.map { c =>
          val state = c match {
            case ChannelLog.Open(_, _)   => "recording"
            case ChannelLog.Closed(_, _) => "closed"
          }
          val contents = if c.recorded.isEmpty then { "empty" }
          else { c.recorded.mkString(", ") }
          s"from ${c.sender.value}" -> s"$state, $contents"
        }
      }
      s.phase match {
        case ClPhase.Waiting                    => ("phase" -> "waiting") :: base
        case ClPhase.Recording(saved, channels) =>
          List("phase" -> "recording") ++ base ++ List("saved" -> saved.toString) ++
            chans(channels)
        case ClPhase.Recorded(saved, channels) =>
          List("phase" -> "recorded") ++ base ++ List("saved" -> saved.toString) ++ chans(channels)
      }
    }
  }
}

object ClMsg {
  given Inspect[ClMsg] = new Inspect[ClMsg] {
    def label(m: ClMsg): String = m match {
      case ClMsg.Transfer(amount) => s"Transfer($amount)"
      case ClMsg.Marker           => "Marker"
    }
    def fields(m: ClMsg): List[(String, String)] = m match {
      case ClMsg.Transfer(amount) => List("amount" -> amount.toString)
      case ClMsg.Marker           => Nil
    }
    override def chip(m: ClMsg): Chip = m match {
      case ClMsg.Transfer(amount) => Chip.Text(amount.toString)
      case ClMsg.Marker           => Chip.Control
    }
  }
}

object ClAction {
  given Inspect[ClAction] = new Inspect[ClAction] {
    def label(a: ClAction): String = a match {
      case ClAction.Spend(to, amount) => s"Spend $amount to ${to.value}"
      case ClAction.Initiate          => "Initiate"
    }
    def fields(a: ClAction): List[(String, String)] = a match {
      case ClAction.Spend(to, amount) =>
        List("to" -> to.value.toString, "amount" -> amount.toString)
      case ClAction.Initiate => Nil
    }
  }
}

object ClOutput {
  given Inspect[ClOutput] = new Inspect[ClOutput] {
    def label(o: ClOutput): String = o match {
      case ClOutput.Snapshot(balance, channels) =>
        s"saved $balance, in transit ${channels.flatMap(_.amounts).sum}"
    }
    def fields(o: ClOutput): List[(String, String)] = o match {
      case ClOutput.Snapshot(balance, channels) =>
        ("balance" -> balance.toString) :: channels.map { c =>
          s"channel ${c.from.value}" -> c.amounts.mkString(", ")
        }
    }
  }
}
