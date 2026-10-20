package cs553.core.registry

import cs553.core.Family
import cs553.core.algorithms.snapshot.ChandyLamport
import cs553.core.algorithms.snapshot.ClAction
import cs553.core.algorithms.snapshot.ClMsg
import cs553.core.algorithms.snapshot.ClOutput
import cs553.core.algorithms.snapshot.ClState
import cs553.core.ids.NodeId
import cs553.core.topology.NodeAttrs
import cs553.core.topology.Role
import cs553.core.topology.Topology

/** The registry entry of Chandy-Lamport. */
object ChandyLamportEntry {

  /** Four nodes with a total of 50. Node 2 has no budget: it only receives. */
  private val bank4: String =
    """digraph "Bank with four branches" {
      |  0 [initiator=true, balance=10, budget=3]
      |  1 [balance=20, budget=3]
      |  2 [balance=5, budget=0]
      |  3 [balance=15, budget=3]
      |  0 -> 1; 1 -> 2; 2 -> 0; 2 -> 3; 3 -> 0; 0 -> 3
      |}""".stripMargin

  val algorithm: Algorithm[ClState, ClMsg, ClAction, ClOutput] = Algorithm(
    ChandyLamport.protocol,
    "Chandy-Lamport",
    Family.Snapshot,
    "Markers on FIFO channels cut a bank in mid-transfer, and the saved balances plus the money " +
      "in transit add up to the total.",
    List(
      Presets.fromDot("bank-4", "Bank with four branches", bank4),
      Presets.of("ring-5", "Ring of 5, two initiators", Topology.ring(5, ringBank)),
      Presets.fromFixture(
        "netgraph-13",
        "College network of 13 nodes",
        "NetGraph_21-04-24-18-24-58.ngs.perturbed.dot",
        t => Right(t.symmetric)
      )
    ).flatten,
    ChandyLamport.invariant
  )

  val entry: Option[AnyAlgorithm] = Some(AnyAlgorithm(algorithm))

  /** A directed ring of 5 nodes. Nodes 0 and 2 initiate, and the balances differ. */
  private def ringBank: Map[NodeId, NodeAttrs] = {
    List(8, 12, 4, 10, 6).zipWithIndex.map { case (balance, i) =>
      val role = if i == 0 || i == 2 then { Role.Initiator }
      else { Role.NonInitiator }
      NodeId(i) -> NodeAttrs(role, None, Map.empty)
        .withExtra(NodeAttrs.Keys.balance, balance.toString)
        .withExtra(NodeAttrs.Keys.budget, "2")
    }.toMap
  }
}
