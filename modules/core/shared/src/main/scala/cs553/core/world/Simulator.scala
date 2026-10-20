package cs553.core.world

import cats.data.NonEmptyList
import cs553.core.ids.AlgorithmId
import cs553.core.protocol.ChannelOrder
import cs553.core.protocol.Protocol
import cs553.core.rng.Rng
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
}

/** The result of a Simulator run: the trace and the world after each step. `worlds(k)` is the world
  * after `k` steps, so the UI can scrub without a backward step.
  */
final case class Simulation[S, M, A, O](run: Run[S, M, A, O], worlds: Vector[World[S, M]]) {
  def last: Option[World[S, M]] = worlds.lastOption
}

/** The engine in `core`. It runs on the JVM and on JavaScript. */
object Simulator {

  def run[S, M, A, O](
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
      start <- World.init(p, spec.topology, order).left.map(RunError.BadTopology(_))
      simulation <- {
        val started = TraceEvent.Started(spec, Engine.Simulator, start.states.toMap)
        val (rng, script) = spec.policy match {
          case Policy.Seeded(seed)    => (Some(Rng(seed)), Nil)
          case Policy.Script(choices) => (None, choices)
        }
        loop(p, spec, Cursor(rng, script), Vector(started), Vector(start), decided = false)
      }
    } yield simulation
  }

  /** The state of the policy: a generator for `Seeded`, the remaining choices for `Script`. */
  private final case class Cursor[A](rng: Option[Rng], script: List[Choice[A]]) {
    def pick(enabled: List[Choice[A]]): Option[(Choice[A], Cursor[A])] = {
      rng match {
        case Some(r) =>
          val (nextRng, index) = r.nextInt(enabled.size)
          enabled.lift(index).map(c => (c, Cursor(Some(nextRng), script)))
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
      events: Vector[TraceEvent[S, M, A, O]],
      worlds: Vector[World[S, M]],
      decided: Boolean
  ): Either[RunError[A], Simulation[S, M, A, O]] = {
    worlds.lastOption match {
      case None    => Right(Simulation(Run(events), worlds))
      case Some(w) =>
        val enabled = World.enabled(p, w)
        val stopAtDecision = spec.stop match {
          case StopRule.AtFirstDecision => decided
          case StopRule.AtQuiescence    => false
        }
        def finish(outcome: Outcome): Either[RunError[A], Simulation[S, M, A, O]] = {
          Right(
            Simulation(Run(events :+ TraceEvent.Finished(Stamp(w.step, 0, 0), outcome)), worlds)
          )
        }
        if stopAtDecision then { finish(Outcome.AlgorithmDecided) }
        else if enabled.isEmpty then { finish(Outcome.Quiescent) }
        else if w.step >= spec.stepLimit then { finish(Outcome.StepLimit) }
        else {
          cursor.pick(enabled) match {
            case None                 => finish(Outcome.ScriptExhausted)
            case Some((choice, next)) =>
              World.next(p, w, choice) match {
                case Left(error) => Left(RunError.BadChoice(w.step + 1, choice, error))
                case Right((after, stepEvents)) =>
                  val hasDecision = stepEvents.collectFirst { case TraceEvent.Decided(at, _, _) =>
                    at
                  }.nonEmpty
                  loop(p, spec, next, events ++ stepEvents, worlds :+ after, hasDecision)
              }
          }
        }
    }
  }
}
