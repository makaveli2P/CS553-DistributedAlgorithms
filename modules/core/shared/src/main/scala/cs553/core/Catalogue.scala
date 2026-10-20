package cs553.core

import cs553.core.ids.AlgorithmId

/** The four families of algorithms in the course. */
enum Family {
  case Snapshot, Wave, Deadlock, Election
}

/** One algorithm of the course: its id, its display name and its family. */
final case class CatalogueEntry(id: AlgorithmId, name: String, family: Family)

/** The algorithms that the project implements, with the family of each one. The id of an entry is
  * the id that the registry uses when the algorithm is ready.
  */
object Catalogue {

  val algorithms: List[CatalogueEntry] = List(
    CatalogueEntry(AlgorithmId("chandy-lamport"), "Chandy-Lamport", Family.Snapshot),
    CatalogueEntry(AlgorithmId("lai-yang"), "Lai-Yang", Family.Snapshot),
    CatalogueEntry(AlgorithmId("echo"), "Echo", Family.Wave),
    CatalogueEntry(AlgorithmId("tarry"), "Tarry", Family.Wave),
    CatalogueEntry(AlgorithmId("tree"), "Tree", Family.Wave),
    CatalogueEntry(AlgorithmId("bracha-toueg"), "Bracha-Toueg", Family.Deadlock),
    CatalogueEntry(AlgorithmId("chang-roberts"), "Chang-Roberts", Family.Election),
    CatalogueEntry(AlgorithmId("franklin"), "Franklin", Family.Election)
  )

  val summary: String =
    s"${algorithms.size} algorithms in ${algorithms.map(_.family).distinct.size} families"
}
