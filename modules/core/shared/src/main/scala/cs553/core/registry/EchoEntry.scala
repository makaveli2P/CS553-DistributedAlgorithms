package cs553.core.registry

import cs553.core.Family
import cs553.core.algorithms.wave.Echo
import cs553.core.algorithms.wave.EchoAction
import cs553.core.algorithms.wave.EchoMsg
import cs553.core.algorithms.wave.EchoOutput
import cs553.core.algorithms.wave.EchoState
import cs553.core.ids.NodeId

/** The registry entry of Echo. */
object EchoEntry {

  val algorithm: Algorithm[EchoState, EchoMsg, EchoAction, EchoOutput] = Algorithm(
    Echo.protocol,
    "Echo",
    Family.Wave,
    "A wave from one initiator builds a spanning tree, and the echoes tell the initiator that " +
      "each node took part.",
    List(
      fixture("inputEcho", "Five nodes, one leaf", "inputEcho.dot", Some(NodeId(1))),
      fixture("inputTarry", "Six nodes in three layers", "inputTarry.dot", Some(NodeId(1))),
      fixture("netgraph-6", "Six-node network", "NetGraph_30-03-24-18-54-55.ngs.dot", None)
    ).flatten,
    Echo.invariant
  )

  val entry: Option[AnyAlgorithm] = Some(AnyAlgorithm(algorithm))

  /** An Echo preset from a fixture file, prepared with `Echo.prepare`. */
  private def fixture(name: String, title: String, file: String, initiator: Option[NodeId]) = {
    Presets.fromFixture(name, title, file, t => Echo.prepare(t, initiator).map(_.topology))
  }
}
