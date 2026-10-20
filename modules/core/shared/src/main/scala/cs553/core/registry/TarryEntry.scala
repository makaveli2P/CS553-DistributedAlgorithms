package cs553.core.registry

import cs553.core.Family
import cs553.core.algorithms.wave.Tarry
import cs553.core.algorithms.wave.TarryAction
import cs553.core.algorithms.wave.TarryMsg
import cs553.core.algorithms.wave.TarryOutput
import cs553.core.algorithms.wave.TarryState
import cs553.core.ids.NodeId

/** The registry entry of Tarry. */
object TarryEntry {

  val algorithm: Algorithm[TarryState, TarryMsg, TarryAction, TarryOutput] = Algorithm(
    Tarry.protocol,
    "Tarry",
    Family.Wave,
    "One token visits each node and goes through each channel once in each direction, and then " +
      "comes back to the initiator.",
    List(
      fixture("inputTarry", "Six nodes in three layers", "inputTarry.dot", Some(NodeId(1))),
      fixture("netgraph-6", "Six-node network", "NetGraph_30-03-24-18-54-55.ngs.dot", None),
      fixture("inputEcho", "Five nodes, one leaf", "inputEcho.dot", Some(NodeId(1)))
    ).flatten,
    Tarry.invariant
  )

  val entry: Option[AnyAlgorithm] = Some(AnyAlgorithm(algorithm))

  /** A Tarry preset from a fixture file, prepared with `Tarry.prepare`. */
  private def fixture(name: String, title: String, file: String, initiator: Option[NodeId]) = {
    Presets.fromFixture(name, title, file, t => Tarry.prepare(t, initiator).map(_.topology))
  }
}
