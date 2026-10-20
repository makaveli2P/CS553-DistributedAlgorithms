package cs553.core.golden

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import scala.util.Try

/** Writes each golden trace to `fixtures/traces`. `sbt goldenPromote` runs it with the repository
  * root as the only argument.
  *
  * This object is JVM test code, because it writes files. Core main code does no IO (coding rule
  * 2).
  */
object GoldenPromote {

  def main(args: Array[String]): Unit = {
    args.toList match {
      case root :: Nil =>
        val dir = Paths.get(root, "fixtures", "traces")
        val results = Goldens.cases.map { c =>
          Goldens.render(c).flatMap(text => write(dir.resolve(c.path), text)).map(_ => c.path)
        }
        results.foreach {
          case Right(path) => println(s"wrote fixtures/traces/$path")
          case Left(error) => System.err.println(s"error: $error")
        }
        if results.exists(_.isLeft) then { sys.exit(1) }
      case _ =>
        System.err.println("usage: GoldenPromote <repository root>")
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
