package io.moia.protos.teleproto

import java.time.Instant

import scala.concurrent.duration.Duration

case class DeliveryModel(
    id: String,
    price: BigDecimal,
    time: Instant,
    duration: Duration,
    pickupId: Option[String],
    prices: List[BigDecimal],
    discounts: Map[String, BigDecimal]
)
