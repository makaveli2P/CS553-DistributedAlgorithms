package cs553.core.registry

import cs553.core.Family
import cs553.core.algorithms.election.ChangRoberts
import cs553.core.algorithms.election.CrAction
import cs553.core.algorithms.election.CrMsg
import cs553.core.algorithms.election.CrOutput
import cs553.core.algorithms.election.CrState

/** The registry entry of Chang-Roberts. */
object ChangRobertsEntry {

  val algorithm: Algorithm[CrState, CrMsg, CrAction, CrOutput] = Algorithm(
    ChangRoberts.protocol,
    "Chang-Roberts",
    Family.Election,
    "Initiators on a directed ring send their uids around, and only the largest uid comes back.",
    List(
      Presets.ring("ring-6", "Ring of 6", List(4, 1, 6, 2, 5, 3), Set(0, 2, 3)),
      Presets.ring("ring-8", "Ring of 8", List(3, 7, 1, 8, 5, 2, 6, 4), Set(0, 1, 4, 6))
    ).flatten,
    ChangRoberts.invariant
  )

  val entry: Option[AnyAlgorithm] = Some(AnyAlgorithm(algorithm))
}
