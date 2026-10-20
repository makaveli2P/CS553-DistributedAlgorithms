package cs553.core.registry

import cats.data.NonEmptyList
import cs553.core.Family
import cs553.core.algorithms.election.ChangRoberts
import cs553.core.algorithms.election.CrAction
import cs553.core.algorithms.election.CrMsg
import cs553.core.algorithms.election.CrOutput
import cs553.core.algorithms.election.CrState
import cs553.core.algorithms.wave.Echo
import cs553.core.algorithms.wave.EchoAction
import cs553.core.algorithms.wave.EchoMsg
import cs553.core.algorithms.wave.EchoOutput
import cs553.core.algorithms.wave.EchoState
import cs553.core.ids.AlgorithmId
import cs553.core.ids.NodeId
import cs553.core.ids.Uid
import cs553.core.topology.NodeAttrs
import cs553.core.topology.Preset
import cs553.core.topology.Role
import cs553.core.topology.Topology
import cs553.core.topology.TopologyError

/** The algorithms that the UI offers, with their topology presets. */
object Registry {

  val echo: Algorithm[EchoState, EchoMsg, EchoAction, EchoOutput] = Algorithm(
    Echo.protocol,
    "Echo",
    Family.Wave,
    "A wave from one initiator builds a spanning tree, and the echoes tell the initiator that " +
      "each node took part.",
    List(
      echoPreset("inputEcho", "inputEcho.dot", Some(NodeId(1))),
      echoPreset("inputTarry", "inputTarry.dot", Some(NodeId(1))),
      echoPreset("netgraph-6", "NetGraph_30-03-24-18-54-55.ngs.dot", None)
    ).flatten
  )

  val changRoberts: Algorithm[CrState, CrMsg, CrAction, CrOutput] = Algorithm(
    ChangRoberts.protocol,
    "Chang-Roberts",
    Family.Election,
    "Initiators on a directed ring send their uids around, and only the largest uid comes back.",
    List(
      ringPreset("ring-6", List(4, 1, 6, 2, 5, 3), Set(0, 2, 3)),
      ringPreset("ring-8", List(3, 7, 1, 8, 5, 2, 6, 4), Set(0, 1, 4, 6))
    ).flatten
  )

  val all: List[AnyAlgorithm] = List(AnyAlgorithm(echo), AnyAlgorithm(changRoberts))

  def find(id: AlgorithmId): Option[AnyAlgorithm] = all.find(_.id == id)

  /** An Echo preset from a fixture file, prepared with `Echo.prepare`. `None` when the file is
    * missing or not valid; `RegistrySpec` makes sure that each preset loads.
    */
  private def echoPreset(name: String, file: String, initiator: Option[NodeId]): Option[Preset] = {
    val prepared = for {
      text <- TopologyFiles.all
        .collectFirst { case (`file`, t) => t }
        .toRight(NonEmptyList.one(TopologyError.Requirement(s"no fixture $file")))
      loaded <- Topology.fromDot(text)
      ready <- Echo.prepare(loaded, initiator)
    } yield Preset(name, ready.topology)
    prepared.toOption
  }

  /** A directed ring `0 -> 1 -> ...` with these uids in ring order and these nodes as initiators.
    */
  private def ringPreset(name: String, uids: List[Int], initiators: Set[Int]): Option[Preset] = {
    val attrs = uids.zipWithIndex.map { case (uid, i) =>
      val role = if initiators.contains(i) then { Role.Initiator }
      else { Role.NonInitiator }
      NodeId(i) -> NodeAttrs(role, Some(Uid(uid)), Map.empty)
    }.toMap
    Topology.ring(uids.size, attrs).toOption.map(Preset(name, _))
  }
}
