package cs553.cli

import cs553.core.ids.NodeId
import cs553.core.protocol.ChannelOrder

import scala.annotation.tailrec
import scala.util.Try

/** The algorithms that the CLI can run. */
enum Alg {
  case Echo, ChangRoberts
}

/** Where the topology comes from. */
enum Source {

  /** A DOT file. */
  case File(path: String)

  /** A directed ring of this size, with seeded uids and initiators (Chang-Roberts only). */
  case Ring(size: Int)
}

/** The arguments of `simulate`. */
final case class SimulateArgs(
    alg: Alg,
    source: Source,
    seed: Long,
    order: Option[ChannelOrder],
    initiator: Option[NodeId],
    out: Option[String],
    stepLimit: Int
)

/** One CLI command. */
enum Command {
  case Simulate(args: SimulateArgs)
  case Help
}

/** Parses the command line. The parser is pure: it returns `Left` with a message for bad input. */
object Args {

  val usage: String =
    """Usage:
      |  simulate --alg echo|chang-roberts (--topology FILE.dot | --ring N) [options]
      |
      |Options:
      |  --seed N         the seed of the scheduler (default 0)
      |  --order O        fifo or any (default: the default order of the algorithm)
      |  --initiator N    make node N the only initiator
      |  --steps N        the step limit (default 100000)
      |  --out FILE.json  write the trace as JSON
      |
      |Examples:
      |  simulate --alg echo --topology fixtures/topologies/inputEcho.dot --initiator 1 --seed 7
      |  simulate --alg chang-roberts --ring 6 --seed 7 --out ring.json""".stripMargin

  private val keys: Set[String] =
    Set("--alg", "--topology", "--ring", "--seed", "--order", "--initiator", "--steps", "--out")

  def parse(args: List[String]): Either[String, Command] = {
    args match {
      case Nil                             => Right(Command.Help)
      case ("help" | "--help" | "-h") :: _ => Right(Command.Help)
      case "simulate" :: rest              => options(rest, Map.empty).flatMap(simulate)
      case other :: _                      => Left(s"unknown command '$other'")
    }
  }

  @tailrec
  private def options(
      args: List[String],
      acc: Map[String, String]
  ): Either[String, Map[String, String]] = {
    args match {
      case Nil                             => Right(acc)
      case key :: _ if !keys.contains(key) => Left(s"unknown option '$key'")
      case key :: _ if acc.contains(key)   => Left(s"the option $key occurs two times")
      case key :: value :: rest if !value.startsWith("--") => options(rest, acc.updated(key, value))
      case key :: _                                        => Left(s"the option $key needs a value")
    }
  }

  private def simulate(opts: Map[String, String]): Either[String, Command] = {
    for {
      alg <- opts.get("--alg") match {
        case Some("echo")          => Right(Alg.Echo)
        case Some("chang-roberts") => Right(Alg.ChangRoberts)
        case Some(other) => Left(s"unknown algorithm '$other' (use echo or chang-roberts)")
        case None        => Left("the option --alg is necessary")
      }
      source <- (opts.get("--topology"), opts.get("--ring")) match {
        case (Some(path), None) => Right(Source.File(path))
        case (None, Some(size)) => int("--ring", size).map(Source.Ring(_))
        case (Some(_), Some(_)) => Left("use --topology or --ring, not both")
        case (None, None)       => Left("the option --topology or --ring is necessary")
      }
      seed <- opts
        .get("--seed")
        .fold[Either[String, Long]](Right(0L))(s =>
          Try(s.toLong).toOption.toRight(s"--seed needs an integer, not '$s'")
        )
      order <- opts.get("--order") match {
        case None         => Right(None)
        case Some("fifo") => Right(Some(ChannelOrder.Fifo))
        case Some("any")  => Right(Some(ChannelOrder.AnyOrder))
        case Some(other)  => Left(s"--order needs fifo or any, not '$other'")
      }
      initiator <- opts
        .get("--initiator")
        .fold[Either[String, Option[NodeId]]](Right(None))(s =>
          int("--initiator", s).map(n => Some(NodeId(n)))
        )
      steps <- opts.get("--steps").fold[Either[String, Int]](Right(100000))(s => int("--steps", s))
    } yield Command.Simulate(
      SimulateArgs(alg, source, seed, order, initiator, opts.get("--out"), steps)
    )
  }

  private def int(key: String, text: String): Either[String, Int] = {
    Try(text.toInt).toOption
      .filter(_ >= 0)
      .toRight(s"$key needs a non-negative integer, not '$text'")
  }
}
