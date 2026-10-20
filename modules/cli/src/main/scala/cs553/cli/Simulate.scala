package cs553.cli

import cats.data.NonEmptyList
import cs553.core.algorithms.election.ChangRoberts
import cs553.core.algorithms.wave.Echo
import cs553.core.codec.RunJson
import cs553.core.protocol.Protocol
import cs553.core.registry.Algorithm
import cs553.core.registry.Registry
import cs553.core.rng.Rng
import cs553.core.rng.Seed
import cs553.core.topology.Topology
import cs553.core.topology.TopologyError
import cs553.core.trace.Inspect
import cs553.core.trace.Policy
import cs553.core.trace.RunSpec
import cs553.core.trace.StopRule
import cs553.core.world.RunError
import cs553.core.world.Simulator
import io.circe.Encoder

/** The result of `simulate`: the text for the terminal and the trace as JSON. */
final case class Report(text: List[String], json: String)

/** The `simulate` command as a pure function. `Main` reads the topology file and writes the output.
  */
object Simulate {

  /** Runs the command. `dot` is the text of the topology file, when the source is a file. */
  def run(args: SimulateArgs, dot: Option[String]): Either[String, Report] = {
    for {
      loaded <- load(args, dot)
      report <- args.alg match {
        case Alg.Echo           => echo(args, loaded)
        case Alg.ChangRoberts   => changRoberts(args, loaded)
        case Alg.Registered(id) =>
          Registry.find(id).toRight(s"unknown algorithm ${id.value}").flatMap { any =>
            registered(any.algorithm, args, loaded)
          }
      }
    } yield report
  }

  private def errors(prefix: String, list: NonEmptyList[TopologyError]): String = {
    (prefix :: list.toList.map(e => s"  - ${e.render}")).mkString("\n")
  }

  private def load(args: SimulateArgs, dot: Option[String]): Either[String, Topology] = {
    (args.source, dot) match {
      case (Source.File(path), Some(text)) =>
        Topology.fromDot(text).left.map(errors(s"$path is not a valid topology:", _))
      case (Source.File(path), None) => Left(s"no text for $path")
      case (Source.Ring(size), _)    =>
        args.alg match {
          case Alg.ChangRoberts =>
            ChangRoberts
              .randomRing(size, Rng.derive(Seed(args.seed)))
              .left
              .map(errors("cannot make the ring:", _))
          case Alg.Echo => Left("--ring is only for chang-roberts; give Echo a --topology")
          case Alg.Registered(id) =>
            Left(s"--ring is only for chang-roberts; give ${id.value} a --topology")
        }
    }
  }

  private def echo(args: SimulateArgs, loaded: Topology): Either[String, Report] = {
    for {
      prepared <- Echo.prepare(loaded, args.initiator).left.map { list =>
        val hint = args.initiator match {
          case None    => List("hint: use --initiator to choose the initiator.")
          case Some(_) => Nil
        }
        (errors("the topology does not meet the requirements of Echo:", list) :: hint)
          .mkString("\n")
      }
      notes = prepared.omitted.toList.map { n =>
        s"note: node ${n.value} has no edges, so the run omits it"
      }
      report <- simulate(Echo.protocol, args, prepared.topology, "Echo", notes)
    } yield report
  }

  private def changRoberts(args: SimulateArgs, loaded: Topology): Either[String, Report] = {
    for {
      chosen <- args.initiator.fold[Either[String, Topology]](Right(loaded)) { n =>
        loaded.withInitiators(Set(n)).left.map(errors("--initiator is not valid:", _))
      }
      uids = chosen.nodes.toList.map { n =>
        val uid = chosen.attrsOf(n).uid.fold("-")(_.value.toString)
        val mark = if chosen.initiators.contains(n) then { "*" }
        else { "" }
        s"${n.value}(uid $uid$mark)"
      }
      nodes = s"nodes: ${uids.mkString(", ")} (* marks an initiator)"
      report <- simulate(ChangRoberts.protocol, args, chosen, "Chang-Roberts", List(nodes))
    } yield report
  }

  /** An algorithm of the registry on the loaded topology, with `--initiator` as the only initiator.
    */
  private def registered[S, M, A, O](
      a: Algorithm[S, M, A, O],
      args: SimulateArgs,
      loaded: Topology
  ): Either[String, Report] = {
    for {
      chosen <- args.initiator.fold[Either[String, Topology]](Right(loaded)) { n =>
        loaded.withInitiators(Set(n)).left.map(errors("--initiator is not valid:", _))
      }
      report <- simulate(a.protocol, args, chosen, a.name, Nil)(using
        a.inspectState,
        a.inspectMsg,
        a.inspectAction,
        a.inspectOutput,
        a.codecState,
        a.codecMsg,
        a.codecAction,
        a.codecOutput
      )
    } yield report
  }

  private def simulate[S, M, A, O](
      p: Protocol[S, M, A, O],
      args: SimulateArgs,
      topology: Topology,
      name: String,
      notes: List[String]
  )(using
      Inspect[S],
      Inspect[M],
      Inspect[A],
      Inspect[O],
      Encoder[S],
      Encoder[M],
      Encoder[A],
      Encoder[O]
  ): Either[String, Report] = {
    val order = args.order.getOrElse(p.order.default)
    val spec = RunSpec(
      p.id,
      topology,
      order,
      Policy.Seeded(Seed(args.seed)),
      StopRule.AtQuiescence,
      args.stepLimit
    )
    val result = Simulator.run(p, spec).left.map {
      case RunError.BadTopology(list) =>
        errors(s"the topology does not meet the requirements of $name:", list)
      case other => s"the Simulator cannot run: $other"
    }
    result.map { simulation =>
      val run = simulation.run
      val edges = topology.edges.size
      val header =
        s"$name on ${topology.nodes.size} nodes and $edges channels, order $order, seed ${args.seed}"
      Report(header :: notes ++ Render.lines(run), RunJson.write(run))
    }
  }
}
