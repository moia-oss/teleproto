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

import java.time.{Instant, LocalTime}

import com.google.protobuf.duration.{Duration => PBDuration}
import com.google.protobuf.timestamp.Timestamp

import scala.annotation.implicitNotFound
import scala.collection.Factory
import scala.collection.immutable.TreeMap
import scala.concurrent.duration.{Deadline, Duration}

/**
  * Provides reading of a generated Protocol Buffers model into a business model.
  */
@implicitNotFound(
  "No mapper from business model type ${M} to Protocol Buffers type ${P} was found. Try to implement an implicit Writer for this type."
)
trait Writer[-M, +P] { self =>

  /**
    * Returns the written Protocol Buffer object.
    */
  def write(model: M): P

  /**
    * Transforms each written result.
    */
  def map[Q](f: P => Q): Writer[M, Q] = new Writer.Mapped(this, f)
}

object Writer extends LowPriorityWrites {

  /* Combinators */

  def transform[MV, PV](model: MV)(implicit valueWriter: Writer[MV, PV]): PV =
    valueWriter.write(model)

  def optional[MV, PV](model: Option[MV])(implicit valueWriter: Writer[MV, PV]): Option[PV] =
    model.map(valueWriter.write)

  // Opposite of required on Reader side
  def present[MV, PV](model: MV)(implicit valueWriter: Writer[MV, PV]): Option[PV] =
    Some(valueWriter.write(model))

  /* Type Writers */

  /**
    * Writes a big decimal as string.
    */
  implicit object BigDecimalWriter extends Writer[BigDecimal, String] {
    def write(model: BigDecimal): String = model.toString
  }

  /**
    * Writes a local time as ISO string.
    */
  implicit object LocalTimeWriter extends Writer[LocalTime, String] {
    def write(model: LocalTime): String = model.toString
  }

  /**
    * Writes an instant into timestamp.
    */
  implicit object InstantWriter extends Writer[Instant, Timestamp] {
    def write(instant: Instant): Timestamp =
      Timestamp(instant.getEpochSecond, instant.getNano)
  }

  /**
    * Writes a Scala duration into ScalaPB duration.
    */
  implicit object DurationWriter extends Writer[Duration, PBDuration] {
    def write(duration: Duration): PBDuration =
      PBDuration(duration.toSeconds, (duration.toNanos % 1000000000).toInt)
  }

  /**
    * Writes a Scala deadline into a ScalaPB Timestamp as fixed point in time.
    *
    * The decoding of this value is side-effect free but has a problem with divergent system clocks!
    *
    * Depending on the use case either this (based on fixed point in time) or the following writer (based on the time
    * left) makes sense.
    */
  object FixedPointDeadlineWriter extends Writer[Deadline, Timestamp] {
    def write(deadline: Deadline): Timestamp = {
      val absoluteDeadline = Instant.now.plusNanos(deadline.timeLeft.toNanos)
      Timestamp(absoluteDeadline.getEpochSecond, absoluteDeadline.getNano)
    }
  }

  /**
    * Writes a Scala deadline into a ScalaPB int as time left duration.
    *
    * The decoding of this value is not side-effect free since it depends on the clock! Time between encoding and
    * decoding does not count.
    *
    * Depending on the use case either this (based on time left) or the following writer (based on fixed point in time)
    * makes sense.
    */
  object TimeLeftDeadlineWriter extends Writer[Deadline, PBDuration] {
    def write(deadline: Deadline): PBDuration = {
      val timeLeft       = deadline.timeLeft
      val nanoAdjustment = timeLeft.toNanos % 1000000000L
      PBDuration(timeLeft.toSeconds, nanoAdjustment.toInt)
    }
  }

  /**
    * Transforms a Scala map into a corresponding map with Protobuf types if writers exists between key and value types.
    */
  implicit def mapWriter[MK, MV, PK, PV](implicit keyWriter: Writer[MK, PK],
                                         valueWriter: Writer[MV, PV]): Writer[Map[MK, MV], Map[PK, PV]] =
    (model: Map[MK, MV]) => for ((key, value) <- model) yield (keyWriter.write(key), valueWriter.write(value))

  implicit def treeMapWriter[MK, MV, PK, PV](implicit keyWriter: Writer[MK, PK],
                                             valueWriter: Writer[MV, PV],
                                             ordering: Ordering[PK]): Writer[TreeMap[MK, MV], Map[PK, PV]] =
    (model: TreeMap[MK, MV]) => for ((key, value) <- model) yield (keyWriter.write(key), valueWriter.write(value))

  private class Mapped[M, P, Q](wrapped: Writer[M, P], f: P => Q) extends Writer[M, Q] {

    def write(model: M): Q = f(wrapped.write(model))
  }
}

trait LowPriorityWrites extends LowestPriorityWrites {

  def sequence[MV, PV](models: IterableOnce[MV])(implicit valueWriter: Writer[MV, PV]): Seq[PV] =
    models.iterator.map(valueWriter.write).toSeq

  def collection[MV, PV, Col[_]](models: IterableOnce[MV])(implicit cbf: Factory[PV, Col[PV]], valueWriter: Writer[MV, PV]): Col[PV] = {
    models.iterator.map(valueWriter.write).iterator.to(cbf)
  }
}

trait LowestPriorityWrites {

  /**
    * Keeps a value of same type in protobuf and model.
    */
  implicit def identityWriter[T]: Writer[T, T] =
    (value: T) => value
}
