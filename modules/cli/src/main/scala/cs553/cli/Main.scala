package cs553.cli

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import scala.util.Try

/** The command-line entry point. Only this object does IO. `Args` and `Simulate` are pure. */
object Main {

  def main(args: Array[String]): Unit = {
    Args.parse(args.toList) match {
      case Left(error) =>
        System.err.println(s"error: $error\n\n${Args.usage}")
        sys.exit(2)
      case Right(Command.Help)          => println(Args.usage)
      case Right(Command.Simulate(sim)) => simulate(sim)
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
    Try(Files.write(Paths.get(path), text.getBytes(StandardCharsets.UTF_8))).toEither.left
      .map(e => s"cannot write $path: ${e.getMessage}")
      .map(_ => ())
  }
}
