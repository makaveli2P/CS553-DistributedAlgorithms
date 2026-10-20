package cs553.core.codec

import cs553.core.trace.Run
import io.circe.Decoder
import io.circe.Encoder
import io.circe.Error
import io.circe.parser.decode
import io.circe.syntax.*

/** Reads and writes a `Run` as JSON text, on the JVM and on JavaScript.
  *
  * The codecs are givens in the companion objects of the trace types. This object is the edge where
  * the text enters and leaves.
  */
object RunJson {

  def write[S: Encoder, M: Encoder, A: Encoder, O: Encoder](run: Run[S, M, A, O]): String = {
    run.asJson.noSpaces
  }

  def read[S: Decoder, M: Decoder, A: Decoder, O: Decoder](
      text: String
  ): Either[Error, Run[S, M, A, O]] = {
    decode[Run[S, M, A, O]](text)
  }
}
