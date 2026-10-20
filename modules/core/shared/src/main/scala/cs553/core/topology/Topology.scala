package cs553.core.topology

import cats.data.NonEmptyList
import cs553.core.ids.NodeId
import cs553.core.ids.Uid
import io.circe.Codec
import io.circe.Encoder

import scala.annotation.tailrec
import scala.collection.immutable.SortedMap
import scala.collection.immutable.SortedSet
import scala.util.Try

/** One directed edge. A channel in a run has the same shape. */
final case class Edge(from: NodeId, to: NodeId) derives Codec.AsObject

object Edge {
  given Ordering[Edge] = Ordering.by[Edge, (NodeId, NodeId)](e => (e.from, e.to))
}

/** The role of a node at the start of a run. */
enum Role derives Codec.AsObject {
  case Initiator, NonInitiator
}

/** The input attributes of one node. `extra` keeps the other DOT attributes, for example `N of M`
  * requests, as data for the protocol to parse.
  */
final case class NodeAttrs(
    role: Role,
    uid: Option[Uid],
    extra: Map[String, String]
) derives Codec.AsObject {

  def withExtra(key: String, value: String): NodeAttrs = copy(extra = extra + (key -> value))

  /** The integer value of `extra(key)`. `Right(None)` when the key is absent. */
  def int(node: NodeId, key: String): Either[TopologyError, Option[Int]] = extra.get(key) match {
    case None       => Right(None)
    case Some(text) =>
      Try(text.trim.toInt).toOption
        .map(Some(_))
        .toRight(TopologyError.BadAttribute(node, key, text))
  }
}

object NodeAttrs {
  val default: NodeAttrs = NodeAttrs(Role.NonInitiator, None, Map.empty)

  /** The names of the `extra` attributes that more than one algorithm reads. A DOT file gives them
    * as node attributes, for example `3 [request=2, balance=10]`.
    */
  object Keys {

    /** Bracha-Toueg: the node needs `request` (N) grants from its M out-neighbours. */
    val request: String = "request"

    /** Snapshots: the initial balance of the node. */
    val balance: String = "balance"

    /** Snapshots: the number of transfers that the node can still start. */
    val budget: String = "budget"
  }
}

/** An error in a topology input. A protocol also returns it from `init` when the topology does not
  * meet its requirements.
  */
enum TopologyError derives Codec.AsObject {
  case Dot(error: DotError)
  case Empty
  case BadNodeId(raw: String)
  case DuplicateNode(node: NodeId)
  case UnknownNode(node: NodeId)
  case SelfLoop(node: NodeId)
  case BadAttribute(node: NodeId, key: String, value: String)
  case RingTooSmall(size: Int)
  case Requirement(detail: String)

  def render: String = this match {
    case Dot(error)                     => error.render
    case Empty                          => "the topology has no nodes"
    case BadNodeId(raw)                 => s"the node id '$raw' is not a non-negative integer"
    case DuplicateNode(node)            => s"the node ${node.value} occurs more than once"
    case UnknownNode(node)              => s"the node ${node.value} is not in the node set"
    case SelfLoop(node)                 => s"the node ${node.value} has an edge to itself"
    case BadAttribute(node, key, value) =>
      s"the node ${node.value} has the attribute $key=$value, which is not valid"
    case RingTooSmall(size)  => s"a ring needs 2 or more nodes, not $size"
    case Requirement(detail) => detail
  }
}

/** A named topology that suits one algorithm. A shared link holds the name, not the topology.
  * `title` is the name that a learner reads, for example "Ring of 6".
  */
final case class Preset(name: String, title: String, topology: Topology)

/** A network: the nodes, the directed edges and the input attributes of each node.
  *
  * An undirected graph holds both directions of each edge. Only the smart constructors make a
  * value, so each edge joins two known nodes, and no edge is a self-loop.
  */
final case class Topology private (
    nodes: SortedSet[NodeId],
    edges: SortedSet[Edge],
    attrs: SortedMap[NodeId, NodeAttrs]
) {

  def outNeighbours(node: NodeId): SortedSet[NodeId] = {
    edges.filter(_.from == node).map(_.to)
  }

  def inNeighbours(node: NodeId): SortedSet[NodeId] = {
    edges.filter(_.to == node).map(_.from)
  }

  def attrsOf(node: NodeId): NodeAttrs = attrs.getOrElse(node, NodeAttrs.default)

  def initiators: SortedSet[NodeId] = nodes.filter(n => attrsOf(n).role == Role.Initiator)

  def outDegree(node: NodeId): Int = outNeighbours(node).size

  /** True when the graph is undirected and connected and has no cycle. */
  def isTree: Boolean = isSymmetric && isConnected && edges.size == 2 * (nodes.size - 1)

  /** True when each node can reach each other node along the edge directions. */
  def isStronglyConnected: Boolean = nodes.headOption match {
    case None        => true
    case Some(start) =>
      val reversed = Topology(nodes, edges.map(e => Edge(e.to, e.from)), attrs)
      forward(Set(start), List(start)) == nodes.toSet &&
      reversed.forward(Set(start), List(start)) == nodes.toSet
  }

  @tailrec
  private def forward(seen: Set[NodeId], frontier: List[NodeId]): Set[NodeId] = frontier match {
    case Nil          => seen
    case node :: rest =>
      val next = outNeighbours(node).filterNot(seen.contains).toList
      forward(seen ++ next, next ++ rest)
  }

  /** True when each edge has its reverse edge, so the graph is undirected. */
  def isSymmetric: Boolean = edges.forall(e => edges.contains(Edge(e.to, e.from)))

  /** The same topology with the reverse of each edge added. */
  def symmetric: Topology = copy(edges = edges ++ edges.map(e => Edge(e.to, e.from)))

  /** The nodes that no edge touches. */
  def isolated: SortedSet[NodeId] = {
    nodes.filterNot(n => edges.exists(e => e.from == n || e.to == n))
  }

  /** True when each node can reach each other node, with the edge directions ignored. */
  def isConnected: Boolean = nodes.headOption match {
    case None        => true
    case Some(start) => reach(Set(start), List(start)) == nodes.toSet
  }

  @tailrec
  private def reach(seen: Set[NodeId], frontier: List[NodeId]): Set[NodeId] = frontier match {
    case Nil          => seen
    case node :: rest =>
      val next = (outNeighbours(node) ++ inNeighbours(node)).filterNot(seen.contains).toList
      reach(seen ++ next, next ++ rest)
  }

  /** The same topology without these nodes and without the edges that touch them. */
  def without(remove: Set[NodeId]): Either[NonEmptyList[TopologyError], Topology] = {
    Topology.directed(
      nodes.filterNot(remove.contains).toList,
      edges.toList.filterNot(e => remove.contains(e.from) || remove.contains(e.to)).map { e =>
        (e.from, e.to)
      },
      attrs.filterNot { case (n, _) => remove.contains(n) }
    )
  }

  /** The same topology with exactly these nodes as initiators. Each other node is a non-initiator.
    */
  def withInitiators(chosen: Set[NodeId]): Either[NonEmptyList[TopologyError], Topology] = {
    NonEmptyList.fromList(chosen.toList.sorted.filterNot(nodes.contains)) match {
      case Some(unknown) => Left(unknown.map(TopologyError.UnknownNode(_)))
      case None          =>
        withAttrs(nodes.toList.map { n =>
          val role = if chosen.contains(n) then { Role.Initiator }
          else { Role.NonInitiator }
          n -> attrsOf(n).copy(role = role)
        }.toMap)
    }
  }

  /** The same topology with new attributes for some nodes. */
  def withAttrs(
      updates: Map[NodeId, NodeAttrs]
  ): Either[NonEmptyList[TopologyError], Topology] = {
    Topology.directed(nodes.toList, edges.toList.map(e => (e.from, e.to)), attrs ++ updates)
  }
}

object Topology {

  /** A directed topology. Each pair `(a, b)` is an edge from `a` to `b`. */
  def directed(
      nodes: Iterable[NodeId],
      edges: Iterable[(NodeId, NodeId)],
      attrs: Map[NodeId, NodeAttrs] = Map.empty
  ): Either[NonEmptyList[TopologyError], Topology] = {
    val nodeList = nodes.toList
    val nodeSet = SortedSet.from(nodeList)
    val duplicates = nodeList.diff(nodeSet.toList).distinct.map(TopologyError.DuplicateNode(_))
    val edgeErrors = edges.toList.flatMap { case (a, b) =>
      val unknown =
        List(a, b).distinct.filterNot(nodeSet.contains).map(TopologyError.UnknownNode(_))
      val loop = if a == b then { List(TopologyError.SelfLoop(a)) }
      else { Nil }
      unknown ++ loop
    }
    val attrErrors =
      attrs.keys.toList.sorted.filterNot(nodeSet.contains).map(TopologyError.UnknownNode(_))
    val empty = if nodeSet.isEmpty then { List(TopologyError.Empty) }
    else { Nil }
    NonEmptyList.fromList((empty ++ duplicates ++ edgeErrors ++ attrErrors).distinct) match {
      case Some(errors) => Left(errors)
      case None         =>
        Right(
          Topology(
            nodeSet,
            SortedSet.from(edges.map { case (a, b) => Edge(a, b) }),
            SortedMap.from(attrs)
          )
        )
    }
  }

  /** An undirected topology. Each pair gives an edge in both directions. */
  def undirected(
      nodes: Iterable[NodeId],
      edges: Iterable[(NodeId, NodeId)],
      attrs: Map[NodeId, NodeAttrs] = Map.empty
  ): Either[NonEmptyList[TopologyError], Topology] = {
    directed(nodes, edges, attrs).map(_.symmetric)
  }

  /** A directed ring `0 -> 1 -> ... -> size-1 -> 0`. Use `symmetric` for a bidirectional ring. */
  def ring(
      size: Int,
      attrs: Map[NodeId, NodeAttrs] = Map.empty
  ): Either[NonEmptyList[TopologyError], Topology] = {
    if size < 2 then { Left(NonEmptyList.one(TopologyError.RingTooSmall(size))) }
    else {
      val ids = (0 until size).map(NodeId(_))
      directed(ids, ids.map(i => (i, NodeId((i.value + 1) % size))), attrs)
    }
  }

  /** A ring with both directions of each edge, for example for Franklin. */
  def bidirectionalRing(
      size: Int,
      attrs: Map[NodeId, NodeAttrs] = Map.empty
  ): Either[NonEmptyList[TopologyError], Topology] = ring(size, attrs).map(_.symmetric)

  /** An undirected tree. Gives a `Requirement` error when the edges do not make a tree. */
  def tree(
      nodes: Iterable[NodeId],
      edges: Iterable[(NodeId, NodeId)],
      attrs: Map[NodeId, NodeAttrs] = Map.empty
  ): Either[NonEmptyList[TopologyError], Topology] = {
    undirected(nodes, edges, attrs).flatMap { t =>
      if t.isTree then { Right(t) }
      else { Left(NonEmptyList.one(TopologyError.Requirement("the graph is not a tree"))) }
    }
  }

  /** Parses DOT text into a topology. A `graph` gives an undirected topology, and a `digraph` gives
    * a directed topology.
    *
    * Node ids must be non-negative integers. A node is an initiator when it has the attribute
    * `initiator=true`, or when its label is the text `Init`, with or without HTML tags (the college
    * fixtures use `label=<<b>Init</b>>`). A label such as `NonInitiator` or `Initial` does not make
    * an initiator. The attribute `uid` gives the `Uid`. The other attributes go to
    * `NodeAttrs.extra`.
    */
  def fromDot(source: String): Either[NonEmptyList[TopologyError], Topology] = {
    DotParser.parse(source).left.map(_.map(TopologyError.Dot(_))).flatMap(fromGraph)
  }

  def fromGraph(graph: DotGraph): Either[NonEmptyList[TopologyError], Topology] = {
    val parsedNodes = graph.nodes.map(n => parseId(n.id).map(id => (id, n.attrs)))
    val parsedEdges = graph.edges.map(e => parseId(e.from).flatMap(a => parseId(e.to).map((a, _))))
    val errors = parsedNodes.collect { case Left(e) => e } ++ parsedEdges.collect { case Left(e) =>
      e
    }
    NonEmptyList.fromList(errors.distinct) match {
      case Some(errs) => Left(errs)
      case None       =>
        val nodes = parsedNodes.collect { case Right(n) => n }
        val edges = parsedEdges.collect { case Right(e) => e }
        val attrResults = nodes.map { case (id, raw) => nodeAttrs(id, raw).map(id -> _) }
        val attrErrors = attrResults.collect { case Left(e) => e }
        NonEmptyList.fromList(attrErrors) match {
          case Some(errs) => Left(errs)
          case None       =>
            val attrs = attrResults.collect { case Right(a) => a }.toMap
            graph.kind match {
              case GraphKind.Undirected => undirected(nodes.map(_._1), edges, attrs)
              case GraphKind.Directed   => directed(nodes.map(_._1), edges, attrs)
            }
        }
    }
  }

  private def parseId(raw: String): Either[TopologyError, NodeId] = {
    Try(raw.trim.toInt).toOption
      .filter(_ >= 0)
      .map(NodeId(_))
      .toRight(TopologyError.BadNodeId(raw))
  }

  private val roleKeys: Set[String] = Set("initiator", "uid")

  /** The text of a label without its HTML tags: `<b>Init</b>` gives `Init`. */
  private def withoutTags(label: String): String = label.replaceAll("<[^<>]*>", "")

  private def nodeAttrs(id: NodeId, raw: Map[String, String]): Either[TopologyError, NodeAttrs] = {
    val labelledInit = raw.get("label").exists(label => withoutTags(label).trim == "Init")
    val role = raw.get("initiator") match {
      case Some("true")  => Right(Role.Initiator)
      case Some("false") => Right(Role.NonInitiator)
      case Some(other)   => Left(TopologyError.BadAttribute(id, "initiator", other))
      case None          =>
        Right(if labelledInit then {
          Role.Initiator
        } else { Role.NonInitiator })
    }
    val uid = raw.get("uid") match {
      case None       => Right(None)
      case Some(text) =>
        Try(text.trim.toInt).toOption
          .map(u => Some(Uid(u)))
          .toRight(TopologyError.BadAttribute(id, "uid", text))
    }
    for {
      r <- role
      u <- uid
    } yield NodeAttrs(r, u, raw.filterNot { case (k, _) => roleKeys.contains(k) })
  }

  private final case class Wire(
      nodes: List[NodeId],
      edges: List[Edge],
      attrs: Map[NodeId, NodeAttrs]
  ) derives Codec.AsObject

  /** The JSON form lists the nodes, the edges and the attributes. Decoding runs the checks of
    * `directed`.
    */
  given Codec[Topology] = {
    val wire = summon[Codec.AsObject[Wire]]
    Codec.from(
      wire.emap { w =>
        directed(w.nodes, w.edges.map(e => (e.from, e.to)), w.attrs).left.map { errors =>
          errors.toList.map(_.render).mkString("; ")
        }
      },
      Encoder.instance[Topology] { t =>
        wire(Wire(t.nodes.toList, t.edges.toList, t.attrs.toMap))
      }
    )
  }
}
