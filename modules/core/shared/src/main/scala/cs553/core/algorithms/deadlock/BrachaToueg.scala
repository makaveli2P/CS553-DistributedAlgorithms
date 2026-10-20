package cs553.core.algorithms.deadlock

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

import scala.annotation.tailrec
import scala.collection.immutable.SortedMap
import scala.collection.immutable.SortedSet
import scala.util.Try

/** The messages of Bracha-Toueg. `Notify` goes along a wait-for edge, and `Done` comes back.
  * `Grant` goes against a wait-for edge, and `Ack` comes back.
  */
enum BtMsg derives Codec.AsObject {
  case Notify, Done, Grant, Ack
}

/** The spontaneous action of the initiator: it starts the notify phase. */
enum BtAction derives Codec.AsObject {
  case Wake
}

/** The decision of the initiator. */
enum BtOutput derives Codec.AsObject {

  /** The initiator became free: it is not deadlocked. */
  case NotDeadlocked

  /** The initiator did not become free: it is deadlocked. */
  case Deadlocked
}

/** The notify phase of one node. */
enum NotifyPhase derives Codec.AsObject {

  /** The node did not get a `Notify` yet. */
  case Idle

  /** The node sent `Notify` to each node that it waits for. `parent` is the node of the first
    * `Notify` (none for the initiator). `dones` counts the `Done` messages that must still come.
    */
  case Notifying(parent: Option[NodeId], dones: Int)

  /** The node got all its `Done` messages and all the `Ack` messages of its grant, and it sent
    * `Done` to its parent (or the initiator decided).
    */
  case Notified
}

/** The grant phase of one node. */
enum GrantPhase derives Codec.AsObject {

  /** The node still needs grants. */
  case Blocked

  /** The node is free and sent `Grant` to each node that waits for it. `acks` counts the `Ack`
    * messages that must still come. `ackTo` is the node whose `Grant` freed this node: it gets its
    * `Ack` when the grant is complete.
    */
  case Granting(acks: Int, ackTo: Option[NodeId])

  /** The node is free, and its grant is complete. */
  case Free
}

/** The state of one node. `waitsFor` is Out (the wait-for edges of the node) and `waitedBy` is In.
  * The node needs `need` grants from its `waitsFor.size` out-neighbours ("N of M"). `requests`
  * counts the grants that it still needs.
  */
final case class BtState(
    role: Role,
    waitsFor: List[NodeId],
    waitedBy: List[NodeId],
    need: Int,
    requests: Int,
    notifyPhase: NotifyPhase,
    grantPhase: GrantPhase
) derives Codec.AsObject {

  def isFree: Boolean = grantPhase match {
    case GrantPhase.Blocked                          => false
    case GrantPhase.Granting(_, _) | GrantPhase.Free => true
  }
}

/** The Bracha-Toueg deadlock detection algorithm for N-out-of-M requests (Fokkink, section 5.2).
  * See `docs/algorithms/bracha-toueg.md`.
  */
object BrachaToueg {

  val id: AlgorithmId = AlgorithmId("bracha-toueg")

  /** The attributes that `check` writes: the wait-for out-neighbours and in-neighbours of the node,
    * as node ids that a space separates. The channels of the run go in both directions of each
    * wait-for edge, so the node cannot see the wait-for edges in its `NodeCtx`.
    */
  val waitsForKey: String = "bt-waits-for"
  val waitedByKey: String = "bt-waited-by"

  /** The rule ids. Each one names one case of a handler. */
  object Rules {
    val wake: RuleId = RuleId("wake")
    val firstNotify: RuleId = RuleId("first-notify")
    val repeatNotify: RuleId = RuleId("repeat-notify")
    val countDone: RuleId = RuleId("count-done")
    val countGrant: RuleId = RuleId("count-grant")
    val lastGrant: RuleId = RuleId("last-grant")
    val extraGrant: RuleId = RuleId("extra-grant")
    val countAck: RuleId = RuleId("count-ack")
    val lastAck: RuleId = RuleId("last-ack")
  }

  /** The pure oracle: the nodes that a fixpoint reduction of the wait-for graph frees. `waits`
    * gives, for each node, its out-neighbours and N. A node becomes free when N of its
    * out-neighbours are free. The reduction repeats until nothing changes.
    */
  def freeNodes(waits: Map[NodeId, (List[NodeId], Int)]): SortedSet[NodeId] = {
    @tailrec
    def loop(free: SortedSet[NodeId]): SortedSet[NodeId] = {
      val next = SortedSet.from(waits.collect {
        case (node, (out, need)) if out.count(free.contains) >= need => node
      })
      if next == free then { free }
      else { loop(next) }
    }
    loop(SortedSet.empty)
  }

  /** The nodes that the fixpoint reduction does not free: the deadlocked nodes. */
  def deadlockedNodes(waits: Map[NodeId, (List[NodeId], Int)]): SortedSet[NodeId] = {
    SortedSet.from(waits.keys) -- freeNodes(waits)
  }

  /** The wait-for graph in the node states. */
  def waitsOf(states: Map[NodeId, BtState]): Map[NodeId, (List[NodeId], Int)] = {
    states.map { case (node, s) => node -> (s.waitsFor, s.need) }
  }

  /** The invariant that the web checks at each step: the count of needed grants stays in `0..N`,
    * and only a node that the fixpoint reduction frees can be free.
    */
  def invariant(w: World[BtState, BtMsg]): Option[String] = {
    val free = freeNodes(waitsOf(w.states))
    w.states.toList.collectFirst {
      case (node, s) if s.requests < 0 || s.requests > s.need =>
        s"Node ${node.value} needs ${s.requests} more grants, which is not in 0 to ${s.need}."
      case (node, s) if s.isFree && !free.contains(node) =>
        s"Node ${node.value} is free, but the wait-for graph cannot free it."
    }
  }

  private type Result = Either[ProtocolError, Step[BtState, BtMsg, BtOutput]]
  private type Errors = NonEmptyList[TopologyError]

  private def ids(nodes: Iterable[NodeId]): String = nodes.map(_.value).mkString(" ")

  /** The wait-for graph of a topology that `check` did not prepare: for each node, its
    * out-neighbours and N. N is the attribute `request`, or M when the attribute is absent.
    */
  def waitsOfTopology(topology: Topology): Either[Errors, Map[NodeId, (List[NodeId], Int)]] = {
    val results = topology.nodes.toList.map { node =>
      val out = topology.outNeighbours(node).toList
      topology.attrsOf(node).int(node, NodeAttrs.Keys.request).flatMap {
        case None                                        => Right(node -> (out, out.size))
        case Some(need) if need >= 0 && need <= out.size => Right(node -> (out, need))
        case Some(need)                                  =>
          Left(
            TopologyError.Requirement(
              s"node ${node.value} requests $need of ${out.size}; N must be in 0 to ${out.size}"
            )
          )
      }
    }
    NonEmptyList.fromList(results.collect { case Left(e) => e }) match {
      case Some(errors) => Left(errors)
      case None         => Right(results.collect { case Right(pair) => pair }.toMap)
    }
  }

  private def prepared(topology: Topology): Boolean = {
    topology.nodes.forall(n => topology.attrsOf(n).extra.contains(waitsForKey))
  }

  private def parseIds(
      node: NodeId,
      key: String,
      text: String
  ): Either[TopologyError, List[NodeId]] = {
    text
      .split(" ")
      .toList
      .filter(_.nonEmpty)
      .foldRight[Either[TopologyError, List[NodeId]]](
        Right(Nil)
      ) { (part, acc) =>
        for {
          rest <- acc
          value <- Try(part.toInt).toOption.toRight(TopologyError.BadAttribute(node, key, text))
        } yield NodeId(value) :: rest
      }
  }

  private def list(node: NodeId, attrs: NodeAttrs, key: String) = {
    attrs.extra
      .get(key)
      .toRight(TopologyError.Requirement(s"node ${node.value} has no $key; run check first"))
      .flatMap(parseIds(node, key, _))
  }

  val protocol: Protocol[BtState, BtMsg, BtAction, BtOutput] =
    new Protocol[BtState, BtMsg, BtAction, BtOutput] {
      val id: AlgorithmId = BrachaToueg.id
      val order: OrderPolicy = OrderPolicy.Default(ChannelOrder.Fifo)

      /** A directed wait-for graph with exactly one initiator and a valid N at each node. The
        * result has a channel in both directions of each wait-for edge. The wait-for edges go into
        * the node attributes, and In comes from Out, so In and Out always agree.
        */
      override def check(topology: Topology): Either[Errors, Topology] = {
        if prepared(topology) then { Right(topology) }
        else {
          val initiators = topology.initiators.toList
          val single =
            if initiators.size == 1 then { Nil }
            else {
              val found = initiators.map(_.value).mkString(", ")
              List(
                TopologyError.Requirement(
                  s"Bracha-Toueg needs exactly one initiator, not ${initiators.size} ($found)"
                )
              )
            }
          val waits = waitsOfTopology(topology)
          val errors = single ++ waits.left.toOption.toList.flatMap(_.toList)
          (NonEmptyList.fromList(errors), waits) match {
            case (Some(errs), _)    => Left(errs)
            case (None, Left(errs)) => Left(errs)
            case (None, Right(w))   =>
              val updates = topology.nodes.toList.map { node =>
                val attrs = topology.attrsOf(node)
                val need = w.get(node).map(_._2).getOrElse(0)
                node -> attrs
                  .withExtra(waitsForKey, ids(topology.outNeighbours(node)))
                  .withExtra(waitedByKey, ids(topology.inNeighbours(node)))
                  .withExtra(NodeAttrs.Keys.request, need.toString)
              }.toMap
              topology.withAttrs(updates).map(_.symmetric)
          }
        }
      }

      def init(ctx: NodeCtx, attrs: NodeAttrs): Either[TopologyError, BtState] = {
        for {
          out <- list(ctx.self, attrs, waitsForKey)
          in <- list(ctx.self, attrs, waitedByKey)
          need <- attrs.int(ctx.self, NodeAttrs.Keys.request).map(_.getOrElse(out.size))
          _ <- {
            val wired = out.forall(n => ctx.out.contains(n) && ctx.in.contains(n)) &&
              in.forall(n => ctx.out.contains(n) && ctx.in.contains(n))
            if wired && need >= 0 && need <= out.size then { Right(()) }
            else {
              Left(
                TopologyError.Requirement(
                  s"node ${ctx.self.value} needs a channel in both directions of each wait-for " +
                    s"edge, and N in 0 to ${out.size}"
                )
              )
            }
          }
        } yield BtState(attrs.role, out, in, need, need, NotifyPhase.Idle, GrantPhase.Blocked)
      }

      def actions(ctx: NodeCtx, state: BtState): List[BtAction] = {
        state.notifyPhase match {
          case NotifyPhase.Idle =>
            state.role match {
              case Role.Initiator    => List(BtAction.Wake)
              case Role.NonInitiator => Nil
            }
          case NotifyPhase.Notifying(_, _) | NotifyPhase.Notified => Nil
        }
      }

      def onAction(ctx: NodeCtx, state: BtState, action: BtAction): Result = {
        action match {
          case BtAction.Wake =>
            actions(ctx, state) match {
              case Nil =>
                Left(
                  ProtocolError.IllegalAction(
                    s"Wake is not legal for a ${state.role} in phase ${state.notifyPhase}"
                  )
                )
              case _ :: _ =>
                val (notified, sends) = startNotify(state, None)
                Right(finish(notified, sends, Rules.wake))
            }
        }
      }

      def onMessage(ctx: NodeCtx, state: BtState, from: NodeId, msg: BtMsg): Result = {
        msg match {
          case BtMsg.Notify => onNotify(state, from)
          case BtMsg.Done   => onDone(state, from)
          case BtMsg.Grant  => onGrant(state, from)
          case BtMsg.Ack    => onAck(state, from)
        }
      }
    }

  /** `notify`: send `Notify` to each node of Out, and start the grant when no grant is needed. */
  private def startNotify(
      state: BtState,
      parent: Option[NodeId]
  ): (BtState, List[Send[BtMsg]]) = {
    val notifies = state.waitsFor.map(Send(_, BtMsg.Notify))
    val notifying = state.copy(notifyPhase = NotifyPhase.Notifying(parent, state.waitsFor.size))
    if notifying.requests == 0 then {
      val (granted, grants) = startGrant(notifying, None)
      (granted, notifies ++ grants)
    } else { (notifying, notifies) }
  }

  /** `grant`: send `Grant` to each node of In. The grant runs at most once for each node, so a
    * second call gives no messages.
    */
  private def startGrant(
      state: BtState,
      ackTo: Option[NodeId]
  ): (BtState, List[Send[BtMsg]]) = {
    state.grantPhase match {
      case GrantPhase.Blocked =>
        if state.waitedBy.isEmpty then {
          (state.copy(grantPhase = GrantPhase.Free), ackTo.map(Send(_, BtMsg.Ack)).toList)
        } else {
          (
            state.copy(grantPhase = GrantPhase.Granting(state.waitedBy.size, ackTo)),
            state.waitedBy.map(Send(_, BtMsg.Grant))
          )
        }
      case GrantPhase.Granting(_, _) | GrantPhase.Free => (state, Nil)
    }
  }

  /** The end of `notify`: when all `Done` messages came and no grant waits for an `Ack`, the node
    * sends `Done` to its parent. The initiator decides instead.
    */
  private def finish(
      state: BtState,
      sends: List[Send[BtMsg]],
      rule: RuleId
  ): Step[BtState, BtMsg, BtOutput] = {
    val granting = state.grantPhase match {
      case GrantPhase.Granting(_, _)            => true
      case GrantPhase.Blocked | GrantPhase.Free => false
    }
    state.notifyPhase match {
      case NotifyPhase.Notifying(parent, dones) =>
        if dones > 0 || granting then { Step(state, sends, None, rule) }
        else {
          val done = state.copy(notifyPhase = NotifyPhase.Notified)
          parent match {
            case Some(p) => Step(done, sends :+ Send(p, BtMsg.Done), None, rule)
            case None    =>
              val verdict = if done.isFree then { BtOutput.NotDeadlocked }
              else { BtOutput.Deadlocked }
              Step(done, sends, Some(verdict), rule)
          }
        }
      case NotifyPhase.Idle | NotifyPhase.Notified => Step(state, sends, None, rule)
    }
  }

  private def onNotify(state: BtState, from: NodeId): Result = {
    if !state.waitedBy.contains(from) then {
      Left(ProtocolError.UnexpectedMessage(from, "Notify comes only from a node that waits"))
    } else {
      state.notifyPhase match {
        case NotifyPhase.Idle =>
          state.role match {
            case Role.NonInitiator =>
              val (notified, sends) = startNotify(state, Some(from))
              Right(finish(notified, sends, Rules.firstNotify))
            case Role.Initiator =>
              Left(ProtocolError.UnexpectedMessage(from, "the initiator got Notify before Wake"))
          }
        case NotifyPhase.Notifying(_, _) | NotifyPhase.Notified =>
          Right(Step(state, List(Send(from, BtMsg.Done)), None, Rules.repeatNotify))
      }
    }
  }

  private def onDone(state: BtState, from: NodeId): Result = {
    val unexpected = Left(ProtocolError.UnexpectedMessage(from, "the node expects no Done"))
    if !state.waitsFor.contains(from) then { unexpected }
    else {
      state.notifyPhase match {
        case NotifyPhase.Notifying(parent, dones) =>
          if dones > 0 then {
            val counted = state.copy(notifyPhase = NotifyPhase.Notifying(parent, dones - 1))
            Right(finish(counted, Nil, Rules.countDone))
          } else { unexpected }
        case NotifyPhase.Idle | NotifyPhase.Notified => unexpected
      }
    }
  }

  private def onGrant(state: BtState, from: NodeId): Result = {
    if !state.waitsFor.contains(from) then {
      Left(ProtocolError.UnexpectedMessage(from, "Grant comes only from a node that it waits for"))
    } else if state.requests > 1 then {
      val counted = state.copy(requests = state.requests - 1)
      Right(Step(counted, List(Send(from, BtMsg.Ack)), None, Rules.countGrant))
    } else if state.requests == 1 then {
      // The last needed grant frees the node. The Ack waits until the grant of the node is complete.
      val (granted, sends) = startGrant(state.copy(requests = 0), Some(from))
      Right(finish(granted, sends, Rules.lastGrant))
    } else {
      // An N-of-M node can get more grants than it needs. It acknowledges each one at once.
      Right(Step(state, List(Send(from, BtMsg.Ack)), None, Rules.extraGrant))
    }
  }

  private def onAck(state: BtState, from: NodeId): Result = {
    val unexpected = Left(ProtocolError.UnexpectedMessage(from, "the node expects no Ack"))
    if !state.waitedBy.contains(from) then { unexpected }
    else {
      state.grantPhase match {
        case GrantPhase.Granting(acks, ackTo) =>
          if acks > 1 then {
            val counted = state.copy(grantPhase = GrantPhase.Granting(acks - 1, ackTo))
            Right(Step(counted, Nil, None, Rules.countAck))
          } else {
            val free = state.copy(grantPhase = GrantPhase.Free)
            Right(finish(free, ackTo.map(Send(_, BtMsg.Ack)).toList, Rules.lastAck))
          }
        case GrantPhase.Blocked | GrantPhase.Free => unexpected
      }
    }
  }
}

object BtState {
  given Inspect[BtState] = new Inspect[BtState] {

    /** Two lines: the notify phase and the grant phase. */
    def label(s: BtState): String = s"${notifyName(s.notifyPhase)}, ${grantName(s)}"

    private def notifyName(phase: NotifyPhase): String = phase match {
      case NotifyPhase.Idle            => "idle"
      case NotifyPhase.Notifying(_, _) => "notifying"
      case NotifyPhase.Notified        => "notified"
    }

    private def grantName(s: BtState): String = s.grantPhase match {
      case GrantPhase.Blocked =>
        if s.requests == 1 then { "needs 1 grant" }
        else { s"needs ${s.requests} grants" }
      case GrantPhase.Granting(_, _) => "granting"
      case GrantPhase.Free           => "free"
    }

    def fields(s: BtState): List[(String, String)] = {
      val role = s.role match {
        case Role.Initiator    => "initiator"
        case Role.NonInitiator => "not an initiator"
      }
      val waiting = s.notifyPhase match {
        case NotifyPhase.Notifying(parent, dones) =>
          List(
            "parent" -> parent.fold("none")(p => s"node ${p.value}"),
            "Done to come" -> dones.toString
          )
        case NotifyPhase.Idle | NotifyPhase.Notified => Nil
      }
      val acks = s.grantPhase match {
        case GrantPhase.Granting(count, ackTo) =>
          List(
            "Ack to come" -> count.toString,
            "Ack after the grant" -> ackTo.fold("none")(p => s"node ${p.value}")
          )
        case GrantPhase.Blocked | GrantPhase.Free => Nil
      }
      List(
        "role" -> role,
        "request" -> s"${s.need} of ${s.waitsFor.size}",
        "waits for" -> nodes(s.waitsFor),
        "waited for by" -> nodes(s.waitedBy),
        "notify" -> notifyName(s.notifyPhase),
        "grant" -> grantName(s)
      ) ++ waiting ++ acks
    }

    private def nodes(list: List[NodeId]): String = {
      if list.isEmpty then { "none" }
      else { list.map(_.value).mkString(", ") }
    }
  }

  /** The "N of M" badge of each node, the removed wait-for edges of each free node, the fill of
    * each free node and, after the decision, the deadlocked set.
    */
  given Overlay[BtState] = new Overlay[BtState] {
    def marks(states: SortedMap[NodeId, BtState]): List[Mark] = {
      val list = states.toList
      val badges = list.map { case (node, s) =>
        Mark.Badge(node, s"${s.need} of ${s.waitsFor.size}")
      }
      val removed = list.flatMap { case (node, s) =>
        if s.isFree then { s.waitedBy.map(Mark.OnEdge(_, node, EdgeStyle.Removed)) }
        else { Nil }
      }
      val free = list.collect { case (node, s) if s.isFree => Mark.Fill(node, NodeFill.Free) }
      val decided = list.exists { case (_, s) =>
        s.role == Role.Initiator && s.notifyPhase == NotifyPhase.Notified
      }
      val deadlocked =
        if decided then {
          list.collect {
            case (node, s) if !s.isFree && s.notifyPhase != NotifyPhase.Idle => node
          }
        } else { Nil }
      // The thumbnail fills the Chosen nodes. The canvas shows the last fill: Deadlocked.
      val verdict = deadlocked.flatMap { node =>
        List(Mark.Fill(node, NodeFill.Chosen), Mark.Fill(node, NodeFill.Deadlocked))
      }
      badges ++ removed ++ free ++ verdict
    }
  }
}

object BtMsg {
  given Inspect[BtMsg] = new Inspect[BtMsg] {
    def label(m: BtMsg): String = m match {
      case BtMsg.Notify => "Notify"
      case BtMsg.Done   => "Done"
      case BtMsg.Grant  => "Grant"
      case BtMsg.Ack    => "Ack"
    }
    def fields(m: BtMsg): List[(String, String)] = Nil
  }
}

object BtAction {
  given Inspect[BtAction] = new Inspect[BtAction] {
    def label(a: BtAction): String = a match {
      case BtAction.Wake => "Wake"
    }
    def fields(a: BtAction): List[(String, String)] = Nil
  }
}

object BtOutput {
  given Inspect[BtOutput] = new Inspect[BtOutput] {
    def label(o: BtOutput): String = o match {
      case BtOutput.NotDeadlocked => "the initiator is not deadlocked"
      case BtOutput.Deadlocked    => "the initiator is deadlocked"
    }
    def fields(o: BtOutput): List[(String, String)] = Nil
  }
}
