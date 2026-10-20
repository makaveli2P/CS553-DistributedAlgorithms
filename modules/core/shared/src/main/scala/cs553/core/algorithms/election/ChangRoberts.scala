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
import cs553.core.rng.Rng
import cs553.core.rng.Seed
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

/** The messages of Chang-Roberts. */
enum CrMsg derives Codec.AsObject {

  /** The bid of the initiator with this uid. */
  case Elect(uid: Uid)

  /** The announcement of the leader. */
  case Leader(uid: Uid)
}

/** The spontaneous action of an initiator. */
enum CrAction derives Codec.AsObject {
  case Wake
}

/** The decision of one node. */
enum CrOutput derives Codec.AsObject {

  /** The node is the leader. */
  case Elected(uid: Uid)

  /** The node knows the uid of the leader. */
  case Follows(leader: Uid)
}

/** The state of one node. Each case holds only the data that is legal in that phase. */
enum CrState derives Codec.AsObject {

  /** An initiator before `Wake`. */
  case Asleep(uid: Uid)

  /** An initiator that sent its bid and can still win. */
  case Active(uid: Uid)

  /** A node that relays each message: each non-initiator, and each defeated initiator. */
  case Passive(uid: Option[Uid])

  /** The leader. Its announcement is on the ring. */
  case Announcing(uid: Uid)

  /** The leader. Its announcement came back. */
  case Leader(uid: Uid)

  /** A node that knows the uid of the leader. */
  case Follower(uid: Option[Uid], leader: Uid)
}

/** The Chang-Roberts election on a directed ring (Fokkink, section 9.1), with a leader
  * announcement. See `docs/algorithms/chang-roberts.md`.
  */
object ChangRoberts {

  /** The invariant that the web checks at each step: at most one node is the leader. */
  def invariant(w: World[CrState, CrMsg]): Option[String] = {
    val leaders = w.states.toList.collect {
      case (node, CrState.Announcing(_) | CrState.Leader(_)) => node.value.toString
    }
    Option.when(leaders.size > 1)(s"Nodes ${leaders.mkString(", ")} are all leaders.")
  }

  val id: AlgorithmId = AlgorithmId("chang-roberts")

  /** The rule ids. Each one names one case of a handler. */
  object Rules {
    val wake: RuleId = RuleId("wake")
    val purge: RuleId = RuleId("purge")
    val defeat: RuleId = RuleId("defeat")
    val relayElect: RuleId = RuleId("relay-elect")
    val elected: RuleId = RuleId("elected")
    val learnLeader: RuleId = RuleId("learn-leader")
    val announceReturned: RuleId = RuleId("announce-returned")
  }

  private type Result = Either[ProtocolError, Step[CrState, CrMsg, CrOutput]]

  /** A directed ring of `size` nodes. The uids are a seeded permutation of `1..size`. Each node is
    * an initiator with probability 1/2, and one or more nodes are initiators. Only ring nodes can
    * be initiators.
    */
  def randomRing(size: Int, seed: Seed): Either[NonEmptyList[TopologyError], Topology] = {
    val drawn = for {
      shuffled <- shuffle(Rng(seed), (1 to size).toVector)
      (afterShuffle, uids) = shuffled
      flipped <- (0 until size).foldLeft(Option((afterShuffle, Vector.empty[Int]))) {
        case (state, _) =>
          state.flatMap { case (rng, acc) =>
            rng.nextInt(2).map { case (next, coin) => (next, acc :+ coin) }
          }
      }
      (afterCoins, coins) = flipped
      fallback = afterCoins.nextInt(size).fold(0)(_._2)
    } yield {
      val chosen = coins.indices.filter(i => coins.lift(i).contains(1)).toSet
      val initiators = if chosen.isEmpty then { Set(fallback) }
      else { chosen }
      uids.zipWithIndex.map { case (uid, i) =>
        val role = if initiators.contains(i) then { Role.Initiator }
        else { Role.NonInitiator }
        NodeId(i) -> NodeAttrs(role, Some(Uid(uid)), Map.empty)
      }.toMap
    }
    Topology.ring(size, drawn.getOrElse(Map.empty))
  }

  /** A Fisher-Yates shuffle of `items` with the generator. */
  private def shuffle[A](start: Rng, items: Vector[A]): Option[(Rng, Vector[A])] = {
    (items.size - 1 to 1 by -1).foldLeft(Option((start, items))) { case (state, i) =>
      state.flatMap { case (rng, acc) =>
        rng.nextInt(i + 1).map { case (next, j) =>
          val swapped = (acc.lift(i), acc.lift(j)) match {
            case (Some(a), Some(b)) => acc.updated(i, b).updated(j, a)
            case _                  => acc
          }
          (next, swapped)
        }
      }
    }
  }

  val protocol: Protocol[CrState, CrMsg, CrAction, CrOutput] =
    new Protocol[CrState, CrMsg, CrAction, CrOutput] {
      val id: AlgorithmId = ChangRoberts.id
      val order: OrderPolicy = OrderPolicy.Default(ChannelOrder.Fifo)

      /** A directed ring, one or more initiators, a uid for each initiator and no uid on two nodes.
        * `init` checks only what one node can see.
        */
      override def check(topology: Topology): Either[NonEmptyList[TopologyError], Topology] = {
        val nodes = topology.nodes.toList
        val degrees = nodes.filterNot { n =>
          topology.outNeighbours(n).size == 1 && topology.inNeighbours(n).size == 1
        }
        val shape =
          if degrees.isEmpty && topology.isConnected then { Nil }
          else {
            List(
              TopologyError.Requirement(
                "Chang-Roberts needs a directed ring: each node has one in-edge and one out-edge, " +
                  "and one cycle holds all nodes"
              )
            )
          }
        val initiators = topology.initiators.toList
        val noInitiator =
          if initiators.isEmpty then {
            List(TopologyError.Requirement("Chang-Roberts needs one or more initiators"))
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

      def init(ctx: NodeCtx, attrs: NodeAttrs): Either[TopologyError, CrState] = {
        if ctx.out.size != 1 || ctx.in.size != 1 then {
          Left(
            TopologyError.Requirement(
              s"Chang-Roberts needs a directed ring, but node ${ctx.self.value} has " +
                s"${ctx.in.size} in-edges and ${ctx.out.size} out-edges"
            )
          )
        } else {
          attrs.role match {
            case Role.NonInitiator => Right(CrState.Passive(attrs.uid))
            case Role.Initiator    =>
              attrs.uid
                .map(CrState.Asleep(_))
                .toRight(TopologyError.Requirement(s"the initiator ${ctx.self.value} has no uid"))
          }
        }
      }

      def actions(ctx: NodeCtx, state: CrState): List[CrAction] = state match {
        case CrState.Asleep(_) => List(CrAction.Wake)
        case CrState.Active(_) | CrState.Passive(_) | CrState.Announcing(_) | CrState.Leader(_) |
            CrState.Follower(_, _) =>
          Nil
      }

      def onAction(ctx: NodeCtx, state: CrState, action: CrAction): Result = {
        action match {
          case CrAction.Wake =>
            state match {
              case CrState.Asleep(p) =>
                send(ctx, CrState.Active(p), CrMsg.Elect(p), None, Rules.wake)
              case CrState.Active(_) | CrState.Passive(_) | CrState.Announcing(_) |
                  CrState.Leader(_) | CrState.Follower(_, _) =>
                Left(ProtocolError.IllegalAction(s"Wake is not legal in state $state"))
            }
        }
      }

      def onMessage(ctx: NodeCtx, state: CrState, from: NodeId, msg: CrMsg): Result = {
        msg match {
          case CrMsg.Elect(q)  => onElect(ctx, state, from, q)
          case CrMsg.Leader(u) => onLeader(ctx, state, from, u)
        }
      }
    }

  /** A step that sends one message to the successor on the ring. */
  private def send(
      ctx: NodeCtx,
      after: CrState,
      msg: CrMsg,
      decision: Option[CrOutput],
      rule: RuleId
  ): Result = {
    ctx.out.headOption match {
      case Some(next) => Right(Step(after, List(Send(next, msg)), decision, rule))
      case None       =>
        Left(ProtocolError.InvariantBroken(s"node ${ctx.self.value} has no successor"))
    }
  }

  private def stay(state: CrState, rule: RuleId): Result = Right(Step(state, Nil, None, rule))

  private def onElect(ctx: NodeCtx, state: CrState, from: NodeId, q: Uid): Result = {
    val ord = summon[Ordering[Uid]]
    state match {
      case CrState.Asleep(p) =>
        if ord.lt(q, p) then { stay(state, Rules.purge) }
        else if ord.gt(q, p) then {
          send(ctx, CrState.Passive(Some(p)), CrMsg.Elect(q), None, Rules.defeat)
        } else {
          Left(ProtocolError.UnexpectedMessage(from, "an initiator got its own uid before Wake"))
        }
      case CrState.Active(p) =>
        if ord.lt(q, p) then { stay(state, Rules.purge) }
        else if ord.gt(q, p) then {
          send(ctx, CrState.Passive(Some(p)), CrMsg.Elect(q), None, Rules.defeat)
        } else {
          send(
            ctx,
            CrState.Announcing(p),
            CrMsg.Leader(p),
            Some(CrOutput.Elected(p)),
            Rules.elected
          )
        }
      case CrState.Passive(_) | CrState.Follower(_, _) =>
        send(ctx, state, CrMsg.Elect(q), None, Rules.relayElect)
      case CrState.Announcing(m) => purgeAfterElection(state, from, q, m)
      case CrState.Leader(m)     => purgeAfterElection(state, from, q, m)
    }
  }

  /** The leader purges each smaller bid that is still on the ring. */
  private def purgeAfterElection(state: CrState, from: NodeId, q: Uid, m: Uid): Result = {
    if summon[Ordering[Uid]].lt(q, m) then { stay(state, Rules.purge) }
    else {
      Left(ProtocolError.UnexpectedMessage(from, s"the leader ${m.value} got Elect(${q.value})"))
    }
  }

  private def onLeader(ctx: NodeCtx, state: CrState, from: NodeId, u: Uid): Result = {
    state match {
      case CrState.Passive(own) =>
        send(
          ctx,
          CrState.Follower(own, u),
          CrMsg.Leader(u),
          Some(CrOutput.Follows(u)),
          Rules.learnLeader
        )
      case CrState.Announcing(m) =>
        if u == m then { stay(CrState.Leader(m), Rules.announceReturned) }
        else {
          Left(
            ProtocolError.UnexpectedMessage(from, s"the leader ${m.value} got Leader(${u.value})")
          )
        }
      case CrState.Asleep(_) | CrState.Active(_) | CrState.Leader(_) | CrState.Follower(_, _) =>
        Left(
          ProtocolError.UnexpectedMessage(from, s"Leader(${u.value}) is not legal in state $state")
        )
    }
  }
}

object CrState {

  /** The fill of the leader. */
  given Overlay[CrState] = new Overlay[CrState] {
    def marks(states: SortedMap[NodeId, CrState]): List[Mark] = {
      states.toList.collect { case (node, CrState.Announcing(_) | CrState.Leader(_)) =>
        Mark.Fill(node, NodeFill.Chosen)
      }
    }
  }

  given Inspect[CrState] = new Inspect[CrState] {

    /** The label has one or two parts. The UI shows each part on its own line. The node id is not
      * in the label, so the label names the uid. A follower label is one word: the leader uid is in
      * `fields`, and the canvas does not repeat it at each node.
      */
    def label(s: CrState): String = s match {
      case CrState.Asleep(uid)     => s"asleep, uid ${uid.value}"
      case CrState.Active(uid)     => s"active, uid ${uid.value}"
      case CrState.Passive(uid)    => uid.fold("passive")(u => s"passive, uid ${u.value}")
      case CrState.Announcing(uid) => s"leader (uid ${uid.value}), announcing"
      case CrState.Leader(uid)     => s"leader (uid ${uid.value})"
      case CrState.Follower(_, _)  => "follower"
    }
    def fields(s: CrState): List[(String, String)] = {
      def own(uid: Option[Uid]): String = uid.fold("none")(_.value.toString)
      s match {
        case CrState.Asleep(uid)     => List("phase" -> "asleep", "uid" -> uid.value.toString)
        case CrState.Active(uid)     => List("phase" -> "active", "uid" -> uid.value.toString)
        case CrState.Passive(uid)    => List("phase" -> "passive", "uid" -> own(uid))
        case CrState.Announcing(uid) => List("phase" -> "announcing", "uid" -> uid.value.toString)
        case CrState.Leader(uid)     => List("phase" -> "leader", "uid" -> uid.value.toString)
        case CrState.Follower(uid, leader) =>
          List("phase" -> "follower", "uid" -> own(uid), "leader" -> s"uid ${leader.value}")
      }
    }
  }
}

object CrMsg {
  given Inspect[CrMsg] = new Inspect[CrMsg] {
    def label(m: CrMsg): String = m match {
      case CrMsg.Elect(uid)  => s"Elect(${uid.value})"
      case CrMsg.Leader(uid) => s"Leader(${uid.value})"
    }
    def fields(m: CrMsg): List[(String, String)] = m match {
      case CrMsg.Elect(uid)  => List("uid" -> uid.value.toString)
      case CrMsg.Leader(uid) => List("uid" -> uid.value.toString)
    }
  }
}

object CrAction {
  given Inspect[CrAction] = new Inspect[CrAction] {
    def label(a: CrAction): String = a match {
      case CrAction.Wake => "Wake"
    }
    def fields(a: CrAction): List[(String, String)] = Nil
  }
}

object CrOutput {
  given Inspect[CrOutput] = new Inspect[CrOutput] {
    def label(o: CrOutput): String = o match {
      case CrOutput.Elected(uid)    => s"it is the leader (uid ${uid.value})"
      case CrOutput.Follows(leader) => s"the leader is uid ${leader.value}"
    }
    def fields(o: CrOutput): List[(String, String)] = o match {
      case CrOutput.Elected(uid)    => List("uid" -> uid.value.toString)
      case CrOutput.Follows(leader) => List("leader" -> leader.value.toString)
    }
  }
}
