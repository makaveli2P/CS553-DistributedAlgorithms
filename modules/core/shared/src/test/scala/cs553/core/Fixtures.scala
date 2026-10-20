package cs553.core

import cs553.core.registry.TopologyFiles

/** The topology files in `fixtures/topologies`, by file name. */
object Fixtures {
  val topologies: List[(String, String)] = TopologyFiles.all
}
