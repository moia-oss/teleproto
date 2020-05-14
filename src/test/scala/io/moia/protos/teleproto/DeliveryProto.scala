package io.moia.protos.teleproto

import com.google.protobuf.duration.Duration
import com.google.protobuf.timestamp.Timestamp

case class DeliveryProto(
    id: Option[String],
    price: Option[String],
    time: Option[Timestamp],
    duration: Option[Duration],
    pickupId: Option[String],
    prices: Seq[String],
    discounts: Map[String, String]
)
