package cs553.core.registry

import cats.data.NonEmptyList
import cs553.core.Family
import cs553.core.codec.LinkError
import cs553.core.codec.RunJson
import cs553.core.codec.RunLink
import cs553.core.codec.SharedRun
import cs553.core.codec.TraceError
import cs553.core.ids.AlgorithmId
import cs553.core.protocol.Protocol
import cs553.core.rng.Seed
import cs553.core.topology.Preset
import cs553.core.trace.Inspect
import cs553.core.trace.Overlay
import cs553.core.trace.Policy
import cs553.core.trace.Run
import cs553.core.trace.RunSpec
import cs553.core.trace.StopRule
import cs553.core.world.RunError
import cs553.core.world.Simulation
import cs553.core.world.Simulator
import io.circe.Codec

/** One algorithm with all that the UI needs: the protocol, the `Inspect` and `Overlay` givens for
  * the render edge, the codecs for traces and links, and the topology presets.
  *
  * The UI works with `AnyAlgorithm`, which hides the four type parameters.
  */
final case class Algorithm[S, M, A, O](
    protocol: Protocol[S, M, A, O],
    name: String,
    family: Family,
    summary: String,
    presets: List[Preset]
)(using
    val inspectState: Inspect[S],
    val inspectMsg: Inspect[M],
    val inspectAction: Inspect[A],
    val inspectOutput: Inspect[O],
    val overlay: Overlay[S],
    val codecState: Codec[S],
    val codecMsg: Codec[M],
    val codecAction: Codec[A],
    val codecOutput: Codec[O]
) {

  def id: AlgorithmId = protocol.id

  def preset(name: String): Option[Preset] = presets.find(_.name == name)

  /** The spec of a seeded run on `preset`, with the default channel order of the protocol. */
  def spec(preset: Preset, seed: Seed): RunSpec[A] = {
    RunSpec(
      id,
      preset.topology,
      protocol.order.default,
      Policy.Seeded(seed),
      StopRule.AtQuiescence,
      Algorithm.stepLimit
    )
  }

  /** The spec of the first preset with the default seed. `None` only when there is no preset. */
  def defaultSpec: Option[RunSpec[A]] = presets.headOption.map(spec(_, Algorithm.defaultSeed))

  def simulate(spec: RunSpec[A]): Either[RunError[A], Simulation[S, M, A, O]] = {
    Simulator.run(protocol, spec)
  }

  def replay(shared: SharedRun[A]): Either[RunError[A], Simulation[S, M, A, O]] = {
    Simulator.replay(protocol, shared.spec, shared.choices)
  }

  def writeTrace(run: Run[S, M, A, O]): String = RunJson.write(run)

  def writeTracePretty(run: Run[S, M, A, O]): String = RunJson.writePretty(run)

  def readTrace(text: String): Either[NonEmptyList[TraceError], Run[S, M, A, O]] = {
    RunJson.read[S, M, A, O](text)
  }

  /** The URL fragment of a run. A topology that equals a preset goes in as the preset name. */
  def encodeLink(shared: SharedRun[A]): String = RunLink.encode(shared, presets)

  /** Reads a URL fragment that `encodeLink` wrote for this algorithm. */
  def decodeLink(text: String): Either[NonEmptyList[LinkError], SharedRun[A]] = {
    RunLink.decode[A](text, id, presets)
  }
}

object Algorithm {

  /** The seed of a new run. */
  val defaultSeed: Seed = Seed(1)

  /** The step limit of a UI run. Each preset run stops well before it. */
  val stepLimit: Int = 1000
}

/** An `Algorithm` with its type parameters hidden, so that one list can hold all algorithms.
  *
  * The path-dependent types `S`, `M`, `A` and `O` let the UI call the typed functions: for one
  * value `alg`, `alg.algorithm.simulate` gives a `Simulation[alg.S, alg.M, alg.A, alg.O]`.
  */
sealed trait AnyAlgorithm {
  type S
  type M
  type A
  type O

  def algorithm: Algorithm[S, M, A, O]

  final def id: AlgorithmId = algorithm.id
  final def name: String = algorithm.name
}

object AnyAlgorithm {

  def apply[S0, M0, A0, O0](a: Algorithm[S0, M0, A0, O0]): AnyAlgorithm = Wrapped(a)

  private final case class Wrapped[S0, M0, A0, O0](algorithm: Algorithm[S0, M0, A0, O0])
      extends AnyAlgorithm {
    type S = S0
    type M = M0
    type A = A0
    type O = O0
  }
}
