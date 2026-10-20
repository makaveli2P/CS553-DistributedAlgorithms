package cs553.core.registry

import cs553.core.algorithms.election.CrAction
import cs553.core.algorithms.election.CrMsg
import cs553.core.algorithms.election.CrOutput
import cs553.core.algorithms.election.CrState
import cs553.core.algorithms.wave.EchoAction
import cs553.core.algorithms.wave.EchoMsg
import cs553.core.algorithms.wave.EchoOutput
import cs553.core.algorithms.wave.EchoState
import cs553.core.ids.AlgorithmId

/** The algorithms that the UI offers, in the order of `Catalogue.algorithms`.
  *
  * Each algorithm has its own file `<Name>Entry.scala` with `entry: Option[AnyAlgorithm]`. An
  * algorithm that is not ready has `entry = None`. To add an algorithm, change only its entry file:
  * this list already holds a line for each of the eight algorithms.
  */
object Registry {

  // Add new algorithms here. Each algorithm already has its line, in catalogue order.
  val all: List[AnyAlgorithm] = List(
    ChandyLamportEntry.entry,
    LaiYangEntry.entry,
    EchoEntry.entry,
    TarryEntry.entry,
    TreeEntry.entry,
    BrachaTouegEntry.entry,
    ChangRobertsEntry.entry,
    FranklinEntry.entry
  ).flatten

  def find(id: AlgorithmId): Option[AnyAlgorithm] = all.find(_.id == id)

  /** Short names for the tests of M1 to M3. New code uses `<Name>Entry.algorithm`. */
  val echo: Algorithm[EchoState, EchoMsg, EchoAction, EchoOutput] = EchoEntry.algorithm

  val changRoberts: Algorithm[CrState, CrMsg, CrAction, CrOutput] = ChangRobertsEntry.algorithm
}
