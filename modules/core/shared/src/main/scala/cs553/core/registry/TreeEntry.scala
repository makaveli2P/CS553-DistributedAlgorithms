package cs553.core.registry

import cs553.core.Family
import cs553.core.algorithms.wave.Tree
import cs553.core.algorithms.wave.TreeAction
import cs553.core.algorithms.wave.TreeMsg
import cs553.core.algorithms.wave.TreeOutput
import cs553.core.algorithms.wave.TreeState

/** The registry entry of the tree algorithm. */
object TreeEntry {

  val algorithm: Algorithm[TreeState, TreeMsg, TreeAction, TreeOutput] = Algorithm(
    Tree.protocol,
    "Tree",
    Family.Wave,
    "The leaves start, each node sends one token towards the centre, and two neighbours decide.",
    List(
      Presets.tree(
        "tree-7",
        "Seven nodes in three levels",
        List(0 -> 1, 0 -> 2, 1 -> 3, 1 -> 4, 2 -> 5, 2 -> 6)
      ),
      Presets.fromFixture(
        "netgraph-13",
        "College tree of 13 nodes",
        "NetGraph_21-04-24-18-24-58.ngs.perturbed.dot",
        Tree.prepare
      ),
      Presets.tree("line-5", "Line of five nodes", List(0 -> 1, 1 -> 2, 2 -> 3, 3 -> 4))
    ).flatten,
    Tree.invariant
  )

  val entry: Option[AnyAlgorithm] = Some(AnyAlgorithm(algorithm))
}
