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
import cs553.core.topology.Topology
import cs553.core.topology.TopologyError
import cs553.core.trace.Inspect
import cs553.core.trace.Mark
import cs553.core.trace.NodeFill
import cs553.core.trace.Overlay
import cs553.core.world.World
import io.circe.Codec

import scala.collection.immutable.SortedMap

/** The one message of the tree algorithm. */
enum TreeMsg derives Codec.AsObject {
  case Token
}

/** The spontaneous action of a leaf: it sends its token to its only neighbour. */
enum TreeAction derives Codec.AsObject {
  case Start
}

/** The decision of one of the two deciders. `partner` is the other decider. */
enum TreeOutput derives Codec.AsObject {
  case Decided(partner: NodeId)
}

/** The phase of one node. */
enum TreePhase derives Codec.AsObject {

  /** The node did not send. It waits for a token from each neighbour except one. */
  case Collecting

  /** The node sent its one token to its parent. It waits for a token from the parent. */
  case Sent

  /** The node got a token from each neighbour, and it decided. */
  case Decided
}

/** The state of one node. `received` holds the senders of the tokens, in the order of arrival.
  * `parent` is the neighbour that the node sent its token to. `degree` is the number of neighbours.
  */
final case class TreeState(
    phase: TreePhase,
    received: List[NodeId],
    parent: Option[NodeId],
    degree: Int
) derives Codec.AsObject

/** The tree algorithm (Fokkink, section 4.2). See `docs/algorithms/tree.md`. */
object Tree {

  val id: AlgorithmId = AlgorithmId("tree")

  /** The rule ids. Each one names one case of a handler. */
  object Rules {
    val start: RuleId = RuleId("start")
    val collect: RuleId = RuleId("collect")
    val sendToParent: RuleId = RuleId("send-to-parent")
    val tokenBeforeStart: RuleId = RuleId("token-before-start")
    val decide: RuleId = RuleId("decide")
  }

  private type Result = Either[ProtocolError, Step[TreeState, TreeMsg, TreeOutput]]

  /** The invariant that the web checks at each step:
    *
    *   - Each channel holds at most one token, because each node sends only one token.
    *   - At most two nodes decide. When two nodes decide, they are neighbours, and each one is the
    *     parent of the other.
    */
  def invariant(w: World[TreeState, TreeMsg]): Option[String] = {
    val crowded = w.channels.collectFirst {
      case (edge, queue) if queue.size > 1 =>
        s"The channel ${edge.from.value} -> ${edge.to.value} holds ${queue.size} tokens, " +
          "but each node sends only one token."
    }
    val deciders = w.states.toList.collect {
      case (node, s) if s.phase == TreePhase.Decided => (node, s.parent)
    }
    val pair = deciders match {
      case Nil | List(_)                                            => None
      case List((a, Some(pa)), (b, Some(pb))) if pa == b && pb == a => None
      case List((a, _), (b, _))                                     =>
        Some(s"Nodes ${a.value} and ${b.value} decided, but they are not each other's parent.")
      case more =>
        Some(s"${more.size} nodes decided, but exactly two neighbours must decide.")
    }
    crowded.orElse(pair)
  }

  /** Makes a loaded topology ready for the tree algorithm: it adds the reverse of each edge, so a
    * digraph becomes undirected, and then it runs `protocol.check`.
    */
  def prepare(topology: Topology): Either[NonEmptyList[TopologyError], Topology] = {
    protocol.check(topology.symmetric)
  }

  val protocol: Protocol[TreeState, TreeMsg, TreeAction, TreeOutput] =
    new Protocol[TreeState, TreeMsg, TreeAction, TreeOutput] {
      val id: AlgorithmId = Tree.id
      val order: OrderPolicy = OrderPolicy.Default(ChannelOrder.Fifo)

      /** An undirected tree with two or more nodes. The initiator attributes are not used: the
        * leaves start.
        */
      override def check(topology: Topology): Either[NonEmptyList[TopologyError], Topology] = {
        val directed =
          if topology.isSymmetric then { Nil }
          else { List(TopologyError.Requirement("the tree algorithm needs an undirected graph")) }
        val size =
          if topology.nodes.size >= 2 then { Nil }
          else {
            List(
              TopologyError.Requirement(
                s"the tree algorithm needs 2 or more nodes, not ${topology.nodes.size}"
              )
            )
          }
        val tree =
          if directed.nonEmpty || topology.isTree then { Nil }
          else {
            val edges = topology.edges.size / 2
            List(
              TopologyError.Requirement(
                s"the tree algorithm needs a tree: a connected graph with ${topology.nodes.size - 1}" +
                  s" edges, but this graph has $edges edges" +
                  (if topology.isConnected then { "" }
                   else { " and is not connected" })
              )
            )
          }
        NonEmptyList.fromList(directed ++ size ++ tree) match {
          case Some(errors) => Left(errors)
          case None         => Right(topology)
        }
      }

      def init(ctx: NodeCtx, attrs: NodeAttrs): Either[TopologyError, TreeState] = {
        if ctx.in == ctx.out then {
          Right(TreeState(TreePhase.Collecting, Nil, None, ctx.out.size))
        } else {
          Left(
            TopologyError.Requirement(
              s"the tree algorithm needs an undirected graph, but node ${ctx.self.value} has " +
                "different in-neighbours and out-neighbours"
            )
          )
        }
      }

      def actions(ctx: NodeCtx, state: TreeState): List[TreeAction] = {
        state.phase match {
          case TreePhase.Collecting =>
            remaining(ctx, state) match {
              case List(_)      => List(TreeAction.Start)
              case Nil | _ :: _ => Nil
            }
          case TreePhase.Sent | TreePhase.Decided => Nil
        }
      }

      def onAction(ctx: NodeCtx, state: TreeState, action: TreeAction): Result = {
        action match {
          case TreeAction.Start =>
            (state.phase, remaining(ctx, state)) match {
              case (TreePhase.Collecting, List(parent)) =>
                Right(
                  Step(
                    state.copy(phase = TreePhase.Sent, parent = Some(parent)),
                    List(Send(parent, TreeMsg.Token)),
                    None,
                    Rules.start
                  )
                )
              case (phase, left) =>
                Left(
                  ProtocolError.IllegalAction(
                    s"Start is not legal in phase $phase with ${left.size} neighbours left"
                  )
                )
            }
        }
      }

      def onMessage(ctx: NodeCtx, state: TreeState, from: NodeId, msg: TreeMsg): Result = {
        msg match {
          case TreeMsg.Token =>
            state.phase match {
              case TreePhase.Collecting => collect(ctx, state, from)
              case TreePhase.Sent       =>
                state.parent match {
                  case Some(parent) if parent == from =>
                    Right(
                      Step(
                        state.copy(phase = TreePhase.Decided, received = state.received :+ from),
                        Nil,
                        Some(TreeOutput.Decided(from)),
                        Rules.decide
                      )
                    )
                  case Some(_) | None =>
                    Left(
                      ProtocolError.UnexpectedMessage(
                        from,
                        "the node sent its token and waits only for its parent"
                      )
                    )
                }
              case TreePhase.Decided =>
                Left(ProtocolError.UnexpectedMessage(from, "the node got a token after it decided"))
            }
        }
      }
    }

  /** The neighbours that did not send a token to the node, in id order. */
  private def remaining(ctx: NodeCtx, state: TreeState): List[NodeId] = {
    ctx.out.toList.filterNot(state.received.contains)
  }

  /** A node in phase `Collecting` gets a token. */
  private def collect(ctx: NodeCtx, state: TreeState, from: NodeId): Result = {
    val left = remaining(ctx, state)
    if !left.contains(from) then {
      Left(ProtocolError.UnexpectedMessage(from, "the sender already sent a token"))
    } else {
      val received = state.received :+ from
      left.filterNot(_ == from) match {
        case Nil =>
          // A leaf got the token of its only neighbour before Start. It sends its token and decides
          // in one step, as if Start came first.
          Right(
            Step(
              TreeState(TreePhase.Decided, received, Some(from), state.degree),
              List(Send(from, TreeMsg.Token)),
              Some(TreeOutput.Decided(from)),
              Rules.tokenBeforeStart
            )
          )
        case List(parent) =>
          Right(
            Step(
              TreeState(TreePhase.Sent, received, Some(parent), state.degree),
              List(Send(parent, TreeMsg.Token)),
              None,
              Rules.sendToParent
            )
          )
        case _ :: _ => Right(Step(state.copy(received = received), Nil, None, Rules.collect))
      }
    }
  }
}

object TreeState {
  given Inspect[TreeState] = new Inspect[TreeState] {

    /** The label names the one neighbour that the node waits for, when it waits for one. */
    def label(s: TreeState): String = s.phase match {
      case TreePhase.Collecting => s"${s.received.size} of ${s.degree - 1} received"
      case TreePhase.Sent       => s.parent.fold("sent")(p => s"waits for ${p.value}")
      case TreePhase.Decided    => s.parent.fold("decided")(p => s"decided with ${p.value}")
    }
    def fields(s: TreeState): List[(String, String)] = {
      val phase = s.phase match {
        case TreePhase.Collecting => "collecting"
        case TreePhase.Sent       => "sent"
        case TreePhase.Decided    => "decided"
      }
      List(
        "phase" -> phase,
        "parent" -> s.parent.fold("none yet")(p => s"node ${p.value}"),
        "received from" -> {
          if s.received.isEmpty then { "none" }
          else { s.received.map(_.value).mkString(", ") }
        },
        "degree" -> s.degree.toString
      )
    }
  }

  /** The parent edges, which grow into the tree, and the fill of the two deciders. The two deciders
    * are parents of each other, so their edge shows once.
    */
  given Overlay[TreeState] = new Overlay[TreeState] {
    def marks(states: SortedMap[NodeId, TreeState]): List[Mark] = {
      val parents = states.toList.flatMap { case (node, s) => s.parent.map(node -> _) }
      val pairs = parents.toSet
      val tree = parents.collect {
        case (child, parent) if !(pairs.contains(parent -> child) && child.value > parent.value) =>
          Mark.treeEdge(child, parent)
      }
      val deciders = states.toList.collect {
        case (node, s) if s.phase == TreePhase.Decided => Mark.Fill(node, NodeFill.Chosen)
      }
      tree ++ deciders
    }
  }
}

object TreeMsg {
  given Inspect[TreeMsg] = new Inspect[TreeMsg] {
    def label(m: TreeMsg): String = m match {
      case TreeMsg.Token => "Token"
    }
    def fields(m: TreeMsg): List[(String, String)] = Nil
  }
}

object TreeAction {
  given Inspect[TreeAction] = new Inspect[TreeAction] {
    def label(a: TreeAction): String = a match {
      case TreeAction.Start => "Start"
    }
    def fields(a: TreeAction): List[(String, String)] = Nil
  }
}

object TreeOutput {
  given Inspect[TreeOutput] = new Inspect[TreeOutput] {
    def label(o: TreeOutput): String = o match {
      case TreeOutput.Decided(partner) => s"decided with ${partner.value}"
    }
    def fields(o: TreeOutput): List[(String, String)] = o match {
      case TreeOutput.Decided(partner) => List("partner" -> s"node ${partner.value}")
    }
  }
}
