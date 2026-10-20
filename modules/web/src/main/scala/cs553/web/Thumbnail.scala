package cs553.web

import cs553.core.registry.Algorithm
import cs553.core.topology.Edge
import cs553.core.trace.EdgeStyle
import cs553.core.trace.Mark
import cs553.core.trace.NodeFill

/** One node of a home thumbnail. `chosen` is true when the overlay fills the node `Chosen` at the
  * end of the run: the initiator of a wave that decided, or the leader of an election.
  */
final case class ThumbNode(at: Point, chosen: Boolean)

/** One edge of a home thumbnail. `tree` is true when the edge is in the tree of the run. */
final case class ThumbEdge(a: Point, b: Point, tree: Boolean)

/** A small static picture of the end of the default run of an algorithm, drawn from the marks of
  * its overlay.
  */
final case class Thumb(box: Box, nodes: List[ThumbNode], edges: List[ThumbEdge])

/** The home thumbnails. Each one is the default preset at the end of its seeded run: the tree of a
  * wave and the chosen node. All functions are pure.
  */
object Thumbnail {

  /** The size of a thumbnail in view box units. */
  val size: Size = Size(200, 120)

  /** The radii of the ring of a thumbnail. */
  val rx: Double = 66.0
  val ry: Double = 40.0

  /** The radius of a thumbnail node. */
  val nodeRadius: Double = 7.0

  def of[S, M, A, O](alg: Algorithm[S, M, A, O]): Option[Thumb] = {
    for {
      spec <- alg.defaultSpec
      sim <- alg.simulate(spec).toOption
      world <- sim.last
    } yield {
      val at = Layout.ring(world.topology, rx, ry)
      val marks = alg.overlay.marks(world.states)
      val chosen = marks.collect { case Mark.Fill(node, NodeFill.Chosen) => node }.toSet
      val tree = marks.collect { case Mark.OnEdge(from, to, EdgeStyle.Tree) => Set(from, to) }.toSet
      val edges = world.topology.edges.toList
        .filter(e =>
          !world.topology.edges.contains(Edge(e.to, e.from)) || e.from.value < e.to.value
        )
        .flatMap { e =>
          at.get(e.from).zip(at.get(e.to)).toList.map { case (a, b) =>
            ThumbEdge(a, b, tree.contains(Set(e.from, e.to)))
          }
        }
      val nodes = world.states.keys.toList.flatMap { node =>
        at.get(node).toList.map(ThumbNode(_, chosen.contains(node)))
      }
      Thumb(Box(-size.width / 2, -size.height / 2, size.width, size.height), nodes, edges)
    }
  }
}
