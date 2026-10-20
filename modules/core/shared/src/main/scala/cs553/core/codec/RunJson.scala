package cs553.core.codec

import cats.data.NonEmptyList
import cs553.core.trace.Run
import io.circe.CursorOp
import io.circe.Decoder
import io.circe.Encoder
import io.circe.parser.parse
import io.circe.syntax.*

/** An error in the JSON text of a trace. */
enum TraceError {

  /** The text is not JSON. */
  case Syntax(detail: String)

  /** The JSON does not have the shape of a trace at `path`, for example `.trace[3].Sent.at`. */
  case Shape(path: String, detail: String)

  def render: String = this match {
    case Syntax(detail)      => s"the text is not JSON: $detail"
    case Shape(path, detail) => s"the trace is not valid at '$path': $detail"
  }
}

/** Reads and writes a `Run` as JSON text, on the JVM and on JavaScript.
  *
  * The codecs are givens in the companion objects of the trace types. This object is the edge where
  * the text enters and leaves. `read` gives all the shape errors that it finds (coding rule 16),
  * and no circe type leaves this object.
  */
object RunJson {

  def write[S: Encoder, M: Encoder, A: Encoder, O: Encoder](run: Run[S, M, A, O]): String = {
    run.asJson.noSpaces
  }

  def read[S: Decoder, M: Decoder, A: Decoder, O: Decoder](
      text: String
  ): Either[NonEmptyList[TraceError], Run[S, M, A, O]] = {
    parse(text) match {
      case Left(failure) => Left(NonEmptyList.one(TraceError.Syntax(failure.message)))
      case Right(json)   =>
        Decoder[Run[S, M, A, O]]
          .decodeAccumulating(json.hcursor)
          .toEither
          .left
          .map(_.map(f => TraceError.Shape(CursorOp.opsToPath(f.history), f.message)))
    }
  }
}
