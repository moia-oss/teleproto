package io.moia.protos.teleproto

import java.time.Instant

import com.google.protobuf.duration.{Duration => PBDuration}
import com.google.protobuf.timestamp.Timestamp

import scala.concurrent.duration.Duration

object TestData {
  final case class Protobuf(
      id: Option[String],
      price: Option[String],
      time: Option[Timestamp],
      duration: Option[PBDuration],
      pickupId: Option[String],
      prices: Seq[String],
      discounts: Map[String, String]
  )

  final case class Model(
      id: String,
      price: BigDecimal,
      time: Instant,
      duration: Duration,
      pickupId: Option[String],
      prices: List[BigDecimal],
      discounts: Map[String, BigDecimal]
  )

  final case class ProtobufLight(
      id: Option[String],
      price: Option[String],
      time: Option[Timestamp],
      duration: Option[PBDuration]
  ) {
    def complete(
        pickupId: Option[String] = None,
        prices: Seq[String] = Nil,
        discounts: Map[String, String] = Map.empty
    ): Protobuf = Protobuf(id, price, time, duration, pickupId, prices, discounts)
  }

  final case class ModelLight(
      id: String,
      price: BigDecimal,
      time: Instant,
      duration: Duration
  ) {
    def complete(
        pickupId: Option[String] = None,
        prices: List[BigDecimal] = Nil,
        discounts: Map[String, BigDecimal] = Map.empty
    ): Model = Model(id, price, time, duration, pickupId, prices, discounts)
  }
}
