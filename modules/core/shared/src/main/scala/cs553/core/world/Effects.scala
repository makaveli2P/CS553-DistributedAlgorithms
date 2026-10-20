package cs553.core.world

import cs553.core.protocol.NodeCtx
import cs553.core.protocol.ProtocolError
import cs553.core.protocol.Step
import cs553.core.topology.Edge
import cs553.core.trace.Choice
import cs553.core.trace.Envelope
import cs553.core.trace.EnvelopeId
import cs553.core.trace.Stamp
import cs553.core.trace.TraceEvent

/** What one handler result does to a run: the new state of the node, the envelopes to buffer and
  * the trace events.
  *
  * `Effects.of` holds the rules that each engine must follow, so that the Simulator and the M3 Akka
  * engine write the same trace:
  *
  *   - A send to a node that is not an out-neighbour makes the step `Rejected` with
  *     `NotANeighbour`, and the step sends nothing.
  *   - The sender assigns `seq` for each channel, from the next sequence number of the channel.
  *   - All events of the step have the stamp of the step. Each envelope has the Lamport time of the
  *     step.
  *   - The events are `Stepped`, then one `Sent` for each envelope in send order, then `Decided`.
  *   - A rejected step keeps the old state. It still uses one step and one local event.
  */
final case class Effects[S, M, A, O](
    at: Stamp,
    state: Option[S],
    sent: List[Buffered[M]],
    events: List[TraceEvent[S, M, A, O]]
)

object Effects {

  /** The effects of `result`, the handler result for `cause` at the node of `ctx`.
    *
    * `step` is the global number of this step, and `clock` is the clock of the node after this
    * event. `nextSeq` gives the next sequence number of each channel before this step.
    */
  def of[S, M, A, O](
      step: Int,
      ctx: NodeCtx,
      cause: Choice[A],
      clock: Clock,
      nextSeq: Edge => Int,
      result: Either[ProtocolError, Step[S, M, O]]
  ): Effects[S, M, A, O] = {
    val at = Stamp(step, clock.local, clock.lamport)
    val checked = result.flatMap { s =>
      s.sends.find(send => !ctx.out.contains(send.to)) match {
        case Some(bad) => Left(ProtocolError.NotANeighbour(bad.to))
        case None      => Right(s)
      }
    }
    checked match {
      case Left(error) =>
        Effects(at, None, Nil, List(TraceEvent.Rejected(at, ctx.self, cause, error)))
      case Right(s) =>
        val (_, envs) = s.sends.foldLeft((Map.empty[Edge, Int], Vector.empty[Envelope[M]])) {
          case ((used, acc), send) =>
            val edge = Edge(ctx.self, send.to)
            val earlier = used.getOrElse(edge, 0)
            val env = Envelope(EnvelopeId(ctx.self, send.to, nextSeq(edge) + earlier), send.msg)
            (used.updated(edge, earlier + 1), acc :+ env)
        }
        val stepped =
          TraceEvent.Stepped(at, ctx.self, cause, s.rule, s.state, envs.toList.map(_.id))
        val sends = envs.toList.map(env => TraceEvent.Sent(at, env))
        val decided = s.decision.toList.map(o => TraceEvent.Decided(at, ctx.self, o))
        Effects(
          at,
          Some(s.state),
          envs.toList.map(Buffered(_, at.lamport)),
          stepped :: sends ++ decided
        )
    }
  }
}
