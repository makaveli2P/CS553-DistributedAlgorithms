package cs553.web

import cs553.core.registry.Algorithm
import cs553.core.topology.Edge
import cs553.core.trace.Mark
import cs553.core.trace.TraceEvent

/** One node of a home thumbnail. `first` is true for the node that decided first in the run: the
  * initiator of a wave or the leader of an election.
  */
final case class ThumbNode(at: Point, first: Boolean)

/** One edge of a home thumbnail. `tree` is true when the edge is in the tree of the run. */
final case class ThumbEdge(a: Point, b: Point, tree: Boolean)

/** A small static picture of the end of the default run of an algorithm. */
final case class Thumb(box: Box, nodes: List[ThumbNode], edges: List[ThumbEdge])

/** The home thumbnails. Each one is the default preset at the end of its seeded run: the tree of a
  * wave and the node that decided first. All functions are pure.
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
      val first = sim.run.trace.collectFirst { case TraceEvent.Decided(_, node, _) => node }
      val tree = alg.overlay
        .marks(world.states)
        .collect { case Mark.TreeEdge(child, parent) =>
          Set(child, parent)
        }
        .toSet
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
        at.get(node).toList.map(ThumbNode(_, first.contains(node)))
      }
      Thumb(Box(-size.width / 2, -size.height / 2, size.width, size.height), nodes, edges)
    }
  }
}
