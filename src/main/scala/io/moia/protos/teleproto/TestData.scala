/*
 * Copyright 2019 MOIA GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
