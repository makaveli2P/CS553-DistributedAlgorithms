package cs553.cli

import cs553.core.Catalogue

/** The command-line entry point. M3 adds the `record` and `run` commands. */
object Main {

  def main(args: Array[String]): Unit = {
    println(s"CS553 Distributed Algorithms: ${Catalogue.summary}")
  }
}
