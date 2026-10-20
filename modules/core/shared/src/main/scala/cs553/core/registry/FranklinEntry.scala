package cs553.core.registry

import cs553.core.Family
import cs553.core.algorithms.election.FrAction
import cs553.core.algorithms.election.FrMsg
import cs553.core.algorithms.election.FrOutput
import cs553.core.algorithms.election.FrState
import cs553.core.algorithms.election.Franklin

/** The registry entry of Franklin. */
object FranklinEntry {

  val algorithm: Algorithm[FrState, FrMsg, FrAction, FrOutput] = Algorithm(
    Franklin.protocol,
    "Franklin",
    Family.Election,
    "Candidates on a two-way ring compare uids with both nearest candidates, and each round removes half or more.",
    List(
      Presets.bidirectionalRing(
        "ring-8",
        "Ring of 8",
        List(3, 7, 1, 8, 5, 2, 6, 4),
        (0 until 8).toSet
      ),
      Presets.bidirectionalRing(
        "college-5",
        "College ring of 5",
        List(0, 1, 2, 3, 4),
        (0 until 5).toSet
      ),
      Presets.bidirectionalRing(
        "ring-6-some",
        "Ring of 6, three initiators",
        List(4, 1, 6, 2, 5, 3),
        Set(0, 3, 4)
      )
    ).flatten,
    Franklin.invariant
  )

  val entry: Option[AnyAlgorithm] = Some(AnyAlgorithm(algorithm))
}
