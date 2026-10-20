package cs553.cli

import akka.actor.typed.ActorSystem
import akka.actor.typed.SpawnProtocol
import akka.util.Timeout

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import scala.util.Try

/** The command-line entry point. Only this object does IO. `Args` and `Simulate` are pure. */
object Main {

  /** A failure guard for one recorded run. A preset run ends in well under one second. */
  private val recordGuard: FiniteDuration = 60.seconds

  def main(args: Array[String]): Unit = {
    Args.parse(args.toList) match {
      case Left(error) =>
        System.err.println(s"error: $error\n\n${Args.usage}")
        sys.exit(2)
      case Right(Command.Help)          => println(Args.usage)
      case Right(Command.Simulate(sim)) => simulate(sim)
      case Right(Command.Record(rec))   => record(rec)
    }
  }

  /** Records one run in the mediated Akka engine.
    *
    * This is the very edge of the program. `main` is not an actor, and the JVM must not exit before
    * the run ends, so this function blocks on the one `Future` of the run with `Await`. No actor
    * blocks or waits (coding rules 22 and 25): the actors detect the end of the run with protocol
    * messages, and the timeout is only a failure guard.
    */
  private def record(args: RecordArgs): Unit = {
    // Record.plan is pure. Check the algorithm and the preset before Akka starts.
    Record.plan(args) match {
      case Left(error) =>
        System.err.println(s"error: $error")
        sys.exit(1)
      case Right(_) => recordChecked(args)
    }
  }

  private def recordChecked(args: RecordArgs): Unit = {
    val system = ActorSystem(SpawnProtocol(), "cs553")
    given Timeout = Timeout(recordGuard)
    given ExecutionContext = system.executionContext
    val recorded = Try(Await.result(Record.run(system, args), recordGuard)).toEither.left
      .map(e => s"the run did not end: ${e.getMessage}")
      .flatten
    system.terminate()
    val _ = Try(Await.ready(system.whenTerminated, recordGuard))
    val result = for {
      report <- recorded
      written <- args.out.fold[Either[String, Option[String]]](Right(None)) { path =>
        write(path, report.json).map(_ => Some(path))
      }
    } yield (report, written)
    result match {
      case Left(error) =>
        System.err.println(s"error: $error")
        sys.exit(1)
      case Right((report, written)) =>
        report.text.foreach(println)
        written.foreach(path => println(s"wrote the trace as JSON to $path"))
    }
  }

  private def simulate(args: SimulateArgs): Unit = {
    val result = for {
      dot <- args.source match {
        case Source.File(path) => read(path).map(Some(_))
        case Source.Ring(_)    => Right(None)
      }
      report <- Simulate.run(args, dot)
      written <- args.out.fold[Either[String, Option[String]]](Right(None)) { path =>
        write(path, report.json).map(_ => Some(path))
      }
    } yield (report, written)
    result match {
      case Left(error) =>
        System.err.println(s"error: $error")
        sys.exit(1)
      case Right((report, written)) =>
        report.text.foreach(println)
        written.foreach(path => println(s"wrote the trace as JSON to $path"))
    }
  }

  private def read(path: String): Either[String, String] = {
    Try(new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8)).toEither.left
      .map(e => s"cannot read $path: ${e.getMessage}")
  }

  private def write(path: String, text: String): Either[String, Unit] = {
    Try {
      Option(Paths.get(path).toAbsolutePath.getParent).foreach(Files.createDirectories(_))
      Files.write(Paths.get(path), text.getBytes(StandardCharsets.UTF_8))
    }.toEither.left
      .map(e => s"cannot write $path: ${e.getMessage}")
      .map(_ => ())
  }
}
