package cs553.core

/** The four families of algorithms in the course. */
enum Family {
  case Snapshot, Wave, Deadlock, Election
}

/** The algorithms that the project implements, with the family of each one.
  *
  * M0 uses this value only to prove that each module can read `core`.
  */
object Catalogue {

  val algorithms: List[(String, Family)] = List(
    "Chandy-Lamport" -> Family.Snapshot,
    "Lai-Yang" -> Family.Snapshot,
    "Echo" -> Family.Wave,
    "Tarry" -> Family.Wave,
    "Tree" -> Family.Wave,
    "Bracha-Toueg" -> Family.Deadlock,
    "Chang-Roberts" -> Family.Election,
    "Franklin" -> Family.Election
  )

  val summary: String =
    s"${algorithms.size} algorithms in ${algorithms.map(_._2).distinct.size} families"
}
