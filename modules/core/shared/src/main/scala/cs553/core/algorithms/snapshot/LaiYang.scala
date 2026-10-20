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

/** The color of a node and of each transfer that it sends. A node is white before its local
  * snapshot and red after it. A transfer has the color of its sender at the time of the send.
  */
enum LyColor derives Codec.AsObject {
  case White, Red
}

/** The messages of Lai-Yang. */
enum LyMsg derives Codec.AsObject {

  /** Money from the sender to the receiver, with the color of the sender at the send. */
  case Transfer(amount: Int, color: LyColor)

  /** The control message of a snapshot: the number of white transfers that the sender sent on this
    * channel before its snapshot.
    */
  case Control(preSnapshotCount: Int)
}

/** The spontaneous actions of a node. */
enum LyAction derives Codec.AsObject {

  /** An initiator takes its local snapshot. */
  case Initiate

  /** The node sends `amount` to the out-neighbour `to`. Each spend uses one unit of the budget. */
  case Spend(to: NodeId, amount: Int)
}

/** The decision of one node: its local snapshot and the recorded content of each in-channel. */
enum LyOutput derives Codec.AsObject {
  case Recorded(balance: Int, channels: SortedMap[NodeId, List[Int]])
}

/** Why a node took its local snapshot. */
enum LyTrigger derives Codec.AsObject {

  /** The node is an initiator and did `Initiate`. */
  case Initiated

  /** The node got a red transfer while it was white. */
  case RedTransfer

  /** The node got a control message while it was white. */
  case ControlMessage
}

/** The local snapshot of one node. `balance` is the balance at the snapshot. `expected` holds the
  * count of each control message that arrived, by sender. `channels` holds the white transfers that
  * arrived after the snapshot, by sender, oldest first: that is the recorded channel state.
  */
final case class LyRecord(
    trigger: LyTrigger,
    balance: Int,
    expected: SortedMap[NodeId, Int],
    channels: SortedMap[NodeId, List[Int]]
) derives Codec.AsObject

/** The snapshot phase of one node. */
enum LyPhase derives Codec.AsObject {

  /** The node did not take its local snapshot. Its color is white. */
  case White

  /** The node took its local snapshot and records one or more in-channels. Its color is red. */
  case Recording(record: LyRecord)

  /** The node recorded each in-channel and decided. Its color stays red. */
  case Done(record: LyRecord)
}

/** The state of one node. `sentWhite` counts the white transfers on each out-channel.
  * `receivedWhite` counts the white transfers from each in-neighbour, before and after the
  * snapshot. Both maps hold a key for each neighbour from `init`.
  */
final case class LyState(
    role: Role,
    balance: Int,
    budget: Int,
    sentWhite: SortedMap[NodeId, Int],
    receivedWhite: SortedMap[NodeId, Int],
    phase: LyPhase
) derives Codec.AsObject {

  def color: LyColor = phase match {
    case LyPhase.White                          => LyColor.White
    case LyPhase.Recording(_) | LyPhase.Done(_) => LyColor.Red
  }

  def record: Option[LyRecord] = phase match {
    case LyPhase.White             => None
    case LyPhase.Recording(record) => Some(record)
    case LyPhase.Done(record)      => Some(record)
  }

  /** True when the control message from `from` arrived and each white transfer that it counts
    * arrived too.
    */
  def closed(from: NodeId): Boolean = {
    record.exists(_.expected.get(from).contains(receivedWhite.getOrElse(from, 0)))
  }
}

/** The Lai-Yang snapshot algorithm (Fokkink, section 3.2) on a bank application. See
  * `docs/algorithms/lai-yang.md`.
  */
object LaiYang {

  val id: AlgorithmId = AlgorithmId("lai-yang")

  /** The balance of a node that has no `balance` attribute. */
  val defaultBalance: Int = 10

  /** The transfer budget of a node that has no `budget` attribute. */
  val defaultBudget: Int = 2

  /** The amounts that a node can spend in one transfer, when its balance allows it. */
  val amounts: List[Int] = List(1, 5)

  /** The rule ids. Each one names one case of a handler. */
  object Rules {
    val initiate: RuleId = RuleId("initiate")
    val spendWhite: RuleId = RuleId("spend-white")
    val spendRed: RuleId = RuleId("spend-red")
    val receiveWhite: RuleId = RuleId("receive-white")
    val receiveRed: RuleId = RuleId("receive-red")
    val snapshotOnRed: RuleId = RuleId("snapshot-on-red")
    val recordWhite: RuleId = RuleId("record-white")
    val snapshotOnControl: RuleId = RuleId("snapshot-on-control")
    val countControl: RuleId = RuleId("count-control")
  }

  private type Result = Either[ProtocolError, Step[LyState, LyMsg, LyOutput]]

  /** The initial balance and budget of a node from its attributes. */
  def bank(node: NodeId, attrs: NodeAttrs): Either[TopologyError, (Int, Int)] = {
    for {
      balance <- attrs.int(node, NodeAttrs.Keys.balance)
      budget <- attrs.int(node, NodeAttrs.Keys.budget)
      b <- nonNegative(node, NodeAttrs.Keys.balance, balance.getOrElse(defaultBalance))
      t <- nonNegative(node, NodeAttrs.Keys.budget, budget.getOrElse(defaultBudget))
    } yield (b, t)
  }

  private def nonNegative(node: NodeId, key: String, value: Int): Either[TopologyError, Int] = {
    if value >= 0 then { Right(value) }
    else { Left(TopologyError.BadAttribute(node, key, value.toString)) }
  }

  /** The sum of the initial balances, or `None` when an attribute is not valid. */
  def initialTotal(topology: Topology): Option[Int] = {
    topology.nodes.toList
      .map(n => bank(n, topology.attrsOf(n)).map(_._1))
      .foldLeft(Option(0)) { (acc, b) => acc.flatMap(sum => b.toOption.map(sum + _)) }
  }

  /** The money that is on the way in the buffered transfers. */
  private def inTransit(w: World[LyState, LyMsg]): Int = w.buffered
    .map(_.msg)
    .map {
      case LyMsg.Transfer(amount, _) => amount
      case LyMsg.Control(_)          => 0
    }
    .sum

  /** The sum of the recorded balances and the recorded channel contents, when each node is done.
    */
  def snapshotTotal(states: Iterable[LyState]): Option[Int] = {
    val done = states.toList.map(_.phase).collect { case LyPhase.Done(record) => record }
    Option.when(done.size == states.size) {
      done.map(r => r.balance + r.channels.values.flatten.sum).sum
    }
  }

  /** The invariant that the web checks at each step: the balances and the transfers in transit hold
    * the initial total, and a complete snapshot holds the initial total too.
    */
  def invariant(w: World[LyState, LyMsg]): Option[String] = {
    initialTotal(w.topology).flatMap { total =>
      val live = w.states.values.map(_.balance).sum + inTransit(w)
      if live != total then {
        Some(s"Total $live is not $total: a transfer was lost.")
      } else {
        snapshotTotal(w.states.values).filter(_ != total).map { recorded =>
          s"The snapshot total $recorded is not $total: the cut is not consistent."
        }
      }
    }
  }

  val protocol: Protocol[LyState, LyMsg, LyAction, LyOutput] =
    new Protocol[LyState, LyMsg, LyAction, LyOutput] {
      val id: AlgorithmId = LaiYang.id
      val order: OrderPolicy = OrderPolicy.Default(ChannelOrder.AnyOrder)

      /** A strongly connected directed graph with two or more nodes and one or more initiators. */
      override def check(topology: Topology): Either[NonEmptyList[TopologyError], Topology] = {
        val shape =
          if topology.nodes.size >= 2 && topology.isStronglyConnected then { Nil }
          else {
            List(
              TopologyError.Requirement(
                "Lai-Yang needs a strongly connected directed graph with two or more nodes"
              )
            )
          }
        val initiators =
          if topology.initiators.nonEmpty then { Nil }
          else { List(TopologyError.Requirement("Lai-Yang needs one or more initiators")) }
        NonEmptyList.fromList(shape ++ initiators) match {
          case Some(errors) => Left(errors)
          case None         => Right(topology)
        }
      }

      def init(ctx: NodeCtx, attrs: NodeAttrs): Either[TopologyError, LyState] = {
        bank(ctx.self, attrs).map { case (balance, budget) =>
          LyState(
            attrs.role,
            balance,
            budget,
            SortedMap.from(ctx.out.toList.map(_ -> 0)),
            SortedMap.from(ctx.in.toList.map(_ -> 0)),
            LyPhase.White
          )
        }
      }

      def actions(ctx: NodeCtx, state: LyState): List[LyAction] = {
        val initiate = (state.role, state.phase) match {
          case (Role.Initiator, LyPhase.White) => List(LyAction.Initiate)
          case (Role.Initiator, LyPhase.Recording(_) | LyPhase.Done(_)) | (Role.NonInitiator, _) =>
            Nil
        }
        val spends =
          if state.budget > 0 then {
            for {
              to <- ctx.out.toList
              amount <- amounts if amount <= state.balance
            } yield LyAction.Spend(to, amount)
          } else { Nil }
        initiate ++ spends
      }

      def onAction(ctx: NodeCtx, state: LyState, action: LyAction): Result = {
        action match {
          case LyAction.Initiate =>
            (state.role, state.phase) match {
              case (Role.Initiator, LyPhase.White) =>
                val (after, controls) = snapshot(ctx, state, LyTrigger.Initiated)
                Right(Step(after, controls, None, Rules.initiate))
              case (Role.Initiator, LyPhase.Recording(_) | LyPhase.Done(_)) |
                  (Role.NonInitiator, _) =>
                Left(
                  ProtocolError.IllegalAction(s"Initiate is not legal at node ${ctx.self.value}")
                )
            }
          case LyAction.Spend(to, amount) => spend(ctx, state, to, amount)
        }
      }

      def onMessage(ctx: NodeCtx, state: LyState, from: NodeId, msg: LyMsg): Result = {
        msg match {
          case LyMsg.Transfer(amount, color) => onTransfer(ctx, state, from, amount, color)
          case LyMsg.Control(count)          => onControl(ctx, state, from, count)
        }
      }
    }

  /** The local snapshot: the node records its balance, becomes red and sends one control message on
    * each out-channel with the count of white transfers on that channel.
    */
  private def snapshot(
      ctx: NodeCtx,
      state: LyState,
      trigger: LyTrigger
  ): (LyState, List[Send[LyMsg]]) = {
    val record = LyRecord(trigger, state.balance, SortedMap.empty, SortedMap.empty)
    val controls = ctx.out.toList.map { to =>
      Send(to, LyMsg.Control(state.sentWhite.getOrElse(to, 0)))
    }
    (state.copy(phase = LyPhase.Recording(record)), controls)
  }

  /** The state after a change to the record: `Done` with a decision when each in-channel is closed.
    */
  private def settle(ctx: NodeCtx, state: LyState): (LyState, Option[LyOutput]) = {
    state.phase match {
      case LyPhase.Recording(record) if ctx.in.forall(state.closed) =>
        (
          state.copy(phase = LyPhase.Done(record)),
          Some(LyOutput.Recorded(record.balance, record.channels))
        )
      case LyPhase.White | LyPhase.Recording(_) | LyPhase.Done(_) => (state, None)
    }
  }

  private def spend(ctx: NodeCtx, state: LyState, to: NodeId, amount: Int): Result = {
    if !ctx.out.contains(to) then { Left(ProtocolError.NotANeighbour(to)) }
    else if state.budget <= 0 || amount <= 0 || amount > state.balance then {
      Left(
        ProtocolError.IllegalAction(
          s"node ${ctx.self.value} cannot spend $amount with balance ${state.balance} and " +
            s"budget ${state.budget}"
        )
      )
    } else {
      val paid = state.copy(balance = state.balance - amount, budget = state.budget - 1)
      val (after, rule) = state.color match {
        case LyColor.White =>
          (paid.copy(sentWhite = bump(paid.sentWhite, to)), Rules.spendWhite)
        case LyColor.Red => (paid, Rules.spendRed)
      }
      Right(Step(after, List(Send(to, LyMsg.Transfer(amount, state.color))), None, rule))
    }
  }

  private def bump(counts: SortedMap[NodeId, Int], key: NodeId): SortedMap[NodeId, Int] = {
    counts.updated(key, counts.getOrElse(key, 0) + 1)
  }

  private def onTransfer(
      ctx: NodeCtx,
      state: LyState,
      from: NodeId,
      amount: Int,
      color: LyColor
  ): Result = {
    val credited = state.copy(balance = state.balance + amount)
    (state.phase, color) match {
      case (LyPhase.White, LyColor.White) =>
        val after = credited.copy(receivedWhite = bump(state.receivedWhite, from))
        Right(Step(after, Nil, None, Rules.receiveWhite))
      case (LyPhase.White, LyColor.Red) =>
        // The snapshot comes first, so the recorded balance does not hold the red transfer.
        val (red, controls) = snapshot(ctx, state, LyTrigger.RedTransfer)
        Right(Step(red.copy(balance = red.balance + amount), controls, None, Rules.snapshotOnRed))
      case (LyPhase.Recording(record), LyColor.White) =>
        val received = state.receivedWhite.getOrElse(from, 0) + 1
        if record.expected.get(from).exists(received > _) then {
          Left(tooMany(from, record.expected.getOrElse(from, 0)))
        } else {
          val recorded = record.copy(channels =
            record.channels.updated(from, record.channels.getOrElse(from, Nil) :+ amount)
          )
          val (after, decision) = settle(
            ctx,
            credited.copy(
              receivedWhite = state.receivedWhite.updated(from, received),
              phase = LyPhase.Recording(recorded)
            )
          )
          Right(Step(after, Nil, decision, Rules.recordWhite))
        }
      case (LyPhase.Done(record), LyColor.White) =>
        Left(tooMany(from, record.expected.getOrElse(from, 0)))
      case (LyPhase.Recording(_) | LyPhase.Done(_), LyColor.Red) =>
        Right(Step(credited, Nil, None, Rules.receiveRed))
    }
  }

  private def tooMany(from: NodeId, expected: Int): ProtocolError = {
    ProtocolError.UnexpectedMessage(
      from,
      s"a white transfer after the control message, which counted $expected white transfers"
    )
  }

  private def onControl(ctx: NodeCtx, state: LyState, from: NodeId, count: Int): Result = {
    val received = state.receivedWhite.getOrElse(from, 0)
    if received > count then { Left(tooMany(from, count)) }
    else {
      state.phase match {
        case LyPhase.White =>
          val (red, controls) = snapshot(ctx, state, LyTrigger.ControlMessage)
          val (after, decision) = settle(ctx, expect(red, from, count))
          Right(Step(after, controls, decision, Rules.snapshotOnControl))
        case LyPhase.Recording(record) =>
          if record.expected.contains(from) then {
            Left(ProtocolError.UnexpectedMessage(from, "a second control message on the channel"))
          } else {
            val (after, decision) = settle(ctx, expect(state, from, count))
            Right(Step(after, Nil, decision, Rules.countControl))
          }
        case LyPhase.Done(_) =>
          Left(ProtocolError.UnexpectedMessage(from, "a control message after the snapshot ended"))
      }
    }
  }

  /** The state with the count of the control message from `from` in the record. */
  private def expect(state: LyState, from: NodeId, count: Int): LyState = state.phase match {
    case LyPhase.Recording(record) =>
      state.copy(phase =
        LyPhase.Recording(record.copy(expected = record.expected.updated(from, count)))
      )
    case LyPhase.White | LyPhase.Done(_) => state
  }
}

object LyColor {
  given Inspect[LyColor] = new Inspect[LyColor] {
    def label(c: LyColor): String = c match {
      case LyColor.White => "white"
      case LyColor.Red   => "red"
    }
    def fields(c: LyColor): List[(String, String)] = List("color" -> label(c))
  }
}

object LyRecord {
  private[snapshot] def channelsText(channels: SortedMap[NodeId, List[Int]]): String = {
    val parts = channels.toList.collect {
      case (from, amounts) if amounts.nonEmpty => s"${from.value}: ${amounts.mkString(" + ")}"
    }
    if parts.isEmpty then { "none" }
    else { parts.mkString(", ") }
  }

  private[snapshot] def countsText(counts: SortedMap[NodeId, Int]): String = {
    if counts.isEmpty then { "none" }
    else { counts.toList.map { case (n, c) => s"${n.value}: $c" }.mkString(", ") }
  }
}

object LyState {

  /** The fills of the white nodes and of the red nodes that still record, a cut mark at each red
    * node, the record state of each in-channel of a red node, an "initiator" badge until the
    * initiator decides, and the snapshot total when each node is done. An initiator that is done
    * gets the `Chosen` fill, which the home thumbnail shows. Like Chandy-Lamport, a node that is
    * done keeps no red fill: the decided fill shows it.
    */
  given Overlay[LyState] = new Overlay[LyState] {
    def marks(states: SortedMap[NodeId, LyState]): List[Mark] = {
      val perNode = states.toList.flatMap { case (node, s) =>
        val role = (s.role, s.phase) match {
          case (Role.Initiator, LyPhase.Done(_)) => Nil
          case (Role.Initiator, _)               => List(Mark.Badge(node, "initiator"))
          case (Role.NonInitiator, _)            => Nil
        }
        val phase = s.phase match {
          case LyPhase.White             => List(Mark.Fill(node, NodeFill.White))
          case LyPhase.Recording(record) =>
            Mark.Fill(node, NodeFill.Red) :: Mark.Cut(node) :: channels(node, s, record)
          case LyPhase.Done(record) =>
            val chosen = record.trigger match {
              case LyTrigger.Initiated => List(Mark.Fill(node, NodeFill.Chosen))
              case LyTrigger.RedTransfer | LyTrigger.ControlMessage => Nil
            }
            (Mark.Cut(node) :: channels(node, s, record)) ++ chosen
        }
        role ++ phase
      }
      val total = Option
        .when(states.nonEmpty)(LaiYang.snapshotTotal(states.values))
        .flatten
        .map(t => Mark.Fact(s"snapshot total $t"))
      perNode ++ total.toList
    }
  }

  private def channels(node: NodeId, s: LyState, record: LyRecord): List[Mark] = {
    s.receivedWhite.keys.toList.map { from =>
      val state = if s.closed(from) then { ChannelRecord.Closed }
      else { ChannelRecord.Recording }
      Mark.Channel(from, node, state, record.channels.getOrElse(from, Nil).map(_.toString))
    }
  }

  given Inspect[LyState] = new Inspect[LyState] {

    /** One line for the live balance, and one line for the saved balance after the snapshot, as in
      * Chandy-Lamport. The fill shows the color.
      */
    def label(s: LyState): String = s.record.fold(s"balance ${s.balance}") { r =>
      s"balance ${s.balance}, saved ${r.balance}"
    }

    def fields(s: LyState): List[(String, String)] = {
      val phase = s.phase match {
        case LyPhase.White        => "white"
        case LyPhase.Recording(_) => "recording"
        case LyPhase.Done(_)      => "done"
      }
      val common = List(
        "phase" -> phase,
        "color" -> Inspect[LyColor].label(s.color),
        "balance" -> s.balance.toString,
        "spends left" -> s.budget.toString,
        "white sent" -> LyRecord.countsText(s.sentWhite),
        "white received" -> LyRecord.countsText(s.receivedWhite)
      )
      val recorded = s.record.toList.flatMap { r =>
        val trigger = r.trigger match {
          case LyTrigger.Initiated      => "Initiate"
          case LyTrigger.RedTransfer    => "a red transfer"
          case LyTrigger.ControlMessage => "a control message"
        }
        List(
          "snapshot by" -> trigger,
          "recorded balance" -> r.balance.toString,
          "control counts" -> LyRecord.countsText(r.expected),
          "recorded channels" -> LyRecord.channelsText(r.channels)
        )
      }
      common ++ recorded
    }
  }
}

object LyMsg {
  given Inspect[LyMsg] = new Inspect[LyMsg] {
    def label(m: LyMsg): String = m match {
      case LyMsg.Transfer(amount, color) =>
        s"Transfer($amount, ${Inspect[LyColor].label(color)})"
      case LyMsg.Control(count) => s"Control($count)"
    }
    def fields(m: LyMsg): List[(String, String)] = m match {
      case LyMsg.Transfer(amount, color) =>
        List("amount" -> amount.toString, "color" -> Inspect[LyColor].label(color))
      case LyMsg.Control(count) => List("white transfers before the snapshot" -> count.toString)
    }
    override def chip(m: LyMsg): Chip = m match {
      case LyMsg.Transfer(amount, LyColor.White) => Chip.Text(amount.toString)
      case LyMsg.Transfer(amount, LyColor.Red)   => Chip.Red(amount.toString)
      case LyMsg.Control(_)                      => Chip.Control
    }
  }
}

object LyAction {
  given Inspect[LyAction] = new Inspect[LyAction] {
    def label(a: LyAction): String = a match {
      case LyAction.Initiate          => "Initiate"
      case LyAction.Spend(to, amount) => s"Spend $amount to ${to.value}"
    }
    def fields(a: LyAction): List[(String, String)] = a match {
      case LyAction.Initiate          => Nil
      case LyAction.Spend(to, amount) =>
        List("to" -> to.value.toString, "amount" -> amount.toString)
    }
  }
}

object LyOutput {
  given Inspect[LyOutput] = new Inspect[LyOutput] {
    def label(o: LyOutput): String = o match {
      case LyOutput.Recorded(balance, channels) =>
        s"saved $balance, in transit ${channels.values.flatten.sum}"
    }
    def fields(o: LyOutput): List[(String, String)] = o match {
      case LyOutput.Recorded(balance, channels) =>
        List("balance" -> balance.toString, "channels" -> LyRecord.channelsText(channels))
    }
  }
}
