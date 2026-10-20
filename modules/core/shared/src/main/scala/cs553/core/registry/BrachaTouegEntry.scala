package cs553.core.registry

import cs553.core.Family
import cs553.core.algorithms.deadlock.BrachaToueg
import cs553.core.algorithms.deadlock.BtAction
import cs553.core.algorithms.deadlock.BtMsg
import cs553.core.algorithms.deadlock.BtOutput
import cs553.core.algorithms.deadlock.BtState

/** The registry entry of Bracha-Toueg. Each preset is a directed wait-for graph: `a -> b` means
  * that `a` waits for `b`. The attribute `request` gives N of "N of M". Without it, N is M.
  */
object BrachaTouegEntry {

  /** Node 0 needs 1 of 2. Nodes 1 and 3 wait for each other, so they are deadlocked. Node 2 needs 2
    * of 2, and both nodes 4 and 5 become free. So nodes 2 and 0 become free too.
    */
  val mixedDot: String =
    """digraph {
      |  0 [initiator=true, request=1]; 1; 2; 3; 4; 5;
      |  0 -> 1; 0 -> 2; 1 -> 3; 3 -> 1; 2 -> 4; 2 -> 5; 5 -> 4
      |}""".stripMargin

  /** The college scenario "DeadlockTestData", with In from Out: the cycle 0 -> 1 -> 2 -> 3 -> 0. */
  val collegeDeadlockDot: String =
    """digraph {
      |  0 [initiator=true]; 1; 2; 3;
      |  0 -> 1; 0 -> 3; 1 -> 2; 2 -> 3; 3 -> 0
      |}""".stripMargin

  /** The college scenario "DeadlockFreeTestData": node 3 waits for nobody. */
  val collegeFreeDot: String =
    """digraph {
      |  0 [initiator=true]; 1; 2; 3;
      |  0 -> 1; 0 -> 3; 1 -> 2; 2 -> 3
      |}""".stripMargin

  val algorithm: Algorithm[BtState, BtMsg, BtAction, BtOutput] = Algorithm(
    BrachaToueg.protocol,
    "Bracha-Toueg",
    Family.Deadlock,
    "The initiator notifies the wait-for graph, free nodes grant their requests, and the nodes " +
      "that never become free are deadlocked.",
    List(
      Presets.fromDot("mixed-6", "Six nodes, 1 of 2 at the initiator", mixedDot),
      Presets.fromDot("college-deadlock", "College cycle of 4", collegeDeadlockDot),
      Presets.fromDot("college-free", "College graph with no deadlock", collegeFreeDot)
    ).flatten,
    BrachaToueg.invariant
  )

  val entry: Option[AnyAlgorithm] = Some(AnyAlgorithm(algorithm))
}
