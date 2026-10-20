package cs553.core.world

import cats.data.NonEmptyList
import cs553.core.ids.AlgorithmId
import cs553.core.protocol.ChannelOrder
import cs553.core.protocol.Protocol
import cs553.core.rng.Rng
import cs553.core.rng.Seed
import cs553.core.topology.TopologyError
import cs553.core.trace.Choice
import cs553.core.trace.Engine
import cs553.core.trace.Outcome
import cs553.core.trace.Policy
import cs553.core.trace.Run
import cs553.core.trace.RunSpec
import cs553.core.trace.Stamp
import cs553.core.trace.StopRule
import cs553.core.trace.TraceEvent

import scala.annotation.tailrec

/** Why the Simulator could not run a spec. */
enum RunError[+A] {
  case AlgorithmMismatch(expected: AlgorithmId, found: AlgorithmId)
  case OrderLocked(required: ChannelOrder, requested: ChannelOrder)
  case BadStepLimit(limit: Int)
  case BadTopology(errors: NonEmptyList[TopologyError])

  /** A `Script` choice at this step is not legal. */
  case BadChoice(step: Int, choice: Choice[A], error: ChoiceError)

  /** A choice comes after the run stopped with this outcome. */
  case AfterStop(step: Int, choice: Choice[A], outcome: Outcome)
}

/** The result of a Simulator run: the trace and the world after each step. `worlds(k)` is the world
  * after `k` steps, so the UI can scrub without a backward step.
  */
final case class Simulation[S, M, A, O](run: Run[S, M, A, O], worlds: Vector[World[S, M]]) {
  def last: Option[World[S, M]] = worlds.lastOption

  /** The choice of each step, in order. `Simulator.replay` of these choices gives this simulation
    * again.
    */
  def choices: List[Choice[A]] = run.trace.toList.collect {
    case TraceEvent.Stepped(_, _, cause, _, _, _) => cause
    case TraceEvent.Rejected(_, _, cause, _)      => cause
    case TraceEvent.Dropped(_, id)                => Choice.Drop(id)
  }
}

/** The engine in `core`. It runs on the JVM and on JavaScript.
  *
  * `run` applies the policy of the spec until the run stops. A UI that lets a learner choose each
  * step uses `start` and `advance` (or `replay` for a list of choices), and `seededChoice` for the
  * step that the seed picks.
  */
object Simulator {

  def run[S, M, A, O](
      p: Protocol[S, M, A, O],
      spec: RunSpec[A]
  ): Either[RunError[A], Simulation[S, M, A, O]] = {
    start(p, spec).flatMap { started =>
      val (rng, script) = spec.policy match {
        case Policy.Seeded(seed)    => (Some(Rng(seed)), Nil)
        case Policy.Script(choices) => (None, choices)
      }
      loop(p, spec, Cursor(rng, script), started)
    }
  }

  /** The simulation before the first step: only `Started`, or also `Finished` when the run stops at
    * once. The policy of `spec` does not matter.
    */
  def start[S, M, A, O](
      p: Protocol[S, M, A, O],
      spec: RunSpec[A]
  ): Either[RunError[A], Simulation[S, M, A, O]] = {
    for {
      _ <- {
        if spec.algorithm == p.id then { Right(()) }
        else { Left(RunError.AlgorithmMismatch(p.id, spec.algorithm)) }
      }
      order <- p.order.resolve(spec.order).left.map(RunError.OrderLocked(_, spec.order))
      _ <-
        if spec.stepLimit >= 0 then { Right(()) }
        else { Left(RunError.BadStepLimit(spec.stepLimit)) }
      world <- World.init(p, spec.topology, order).left.map(RunError.BadTopology(_))
      started = Simulation[S, M, A, O](
        Run(Vector(TraceEvent.Started(spec, Engine.Simulator, world.states))),
        Vector(world)
      )
      simulation <- advance(p, spec, started, Nil)
    } yield simulation
  }

  /** Applies `choices` in order to the end of `simulation`, and adds `Finished` when the spec stops
    * the run after the last choice. The policy of `spec` does not matter.
    *
    * A choice that `World.next` refuses gives `BadChoice`. A choice after the run stopped gives
    * `AfterStop`. With no choices, the result is `simulation` with `Finished` when the run stops.
    */
  @tailrec
  def advance[S, M, A, O](
      p: Protocol[S, M, A, O],
      spec: RunSpec[A],
      simulation: Simulation[S, M, A, O],
      choices: List[Choice[A]]
  ): Either[RunError[A], Simulation[S, M, A, O]] = {
    (simulation.last, simulation.run.outcome) match {
      case (None, _)                => Right(simulation)
      case (Some(w), Some(outcome)) =>
        choices match {
          case Nil         => Right(simulation)
          case choice :: _ => Left(RunError.AfterStop(w.step + 1, choice, outcome))
        }
      case (Some(w), None) =>
        (choices, stopAt(p, spec, w, simulation.run)) match {
          case (Nil, None)               => Right(simulation)
          case (Nil, Some(outcome))      => Right(finish(simulation, w, outcome))
          case (choice :: _, Some(stop)) => Left(RunError.AfterStop(w.step + 1, choice, stop))
          case (choice :: rest, None)    =>
            World.next(p, w, choice) match {
              case Left(error)                => Left(RunError.BadChoice(w.step + 1, choice, error))
              case Right((after, stepEvents)) =>
                val next = Simulation(
                  Run(simulation.run.trace ++ stepEvents),
                  simulation.worlds :+ after
                )
                advance(p, spec, next, rest)
            }
        }
    }
  }

  /** The simulation of `choices` from the start of `spec`. The UI uses it for a shared link and for
    * "continue from step k" with `choices.take(k)`. The policy of `spec` does not matter.
    */
  def replay[S, M, A, O](
      p: Protocol[S, M, A, O],
      spec: RunSpec[A],
      choices: List[Choice[A]]
  ): Either[RunError[A], Simulation[S, M, A, O]] = {
    start(p, spec).flatMap(advance(p, spec, _, choices))
  }

  /** The choice that `Policy.Seeded(seed)` makes at the step after `step` steps, from `enabled`.
    * Each step uses one value of the generator. So when each step is a seeded choice, the result is
    * the same as `run` with the seed.
    */
  def seededChoice[A](seed: Seed, step: Int, enabled: List[Choice[A]]): Option[Choice[A]] = {
    val rng = (0 until step).foldLeft(Rng(seed))((r, _) => r.nextLong._1)
    rng.pick(enabled).map(_._2)
  }

  /** Why the run stops at `w`, or `None` when it continues. `run` is the trace up to `w`. */
  def stopAt[S, M, A, O](
      p: Protocol[S, M, A, O],
      spec: RunSpec[A],
      w: World[S, M],
      run: Run[S, M, A, O]
  ): Option[Outcome] = {
    stopWhen(spec, w.step, World.enabled(p, w), run.trace.lastOption)
  }

  /** The rule of `stopAt` on plain data, so that an engine with no `World` stops at the same step.
    *
    * `step` is the number of steps so far, `enabled` the legal choices now and `last` the last
    * trace event so far.
    */
  def stopWhen[A](
      spec: RunSpec[A],
      step: Int,
      enabled: List[Choice[A]],
      last: Option[TraceEvent[Any, Any, Any, Any]]
  ): Option[Outcome] = {
    val decidedLast = last match {
      case Some(TraceEvent.Decided(_, _, _)) => true
      case Some(_) | None                    => false
    }
    val stopAtDecision = spec.stop match {
      case StopRule.AtFirstDecision => decidedLast
      case StopRule.AtQuiescence    => false
    }
    if stopAtDecision then { Some(Outcome.AlgorithmDecided) }
    else if enabled.isEmpty then { Some(Outcome.Quiescent) }
    else if step >= spec.stepLimit then { Some(Outcome.StepLimit) }
    else { None }
  }

  /** The `Finished` event of a run that stops after `step` steps. */
  def finishedAt(step: Int, outcome: Outcome): TraceEvent[Nothing, Nothing, Nothing, Nothing] = {
    TraceEvent.Finished(Stamp(step, 0, 0), outcome)
  }

  private def finish[S, M, A, O](
      simulation: Simulation[S, M, A, O],
      w: World[S, M],
      outcome: Outcome
  ): Simulation[S, M, A, O] = {
    simulation.copy(run = Run(simulation.run.trace :+ finishedAt(w.step, outcome)))
  }

  /** The state of the policy: a generator for `Seeded`, the remaining choices for `Script`. */
  private final case class Cursor[A](rng: Option[Rng], script: List[Choice[A]]) {
    def pick(enabled: List[Choice[A]]): Option[(Choice[A], Cursor[A])] = {
      rng match {
        case Some(r) =>
          r.pick(enabled).map { case (nextRng, c) => (c, Cursor(Some(nextRng), script)) }
        case None =>
          script match {
            case head :: tail => Some((head, Cursor(None, tail)))
            case Nil          => None
          }
      }
    }
  }

  @tailrec
  private def loop[S, M, A, O](
      p: Protocol[S, M, A, O],
      spec: RunSpec[A],
      cursor: Cursor[A],
      simulation: Simulation[S, M, A, O]
  ): Either[RunError[A], Simulation[S, M, A, O]] = {
    (simulation.last, simulation.run.outcome) match {
      case (None, _) | (_, Some(_)) => Right(simulation)
      case (Some(w), None)          =>
        cursor.pick(World.enabled(p, w)) match {
          case None                 => Right(finish(simulation, w, Outcome.ScriptExhausted))
          case Some((choice, next)) =>
            advance(p, spec, simulation, List(choice)) match {
              case Left(error)  => Left(error)
              case Right(after) => loop(p, spec, next, after)
            }
        }
    }
  }
}
