package cs553.cli

import akka.actor.typed.ActorSystem
import akka.actor.typed.SpawnProtocol
import akka.util.Timeout
import cs553.core.golden.Goldens

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.util.Try

/** Records each golden case with the CLI `record` command (the mediated Akka engine), and writes
  * the trace to `fixtures/traces/<alg>/<preset>-akka-mediated-<seed>.json`.
  *
  * `sbt goldenPromote` runs it after `GoldenPromote` when `AKKA_REPO_TOKEN` is set. It is test
  * code, because it reads the golden cases from the core test code. Like `Main`, it is the edge of
  * the program, so it waits for the one `Future` of all runs with `Await`. The timeout is a failure
  * guard.
  */
object GoldenRecord {

  private val guard: FiniteDuration = 120.seconds

  def main(args: Array[String]): Unit = {
    args.toList match {
      case root :: Nil =>
        val dir = Paths.get(root, "fixtures", "traces")
        val system = ActorSystem(SpawnProtocol(), "golden-record")
        given Timeout = Timeout(guard)
        given ExecutionContext = system.executionContext
        val runs = Goldens.cases.foldLeft(Future.successful(List.empty[Either[String, String]])) {
          (acc, c) =>
            acc.flatMap { done =>
              val recordArgs =
                RecordArgs(RecordEngine.Mediated, c.algorithm.id, c.preset, c.seed, None)
              Record.run(system, recordArgs).map { result =>
                done :+ result.flatMap(r => write(dir.resolve(c.mediatedPath), r.json)).map { _ =>
                  c.mediatedPath
                }
              }
            }
        }
        val results = Try(Await.result(runs, guard)).toEither.left
          .map(e => List(Left(s"the runs did not end: ${e.getMessage}")))
          .merge
        system.terminate()
        results.foreach {
          case Right(path) => println(s"wrote fixtures/traces/$path")
          case Left(error) => System.err.println(s"error: $error")
        }
        if results.exists(_.isLeft) then { sys.exit(1) }
      case _ =>
        System.err.println("usage: GoldenRecord <repository root>")
        sys.exit(2)
    }
  }

  private def write(path: Path, text: String): Either[String, Unit] = {
    Try {
      Files.createDirectories(path.getParent)
      Files.write(path, text.getBytes(StandardCharsets.UTF_8))
    }.toEither.left.map(e => s"cannot write $path: ${e.getMessage}").map(_ => ())
  }
}
