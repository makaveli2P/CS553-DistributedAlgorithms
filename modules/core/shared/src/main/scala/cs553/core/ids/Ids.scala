package cs553.core.ids

import io.circe.Codec
import io.circe.Decoder
import io.circe.Encoder
import io.circe.KeyDecoder
import io.circe.KeyEncoder

/** The identity of one process in a topology. It is not a `Uid`. */
opaque type NodeId = Int

object NodeId {
  def apply(value: Int): NodeId = value

  extension (id: NodeId) {
    def value: Int = id
  }

  given Ordering[NodeId] = Ordering.Int
  given Codec[NodeId] = Codec.from(Decoder.decodeInt, Encoder.encodeInt)
  given KeyEncoder[NodeId] = KeyEncoder.encodeKeyInt
  given KeyDecoder[NodeId] = KeyDecoder.decodeKeyInt
}

/** The unique identifier that an election algorithm compares. It is not a `NodeId`. */
opaque type Uid = Int

object Uid {
  def apply(value: Int): Uid = value

  extension (id: Uid) {
    def value: Int = id
  }

  given Ordering[Uid] = Ordering.Int
  given Codec[Uid] = Codec.from(Decoder.decodeInt, Encoder.encodeInt)
}

/** The label of one step case of a protocol. It links a trace step to the docs and the source. */
opaque type RuleId = String

object RuleId {
  def apply(value: String): RuleId = value

  extension (id: RuleId) {
    def value: String = id
  }

  given Ordering[RuleId] = Ordering.String
  given Codec[RuleId] = Codec.from(Decoder.decodeString, Encoder.encodeString)
}

/** The stable name of an algorithm, for example `echo`. */
opaque type AlgorithmId = String

object AlgorithmId {
  def apply(value: String): AlgorithmId = value

  extension (id: AlgorithmId) {
    def value: String = id
  }

  given Ordering[AlgorithmId] = Ordering.String
  given Codec[AlgorithmId] = Codec.from(Decoder.decodeString, Encoder.encodeString)
}
