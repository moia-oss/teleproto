package io.moia.protos

import io.scalaland.chimney.{Transformer, PartialTransformer}

package object teleproto {
  given PartialTransformer[String, BigDecimal] = PartialTransformer.fromFunction(BigDecimal.apply)

  given Transformer[BigDecimal, String] = _.toString

  given Transformer[Int, Long] = _.toLong
}
