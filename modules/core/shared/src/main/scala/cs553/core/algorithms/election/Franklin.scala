package cs553.core.algorithms.election

import cats.data.NonEmptyList
import cs553.core.ids.AlgorithmId
import cs553.core.ids.NodeId
import cs553.core.ids.RuleId
import cs553.core.ids.Uid
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
import cs553.core.trace.Mark
import cs553.core.trace.NodeFill
import cs553.core.trace.Overlay
import cs553.core.world.World
import io.circe.Codec

import scala.collection.immutable.SortedMap

/** The messages of Franklin. */
enum FrMsg derives Codec.AsObject {

  /** The uid of an active node in this round. Passive nodes relay it. */
  case Id(uid: Uid, round: Int)

  /** The announcement of the leader. */
  case Leader(uid: Uid)
}

/** The spontaneous action of an initiator. */
enum FrAction derives Codec.AsObject {
  case Wake
}

/** The decision of one node. */
enum FrOutput derives Codec.AsObject {

  /** The node is the leader. */
  case Elected(uid: Uid)

  /** The node knows the uid of the leader. */
  case Follows(leader: Uid)
}

/** One `Id` message that an active node received and did not use yet: the neighbour that sent it,
  * the uid and the round.
  */
final case class Heard(from: NodeId, uid: Uid, round: Int) derives Codec.AsObject

/** The state of one node. Each case holds only the data that is legal in that phase. */
enum FrState derives Codec.AsObject {

  /** An initiator before `Wake` and before its first message. */
  case Asleep(uid: Uid)

  /** A candidate in `round`. `heard` holds the received `Id` messages of this round and of the next
    * round, at most one for each neighbour and round.
    */
  case Active(uid: Uid, round: Int, heard: List[Heard])

  /** A node that relays each message: each non-initiator, and each defeated candidate. */
  case Passive(uid: Option[Uid])

  /** The leader. Its announcement is on the ring. */
  case Announcing(uid: Uid)

  /** The leader. Its announcement came back. */
  case Leader(uid: Uid)

  /** A node that knows the uid of the leader. */
  case Follower(uid: Option[Uid], leader: Uid)
}

/** The Franklin election on a bidirectional ring (Fokkink, section 9.1), with a leader
  * announcement. Each node decides by itself: no central process runs the rounds. See
  * `docs/algorithms/franklin.md`.
  */
object Franklin {

  val id: AlgorithmId = AlgorithmId("franklin")

  /** The rule ids. Each one names one case of a handler. */
  object Rules {
    val wake: RuleId = RuleId("wake")
    val wakeByMessage: RuleId = RuleId("wake-by-message")
    val record: RuleId = RuleId("record")
    val survive: RuleId = RuleId("survive")
    val defeat: RuleId = RuleId("defeat")
    val elected: RuleId = RuleId("elected")
    val relayId: RuleId = RuleId("relay-id")
    val learnLeader: RuleId = RuleId("learn-leader")
    val announceReturned: RuleId = RuleId("announce-returned")
  }

  /** The maximum number of rounds on a ring of `n` nodes: ⌊log₂ n⌋ + 1. */
  def maxRounds(n: Int): Int = Iterator.iterate(1)(_ * 2).takeWhile(_ <= n).size

  /** The invariant that the web checks at each step: at most one leader, the leader and each
    * follower name the largest initiator uid, and no node is in a round after ⌊log₂ n⌋ + 1.
    */
  def invariant(w: World[FrState, FrMsg]): Option[String] = {
    val largest = w.topology.initiators.toList.flatMap(n => w.topology.attrsOf(n).uid).maxOption
    val limit = maxRounds(w.topology.nodes.size)
    val leaders = w.states.toList.collect {
      case (node, FrState.Announcing(u)) => (node, u)
      case (node, FrState.Leader(u))     => (node, u)
    }
    val tooMany = Option.when(leaders.size > 1) {
      s"Nodes ${leaders.map(_._1.value).mkString(", ")} are all leaders."
    }
    def notLargest(u: Uid): Boolean = !largest.contains(u)
    val wrongLeader = leaders.collectFirst {
      case (_, u) if notLargest(u) =>
        s"The leader has uid ${u.value}, but the largest uid is ${largest.fold("none")(_.value.toString)}."
    }
    val wrongFollower = w.states.toList.collectFirst {
      case (node, FrState.Follower(_, u)) if notLargest(u) =>
        s"Node ${node.value} follows uid ${u.value}, which is not the largest uid."
    }
    val lateRound = w.states.toList.collectFirst {
      case (node, FrState.Active(_, round, _)) if round > limit =>
        s"Node ${node.value} is in round $round, but $limit rounds are enough for this ring."
    }
    tooMany.orElse(wrongLeader).orElse(wrongFollower).orElse(lateRound)
  }

  private type Result = Either[ProtocolError, Step[FrState, FrMsg, FrOutput]]

  val protocol: Protocol[FrState, FrMsg, FrAction, FrOutput] =
    new Protocol[FrState, FrMsg, FrAction, FrOutput] {
      val id: AlgorithmId = Franklin.id

      /** Fifo is necessary: a passive node must not let the round r+1 message of a candidate go
        * before its round r message.
        */
      val order: OrderPolicy = OrderPolicy.Locked(ChannelOrder.Fifo)

      /** A bidirectional ring of three or more nodes, one or more initiators, a uid for each
        * initiator and no uid on two nodes.
        */
      override def check(topology: Topology): Either[NonEmptyList[TopologyError], Topology] = {
        val nodes = topology.nodes.toList
        val badDegree = nodes.filterNot { n =>
          topology.outNeighbours(n).size == 2 && topology.inNeighbours(n) == topology
            .outNeighbours(n)
        }
        val shape =
          if nodes.size >= 3 && badDegree.isEmpty && topology.isConnected then { Nil }
          else {
            List(
              TopologyError.Requirement(
                "Franklin needs a bidirectional ring of three or more nodes: each node has two " +
                  "neighbours, with an edge in each direction, and one cycle holds all nodes"
              )
            )
          }
        val initiators = topology.initiators.toList
        val noInitiator =
          if initiators.isEmpty then {
            List(TopologyError.Requirement("Franklin needs one or more initiators"))
          } else { Nil }
        val missingUid = initiators.filter(n => topology.attrsOf(n).uid.isEmpty).map { n =>
          TopologyError.Requirement(s"the initiator ${n.value} has no uid")
        }
        val uids = nodes.flatMap(n => topology.attrsOf(n).uid.map(_ -> n))
        val duplicates = uids.groupBy(_._1).toList.sortBy(_._1).collect {
          case (uid, owners) if owners.size > 1 =>
            TopologyError.Requirement(
              s"the uid ${uid.value} is on more than one node: ${owners.map(_._2.value).mkString(", ")}"
            )
        }
        NonEmptyList.fromList(shape ++ noInitiator ++ missingUid ++ duplicates) match {
          case Some(errors) => Left(errors)
          case None         => Right(topology)
        }
      }

      def init(ctx: NodeCtx, attrs: NodeAttrs): Either[TopologyError, FrState] = {
        if ctx.out.size != 2 || ctx.in != ctx.out then {
          Left(
            TopologyError.Requirement(
              s"Franklin needs a bidirectional ring, but node ${ctx.self.value} has " +
                s"${ctx.in.size} in-edges and ${ctx.out.size} out-edges"
            )
          )
        } else {
          attrs.role match {
            case Role.NonInitiator => Right(FrState.Passive(attrs.uid))
            case Role.Initiator    =>
              attrs.uid
                .map(FrState.Asleep(_))
                .toRight(TopologyError.Requirement(s"the initiator ${ctx.self.value} has no uid"))
          }
        }
      }

      def actions(ctx: NodeCtx, state: FrState): List[FrAction] = state match {
        case FrState.Asleep(_) => List(FrAction.Wake)
        case FrState.Active(_, _, _) | FrState.Passive(_) | FrState.Announcing(_) |
            FrState.Leader(_) | FrState.Follower(_, _) =>
          Nil
      }

      def onAction(ctx: NodeCtx, state: FrState, action: FrAction): Result = {
        action match {
          case FrAction.Wake =>
            state match {
              case FrState.Asleep(p) =>
                Right(Step(FrState.Active(p, 1, Nil), both(ctx, p, 1), None, Rules.wake))
              case FrState.Active(_, _, _) | FrState.Passive(_) | FrState.Announcing(_) |
                  FrState.Leader(_) | FrState.Follower(_, _) =>
                Left(ProtocolError.IllegalAction(s"Wake is not legal in state $state"))
            }
        }
      }

      def onMessage(ctx: NodeCtx, state: FrState, from: NodeId, msg: FrMsg): Result = {
        msg match {
          case FrMsg.Id(q, round) => onId(ctx, state, from, q, round)
          case FrMsg.Leader(u)    => onLeader(ctx, state, from, u)
        }
      }
    }

  /** The `Id(uid, round)` message to both neighbours. */
  private def both(ctx: NodeCtx, uid: Uid, round: Int): List[Send[FrMsg]] = {
    ctx.out.toList.map(n => Send(n, FrMsg.Id(uid, round)))
  }

  /** The neighbour on the other side of `from`. */
  private def other(ctx: NodeCtx, from: NodeId): Either[ProtocolError, NodeId] = {
    ctx.out.filterNot(_ == from).toList match {
      case List(next) => Right(next)
      case _          =>
        Left(ProtocolError.UnexpectedMessage(from, s"node ${from.value} is not a ring neighbour"))
    }
  }

  private def onId(ctx: NodeCtx, state: FrState, from: NodeId, q: Uid, round: Int): Result = {
    state match {
      case FrState.Asleep(p) =>
        if round != 1 then {
          Left(ProtocolError.UnexpectedMessage(from, s"a sleeping node got a round $round Id"))
        } else {
          settle(ctx, p, 1, List(Heard(from, q, round)), both(ctx, p, 1), Rules.wakeByMessage)
        }
      case FrState.Active(p, current, heard) =>
        val early = round == current + 1
        val repeated = heard.exists(h => h.from == from && h.round == round)
        if (round != current && !early) || repeated then {
          Left(
            ProtocolError.UnexpectedMessage(
              from,
              s"Id(${q.value}, round $round) is not legal in round $current"
            )
          )
        } else {
          settle(ctx, p, current, heard :+ Heard(from, q, round), Nil, Rules.record)
        }
      case FrState.Passive(_) =>
        other(ctx, from).map { next =>
          Step(state, List(Send(next, FrMsg.Id(q, round))), None, Rules.relayId)
        }
      case FrState.Announcing(_) | FrState.Leader(_) | FrState.Follower(_, _) =>
        Left(
          ProtocolError.UnexpectedMessage(from, s"Id(${q.value}) is not legal in state $state")
        )
    }
  }

  /** Compares the uid with both neighbours when the round is complete. A survivor goes to the next
    * round and can complete it at once with the early messages that it holds.
    */
  @scala.annotation.tailrec
  private def settle(
      ctx: NodeCtx,
      p: Uid,
      round: Int,
      heard: List[Heard],
      sends: List[Send[FrMsg]],
      pending: RuleId
  ): Result = {
    val ord = summon[Ordering[Uid]]
    val current = ctx.out.toList.map(n => heard.find(h => h.from == n && h.round == round))
    val later = heard.filterNot(_.round == round)
    if !current.forall(_.isDefined) then {
      Right(Step(FrState.Active(p, round, heard), sends, None, pending))
    } else {
      val uids = current.flatten.map(_.uid)
      if uids.forall(_ == p) && later.isEmpty then {
        ctx.out.headOption match {
          case Some(first) =>
            Right(
              Step(
                FrState.Announcing(p),
                sends :+ Send(first, FrMsg.Leader(p)),
                Some(FrOutput.Elected(p)),
                Rules.elected
              )
            )
          case None =>
            Left(ProtocolError.InvariantBroken(s"node ${ctx.self.value} has no neighbour"))
        }
      } else if uids.contains(p) then {
        Left(ProtocolError.InvariantBroken(s"uid ${p.value} came back on one side only"))
      } else if uids.exists(ord.gt(_, p)) then {
        val relays = later.foldLeft(Right(Nil): Either[ProtocolError, List[Send[FrMsg]]]) {
          case (acc, h) =>
            acc.flatMap(list =>
              other(ctx, h.from).map(n => list :+ Send(n, FrMsg.Id(h.uid, h.round)))
            )
        }
        relays.map(r => Step(FrState.Passive(Some(p)), sends ++ r, None, Rules.defeat))
      } else {
        settle(ctx, p, round + 1, later, sends ++ both(ctx, p, round + 1), Rules.survive)
      }
    }
  }

  private def onLeader(ctx: NodeCtx, state: FrState, from: NodeId, u: Uid): Result = {
    state match {
      case FrState.Passive(own) =>
        other(ctx, from).map { next =>
          Step(
            FrState.Follower(own, u),
            List(Send(next, FrMsg.Leader(u))),
            Some(FrOutput.Follows(u)),
            Rules.learnLeader
          )
        }
      case FrState.Announcing(m) =>
        if u == m then { Right(Step(FrState.Leader(m), Nil, None, Rules.announceReturned)) }
        else {
          Left(
            ProtocolError.UnexpectedMessage(from, s"the leader ${m.value} got Leader(${u.value})")
          )
        }
      case FrState.Asleep(_) | FrState.Active(_, _, _) | FrState.Leader(_) |
          FrState.Follower(_, _) =>
        Left(
          ProtocolError.UnexpectedMessage(from, s"Leader(${u.value}) is not legal in state $state")
        )
    }
  }
}

object FrState {

  /** The leader is `Chosen`, relays are `Passive`, and each candidate shows its round. */
  given Overlay[FrState] = new Overlay[FrState] {
    def marks(states: SortedMap[NodeId, FrState]): List[Mark] = {
      states.toList.flatMap {
        case (node, FrState.Announcing(_) | FrState.Leader(_)) =>
          List(Mark.Fill(node, NodeFill.Chosen))
        case (node, FrState.Passive(_) | FrState.Follower(_, _)) =>
          List(Mark.Fill(node, NodeFill.Passive))
        case (node, FrState.Active(_, round, _)) => List(Mark.Badge(node, s"round $round"))
        case (_, FrState.Asleep(_))              => Nil
      }
    }
  }

  given Inspect[FrState] = new Inspect[FrState] {
    def label(s: FrState): String = s match {
      case FrState.Asleep(uid)       => s"asleep, uid ${uid.value}"
      case FrState.Active(uid, _, _) => s"active, uid ${uid.value}"
      case FrState.Passive(uid)      => uid.fold("passive")(u => s"passive, uid ${u.value}")
      case FrState.Announcing(uid)   => s"leader (uid ${uid.value}), announcing"
      case FrState.Leader(uid)       => s"leader (uid ${uid.value})"
      case FrState.Follower(_, _)    => "follower"
    }
    def fields(s: FrState): List[(String, String)] = {
      def own(uid: Option[Uid]): String = uid.fold("none")(_.value.toString)
      s match {
        case FrState.Asleep(uid) => List("phase" -> "asleep", "uid" -> uid.value.toString)
        case FrState.Active(uid, round, heard) =>
          List("phase" -> "active", "uid" -> uid.value.toString, "round" -> round.toString) ++
            heard.map(h => s"from node ${h.from.value}" -> s"uid ${h.uid.value}, round ${h.round}")
        case FrState.Passive(uid)    => List("phase" -> "passive", "uid" -> own(uid))
        case FrState.Announcing(uid) => List("phase" -> "announcing", "uid" -> uid.value.toString)
        case FrState.Leader(uid)     => List("phase" -> "leader", "uid" -> uid.value.toString)
        case FrState.Follower(uid, leader) =>
          List("phase" -> "follower", "uid" -> own(uid), "leader" -> s"uid ${leader.value}")
      }
    }
  }
}

object FrMsg {
  given Inspect[FrMsg] = new Inspect[FrMsg] {
    def label(m: FrMsg): String = m match {
      case FrMsg.Id(uid, round) => s"Id(${uid.value}, round $round)"
      case FrMsg.Leader(uid)    => s"Leader(${uid.value})"
    }
    def fields(m: FrMsg): List[(String, String)] = m match {
      case FrMsg.Id(uid, round) => List("uid" -> uid.value.toString, "round" -> round.toString)
      case FrMsg.Leader(uid)    => List("uid" -> uid.value.toString)
    }
  }
}

object FrAction {
  given Inspect[FrAction] = new Inspect[FrAction] {
    def label(a: FrAction): String = a match {
      case FrAction.Wake => "Wake"
    }
    def fields(a: FrAction): List[(String, String)] = Nil
  }
}

object FrOutput {
  given Inspect[FrOutput] = new Inspect[FrOutput] {
    def label(o: FrOutput): String = o match {
      case FrOutput.Elected(uid)    => s"it is the leader (uid ${uid.value})"
      case FrOutput.Follows(leader) => s"the leader is uid ${leader.value}"
    }
    def fields(o: FrOutput): List[(String, String)] = o match {
      case FrOutput.Elected(uid)    => List("uid" -> uid.value.toString)
      case FrOutput.Follows(leader) => List("leader" -> leader.value.toString)
    }
  }
}
