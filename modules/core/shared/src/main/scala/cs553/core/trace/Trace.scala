package cs553.core.trace

import cs553.core.ids.AlgorithmId
import cs553.core.ids.NodeId
import cs553.core.ids.RuleId
import cs553.core.protocol.ChannelOrder
import cs553.core.protocol.ProtocolError
import cs553.core.rng.Seed
import cs553.core.topology.Edge
import cs553.core.topology.Topology
import io.circe.Codec
import io.circe.Decoder
import io.circe.Encoder

import scala.collection.immutable.SortedMap

/** The id of one envelope. The sender assigns `seq` for each channel, from 0. So the id is the same
  * in each engine and in each replay.
  */
final case class EnvelopeId(from: NodeId, to: NodeId, seq: Int) derives Codec.AsObject {
  def channel: Edge = Edge(from, to)
}

object EnvelopeId {
  given Ordering[EnvelopeId] =
    Ordering.by[EnvelopeId, (NodeId, NodeId, Int)](e => (e.from, e.to, e.seq))
}

/** One message on a channel, with its id. */
final case class Envelope[+M](id: EnvelopeId, msg: M)

object Envelope {
  given [M: Encoder]: Encoder.AsObject[Envelope[M]] = Encoder.AsObject.derived
  given [M: Decoder]: Decoder[Envelope[M]] = Decoder.derived
}

/** One choice of a scheduler. One step applies one choice. */
enum Choice[+A] {

  /** Deliver the envelope to its receiver. */
  case Deliver(id: EnvelopeId)

  /** Let the node do one of its spontaneous actions. */
  case Fire(node: NodeId, action: A)

  /** Remove the envelope from its channel. The receiver never gets it. */
  case Drop(id: EnvelopeId)
}

object Choice {
  given [A: Encoder]: Encoder.AsObject[Choice[A]] = Encoder.AsObject.derived
  given [A: Decoder]: Decoder[Choice[A]] = Decoder.derived
}

/** The time of one event: the global step, the node-local event counter and the Lamport clock.
  * Observer events (`Dropped`, `Finished`) have `local = 0` and `lamport = 0`.
  */
final case class Stamp(step: Int, local: Int, lamport: Int) derives Codec.AsObject

/** Why a run stopped. */
enum Outcome derives Codec.AsObject {

  /** No choice is enabled. Only an outside observer knows this. */
  case Quiescent

  /** A node decided, and the run spec says to stop at the first decision. */
  case AlgorithmDecided

  /** The run reached the step limit of the run spec. */
  case StepLimit

  /** A `Script` policy has no more choices, and choices are still enabled. */
  case ScriptExhausted
}

/** The engine that wrote a trace. */
enum Engine derives Codec.AsObject {
  case Simulator, AkkaMediated, AkkaFreeRun
}

/** How the Simulator picks the next choice. */
enum Policy[+A] {

  /** Pick from the canonical `enabled` list with SplitMix64. */
  case Seeded(seed: Seed)

  /** Replay these choices in order. */
  case Script(choices: List[Choice[A]])
}

object Policy {
  given [A: Encoder]: Encoder.AsObject[Policy[A]] = Encoder.AsObject.derived
  given [A: Decoder]: Decoder[Policy[A]] = Decoder.derived
}

/** When the Simulator stops before the step limit. */
enum StopRule derives Codec.AsObject {

  /** Stop when no choice is enabled. */
  case AtQuiescence

  /** Stop after the first step that has a decision, or when no choice is enabled. */
  case AtFirstDecision
}

/** A complete definition of one run. The same spec always gives the same trace. */
final case class RunSpec[+A](
    algorithm: AlgorithmId,
    topology: Topology,
    order: ChannelOrder,
    policy: Policy[A],
    stop: StopRule,
    stepLimit: Int
)

object RunSpec {
  given [A: Encoder]: Encoder.AsObject[RunSpec[A]] = Encoder.AsObject.derived
  given [A: Decoder]: Decoder[RunSpec[A]] = Decoder.derived
}

/** One event of a trace. All engines write this format. Payloads stay typed. */
enum TraceEvent[+S, +M, +A, +O] {

  /** The first event. `initial` holds the state of each node, in node order. */
  case Started(spec: RunSpec[A], engine: Engine, initial: SortedMap[NodeId, S])
  case Sent(at: Stamp, env: Envelope[M])
  case Stepped(
      at: Stamp,
      node: NodeId,
      cause: Choice[A],
      rule: RuleId,
      after: S,
      sent: List[EnvelopeId]
  )

  /** The handler of the node refused the choice. The state of the node does not change, and the
    * step sends nothing. When the cause is `Deliver(id)`, the step still consumes the envelope: the
    * envelope leaves its channel, as after a `Stepped` delivery.
    */
  case Rejected(at: Stamp, node: NodeId, cause: Choice[A], error: ProtocolError)
  case Dropped(at: Stamp, id: EnvelopeId)
  case Decided(at: Stamp, node: NodeId, output: O)
  case Finished(at: Stamp, outcome: Outcome)
}

object TraceEvent {
  given [S: Encoder, M: Encoder, A: Encoder, O: Encoder]: Encoder.AsObject[TraceEvent[S, M, A, O]] =
    Encoder.AsObject.derived
  given [S: Decoder, M: Decoder, A: Decoder, O: Decoder]: Decoder[TraceEvent[S, M, A, O]] =
    Decoder.derived
}

/** The full trace of one run, from `Started` to `Finished`. */
final case class Run[S, M, A, O](trace: Vector[TraceEvent[S, M, A, O]]) {

  def outcome: Option[Outcome] = trace.collectFirst { case TraceEvent.Finished(_, outcome) =>
    outcome
  }

  def decisions: Vector[(NodeId, O)] = trace.collect { case TraceEvent.Decided(_, node, output) =>
    (node, output)
  }
}

object Run {
  given [S: Encoder, M: Encoder, A: Encoder, O: Encoder]: Encoder.AsObject[Run[S, M, A, O]] =
    Encoder.AsObject.derived
  given [S: Decoder, M: Decoder, A: Decoder, O: Decoder]: Decoder[Run[S, M, A, O]] = Decoder.derived
}

/** Shows a value of a protocol type at the render edge. Give one instance for each `S`, `M`, `A`
  * and `O`.
  */
trait Inspect[A] {

  /** A short label, for example `recording`, `Marker` or `Elect(7)`. */
  def label(a: A): String

  /** The named fields, for a detail panel. */
  def fields(a: A): List[(String, String)]

  /** The short chip that the canvas draws for a message. The full `label` goes to the hover tip and
    * the panel. The default is a neutral chip with the full label.
    */
  def chip(a: A): Chip = Chip.Text(label(a))
}

/** How the canvas draws a message. */
enum Chip {

  /** A neutral chip with short text. */
  case Text(text: String)

  /** A chip with the red tint and short text: a message that a red node sent (Lai-Yang). */
  case Red(text: String)

  /** A small diamond with no text: a control message of a snapshot. */
  case Control
}

object Inspect {
  def apply[A](using inspect: Inspect[A]): Inspect[A] = inspect
}
