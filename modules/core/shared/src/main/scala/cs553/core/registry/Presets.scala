package cs553.core.registry

import cats.data.NonEmptyList
import cs553.core.ids.NodeId
import cs553.core.ids.Uid
import cs553.core.topology.NodeAttrs
import cs553.core.topology.Preset
import cs553.core.topology.Role
import cs553.core.topology.Topology
import cs553.core.topology.TopologyError

/** Helpers that make the topology presets of the registry. Each one gives `None` when the input is
  * not valid. `RegistrySpec` makes sure that each preset of each algorithm loads.
  */
object Presets {

  private type Checked = Either[NonEmptyList[TopologyError], Topology]

  /** A preset from a DOT file in `fixtures/topologies`, after `prepare`. */
  def fromFixture(
      name: String,
      title: String,
      file: String,
      prepare: Topology => Checked = Right(_)
  ): Option[Preset] = {
    val prepared = for {
      text <- TopologyFiles.all
        .collectFirst { case (`file`, t) => t }
        .toRight(NonEmptyList.one(TopologyError.Requirement(s"no fixture $file")))
      loaded <- Topology.fromDot(text)
      ready <- prepare(loaded)
    } yield Preset(name, title, ready)
    prepared.toOption
  }

  /** A preset from DOT text, after `prepare`. */
  def fromDot(
      name: String,
      title: String,
      dot: String,
      prepare: Topology => Checked = Right(_)
  ): Option[Preset] = {
    Topology.fromDot(dot).flatMap(prepare).toOption.map(Preset(name, title, _))
  }

  /** A preset from a topology that a smart constructor made. */
  def of(name: String, title: String, topology: Checked): Option[Preset] = {
    topology.toOption.map(Preset(name, title, _))
  }

  /** The attributes of a ring: these uids in ring order, and these ring places as initiators. */
  def ringAttrs(uids: List[Int], initiators: Set[Int]): Map[NodeId, NodeAttrs] = {
    uids.zipWithIndex.map { case (uid, i) =>
      val role = if initiators.contains(i) then { Role.Initiator }
      else { Role.NonInitiator }
      NodeId(i) -> NodeAttrs(role, Some(Uid(uid)), Map.empty)
    }.toMap
  }

  /** A directed ring `0 -> 1 -> ...` with these uids in ring order and these initiators. */
  def ring(name: String, title: String, uids: List[Int], initiators: Set[Int]): Option[Preset] = {
    of(name, title, Topology.ring(uids.size, ringAttrs(uids, initiators)))
  }

  /** A ring with both directions of each edge, with these uids and these initiators. */
  def bidirectionalRing(
      name: String,
      title: String,
      uids: List[Int],
      initiators: Set[Int]
  ): Option[Preset] = {
    of(name, title, Topology.bidirectionalRing(uids.size, ringAttrs(uids, initiators)))
  }

  /** An undirected tree on the nodes `0 .. n-1` of the edges. */
  def tree(name: String, title: String, edges: List[(Int, Int)]): Option[Preset] = {
    val nodes = edges.flatMap { case (a, b) => List(a, b) }.distinct.sorted.map(NodeId(_))
    of(name, title, Topology.tree(nodes, edges.map { case (a, b) => (NodeId(a), NodeId(b)) }))
  }
}
