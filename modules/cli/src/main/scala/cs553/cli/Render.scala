package cs553.cli

import cs553.core.ids.NodeId
import cs553.core.trace.Choice
import cs553.core.trace.EnvelopeId
import cs553.core.trace.Inspect
import cs553.core.trace.Run
import cs553.core.trace.TraceEvent

/** Renders a trace as readable text lines. Each line comes from one `TraceEvent`. */
object Render {

  def envelope(id: EnvelopeId): String = s"${id.from.value}->${id.to.value}#${id.seq}"

  def lines[S, M, A, O](
      run: Run[S, M, A, O]
  )(using s: Inspect[S], m: Inspect[M], a: Inspect[A], o: Inspect[O]): List[String] = {
    val messages: Map[EnvelopeId, M] = run.trace.collect { case TraceEvent.Sent(_, env) =>
      env.id -> env.msg
    }.toMap
    def msg(id: EnvelopeId): String = messages.get(id).fold("?")(m.label)
    def cause(c: Choice[A]): String = c match {
      case Choice.Deliver(id)     => s"${msg(id)} from ${id.from.value} (${envelope(id)})"
      case Choice.Fire(_, action) => a.label(action)
      case Choice.Drop(id)        => s"drop ${envelope(id)}"
    }
    def node(n: NodeId): String = f"node ${n.value}%-3d"
    run.trace.toList.flatMap {
      case TraceEvent.Started(_, _, initial) =>
        "initial states:" :: initial.toList.sortBy(_._1).map { case (n, state) =>
          s"  ${node(n)} ${s.label(state)}"
        }
      case TraceEvent.Sent(_, _)                           => Nil
      case TraceEvent.Stepped(at, n, c, rule, after, sent) =>
        val sends =
          if sent.isEmpty then { "" }
          else { sent.map(id => s"${msg(id)} to ${id.to.value}").mkString("  sends ", ", ", "") }
        List(
          f"#${at.step}%-4d ${node(n)} ${cause(c)}%-28s [${rule.value}] -> ${s.label(after)}$sends"
        )
      case TraceEvent.Rejected(at, n, c, error) =>
        List(f"#${at.step}%-4d ${node(n)} ${cause(c)}%-28s REJECTED: $error")
      case TraceEvent.Dropped(at, id)       => List(f"#${at.step}%-4d dropped ${envelope(id)}")
      case TraceEvent.Decided(_, n, output) => List(s"      ${node(n)} DECIDES ${o.label(output)}")
      case TraceEvent.Finished(at, outcome) =>
        val sentCount = messages.size
        val decisions = run.decisions.map { case (n, out) => s"node ${n.value} ${o.label(out)}" }
        val decided = if decisions.isEmpty then { "none" }
        else { decisions.mkString(", ") }
        List(
          s"finished: $outcome after ${at.step} steps, $sentCount messages",
          s"decisions: $decided"
        )
    }
  }
}
