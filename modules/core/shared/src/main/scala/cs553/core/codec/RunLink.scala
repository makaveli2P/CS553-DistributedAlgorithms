package cs553.core.codec

import cats.data.NonEmptyList
import cats.data.ValidatedNel
import cats.syntax.all.*
import cs553.core.ids.AlgorithmId
import cs553.core.ids.NodeId
import cs553.core.protocol.ChannelOrder
import cs553.core.rng.Seed
import cs553.core.topology.Preset
import cs553.core.topology.Topology
import cs553.core.trace.Choice
import cs553.core.trace.EnvelopeId
import cs553.core.trace.Policy
import cs553.core.trace.RunSpec
import cs553.core.trace.StopRule
import io.circe.ACursor
import io.circe.CursorOp
import io.circe.Decoder
import io.circe.DecodingFailure
import io.circe.Encoder
import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*

import java.nio.charset.StandardCharsets
import java.util.Base64
import scala.util.Try

/** A run that a link shares: the spec and the choices that the learner applied. The policy of the
  * spec gives the next step after the choices, for example `Seeded(seed)` for "Deliver next".
  */
final case class SharedRun[A](spec: RunSpec[A], choices: List[Choice[A]])

/** An error in the text of a link. */
enum LinkError {

  /** The text has no `<version>.<payload>` form. */
  case NotALink(detail: String)

  /** The link has a format version that this code cannot read. */
  case UnsupportedVersion(found: String)

  /** The payload is not base64url text. */
  case NotBase64(detail: String)

  /** The decoded payload is not JSON. */
  case Syntax(detail: String)

  /** The JSON does not have the shape of a link at `path`. */
  case Shape(path: String, detail: String)

  /** The link names a topology preset that the algorithm does not have. */
  case UnknownPreset(name: String)

  /** The link is for another algorithm. */
  case WrongAlgorithm(expected: AlgorithmId, found: AlgorithmId)

  def render: String = this match {
    case NotALink(detail)          => s"the text is not a run link: $detail"
    case UnsupportedVersion(found) =>
      s"the link has the format version '$found', not ${RunLink.version}"
    case NotBase64(detail)          => s"the link is not base64url text: $detail"
    case Syntax(detail)             => s"the link does not hold JSON: $detail"
    case Shape(path, detail)        => s"the link is not valid at '$path': $detail"
    case UnknownPreset(name)        => s"the link names the topology '$name', which is not a preset"
    case WrongAlgorithm(exp, found) =>
      s"the link is for the algorithm '${found.value}', not '${exp.value}'"
  }
}

/** Writes and reads a `SharedRun` as the fragment of a URL, on the JVM and on JavaScript.
  *
  * The text is `<version>.<payload>`. The payload is compact JSON in base64url without padding, so
  * the text needs no escapes in a URL. Version 1 has these fields:
  *
  *   - `a`: the algorithm id.
  *   - `t`: the name of a preset, or the topology as JSON when no preset is equal to it.
  *   - `o`: the channel order, `f` (Fifo) or `a` (AnyOrder).
  *   - `p`: the policy, a seed as a decimal string, or a list of choices for a `Script`.
  *   - `s`: the stop rule, `q` (AtQuiescence) or `d` (AtFirstDecision).
  *   - `l`: the step limit.
  *   - `c`: the choices: `[0, from, to, seq]` delivers, `[1, from, to, seq]` drops and
  *     `[2, node, action]` fires an action.
  *
  * A change to the format must use a new version, and `decode` must still read version 1.
  */
object RunLink {

  val version: String = "1"

  def encode[A: Encoder](shared: SharedRun[A], presets: List[Preset]): String = {
    val spec = shared.spec
    val topology = presets.find(_.topology == spec.topology) match {
      case Some(preset) => preset.name.asJson
      case None         => spec.topology.asJson
    }
    val order = spec.order match {
      case ChannelOrder.Fifo     => "f"
      case ChannelOrder.AnyOrder => "a"
    }
    val policy = spec.policy match {
      case Policy.Seeded(seed)    => seed.value.toString.asJson
      case Policy.Script(choices) => Json.arr(choices.map(choiceJson(_))*)
    }
    val stop = spec.stop match {
      case StopRule.AtQuiescence    => "q"
      case StopRule.AtFirstDecision => "d"
    }
    val json = Json.obj(
      "a" -> spec.algorithm.asJson,
      "t" -> topology,
      "o" -> order.asJson,
      "p" -> policy,
      "s" -> stop.asJson,
      "l" -> spec.stepLimit.asJson,
      "c" -> Json.arr(shared.choices.map(choiceJson(_))*)
    )
    val bytes = json.noSpaces.getBytes(StandardCharsets.UTF_8)
    s"$version.${Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)}"
  }

  /** The algorithm id of a link, so that the caller can find the algorithm and then `decode`. */
  def algorithmOf(text: String): Either[NonEmptyList[LinkError], AlgorithmId] = {
    payload(text).flatMap { json =>
      json.hcursor.downField("a").as[AlgorithmId].left.map(f => NonEmptyList.one(shape(f)))
    }
  }

  /** Reads a link for the algorithm `expected`. A preset name resolves against `presets`. */
  def decode[A: Decoder](
      text: String,
      expected: AlgorithmId,
      presets: List[Preset]
  ): Either[NonEmptyList[LinkError], SharedRun[A]] = {
    payload(text).flatMap { json =>
      val c = json.hcursor
      val algorithm: ValidatedNel[LinkError, AlgorithmId] =
        field[AlgorithmId](c.downField("a")).andThen { found =>
          if found == expected then { found.validNel }
          else { LinkError.WrongAlgorithm(expected, found).invalidNel }
        }
      val topology = topologyField(c.downField("t"), presets)
      val order = field[String](c.downField("o")).andThen {
        case "f"   => ChannelOrder.Fifo.validNel
        case "a"   => ChannelOrder.AnyOrder.validNel
        case other => LinkError.Shape(".o", s"the order '$other' is not f or a").invalidNel
      }
      val policy = policyField[A](c.downField("p"))
      val stop = field[String](c.downField("s")).andThen {
        case "q"   => StopRule.AtQuiescence.validNel
        case "d"   => StopRule.AtFirstDecision.validNel
        case other => LinkError.Shape(".s", s"the stop rule '$other' is not q or d").invalidNel
      }
      val limit = field[Int](c.downField("l"))
      val choices =
        field[List[Choice[A]]](c.downField("c"))(using choicesDecoder[A])
      (algorithm, topology, order, policy, stop, limit, choices).mapN { (a, t, o, p, s, l, cs) =>
        SharedRun(RunSpec(a, t, o, p, s, l), cs)
      }.toEither
    }
  }

  /** The JSON of a link, after the version check and the base64url decode. */
  private def payload(text: String): Either[NonEmptyList[LinkError], Json] = {
    val trimmed = text.trim.stripPrefix("#")
    val result = trimmed.split('.').toList match {
      case v :: body :: Nil =>
        for {
          _ <-
            if v == version then { Right(()) }
            else { Left(LinkError.UnsupportedVersion(v)) }
          bytes <- Try(Base64.getUrlDecoder.decode(body)).toEither.left.map { e =>
            LinkError.NotBase64(Option(e.getMessage).getOrElse(e.getClass.getSimpleName))
          }
          json <- parse(new String(bytes, StandardCharsets.UTF_8)).left.map { failure =>
            LinkError.Syntax(failure.message)
          }
        } yield json
      case _ => Left(LinkError.NotALink("expected <version>.<payload>"))
    }
    result.left.map(NonEmptyList.one(_))
  }

  private def shape(f: DecodingFailure): LinkError = {
    LinkError.Shape(CursorOp.opsToPath(f.history), f.message)
  }

  private def field[T: Decoder](c: ACursor): ValidatedNel[LinkError, T] = {
    c.as[T].left.map(shape).toValidatedNel
  }

  private def topologyField(
      c: ACursor,
      presets: List[Preset]
  ): ValidatedNel[LinkError, Topology] = {
    c.as[String] match {
      case Right(name) =>
        presets.find(_.name == name).map(_.topology).toValidNel(LinkError.UnknownPreset(name))
      case Left(_) => field[Topology](c)
    }
  }

  private def policyField[A: Decoder](c: ACursor): ValidatedNel[LinkError, Policy[A]] = {
    c.as[String] match {
      case Right(text) =>
        text.toLongOption
          .map(n => Policy.Seeded(Seed(n)))
          .toValidNel(LinkError.Shape(".p", s"the seed '$text' is not a 64-bit integer"))
      case Left(_) =>
        field[List[Choice[A]]](c)(using choicesDecoder[A]).map(Policy.Script(_))
    }
  }

  private def choiceJson[A: Encoder](choice: Choice[A]): Json = choice match {
    case Choice.Deliver(id)        => Json.arr(0.asJson :: envelopeJson(id)*)
    case Choice.Drop(id)           => Json.arr(1.asJson :: envelopeJson(id)*)
    case Choice.Fire(node, action) => Json.arr(2.asJson, node.asJson, action.asJson)
  }

  private def envelopeJson(id: EnvelopeId): List[Json] = {
    List(id.from.asJson, id.to.asJson, id.seq.asJson)
  }

  private def choicesDecoder[A: Decoder]: Decoder[List[Choice[A]]] = {
    Decoder.decodeList(using choiceDecoder[A])
  }

  private def choiceDecoder[A: Decoder]: Decoder[Choice[A]] = Decoder.instance { c =>
    def envelope: Decoder.Result[EnvelopeId] = for {
      from <- c.downN(1).as[NodeId]
      to <- c.downN(2).as[NodeId]
      seq <- c.downN(3).as[Int]
    } yield EnvelopeId(from, to, seq)
    c.downN(0).as[Int].flatMap {
      case 0 => envelope.map(Choice.Deliver(_))
      case 1 => envelope.map(Choice.Drop(_))
      case 2 =>
        for {
          node <- c.downN(1).as[NodeId]
          action <- c.downN(2).as[A]
        } yield Choice.Fire(node, action)
      case other => Left(DecodingFailure(s"the choice kind $other is not 0, 1 or 2", c.history))
    }
  }
}
