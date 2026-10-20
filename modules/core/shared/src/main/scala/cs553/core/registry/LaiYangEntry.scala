package cs553.core.registry

import cs553.core.Family
import cs553.core.algorithms.snapshot.LaiYang
import cs553.core.algorithms.snapshot.LyAction
import cs553.core.algorithms.snapshot.LyMsg
import cs553.core.algorithms.snapshot.LyOutput
import cs553.core.algorithms.snapshot.LyState
import cs553.core.ids.NodeId
import cs553.core.topology.NodeAttrs
import cs553.core.topology.Topology

/** The registry entry of Lai-Yang. */
object LaiYangEntry {

  /** Four bank nodes on a directed graph that is strongly connected but not symmetric. */
  private val bank4: String =
    """digraph bank {
      |  0 [initiator=true, balance=10, budget=3];
      |  1 [balance=20, budget=2];
      |  2 [balance=5, budget=2];
      |  3 [balance=15, budget=2];
      |  0 -> 1; 1 -> 2; 2 -> 0; 2 -> 3; 3 -> 0; 1 -> 3;
      |}""".stripMargin

  val algorithm: Algorithm[LyState, LyMsg, LyAction, LyOutput] = Algorithm(
    LaiYang.protocol,
    "Lai-Yang",
    Family.Snapshot,
    "Nodes color their transfers white or red, so a consistent snapshot needs no FIFO channels.",
    List(
      Presets.fromDot("bank-4", "Bank of 4, directed", bank4),
      Presets.fromFixture(
        "netgraph-6",
        "Six-node network, both directions",
        "NetGraph_30-03-24-18-54-55.ngs.dot",
        t => t.symmetric.withAttrs(listeners(t, List(3, 4)))
      ),
      Presets.of(
        "ring-5",
        "Ring of 5, two initiators",
        Topology.bidirectionalRing(
          5,
          Presets.ringAttrs(List(1, 2, 3, 4, 5), Set(0, 2)).map { case (n, a) =>
            n -> a.withExtra(
              NodeAttrs.Keys.budget,
              if n.value == 4 then { "0" }
              else { "2" }
            )
          }
        )
      )
    ).flatten,
    LaiYang.invariant
  )

  val entry: Option[AnyAlgorithm] = Some(AnyAlgorithm(algorithm))

  /** These nodes get budget 0: they only receive, so the first step of each one is a delivery. */
  private def listeners(t: Topology, nodes: List[Int]): Map[NodeId, NodeAttrs] = {
    nodes.map(NodeId(_)).map(n => n -> t.attrsOf(n).withExtra(NodeAttrs.Keys.budget, "0")).toMap
  }
}
