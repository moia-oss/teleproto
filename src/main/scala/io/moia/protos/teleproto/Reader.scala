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
import scala.collection.compat._
import scala.collection.immutable.TreeMap
import scala.concurrent.duration.{Deadline, Duration, FiniteDuration, MILLISECONDS, NANOSECONDS, SECONDS}
import scala.util.Try

/**
  * Provides reading of a generated Protocol Buffers model into a business model.
  */
@implicitNotFound("No Protocol Buffers mapper from type ${P} to ${M} was found. Try to implement an implicit Reader for this type.")
trait Reader[-P, +M] { self =>

  /**
    * Returns the read business model or an error message.
    */
  def read(protobuf: P): PbResult[M]

  /**
    * Transforms successfully read results.
    */
  def map[N](f: M => N): Reader[P, N] =
    (protobuf: P) => self.read(protobuf).map(f)

  /**
    * Transforms successfully read results with the option to fail.
    */
  def flatMap[N](f: M => PbSuccess[N]): Reader[P, N] =
    (protobuf: P) => self.read(protobuf).flatMap(f)

}

object Reader extends LowPriorityReads {

  /* Combinators */

  def transform[PV, MV](protobuf: PV, path: String)(implicit valueReader: Reader[PV, MV]): PbResult[MV] =
    valueReader.read(protobuf).withPathPrefix(path)

  def optional[PV, MV](protobuf: Option[PV], path: String)(implicit valueReader: Reader[PV, MV]): PbResult[Option[MV]] =
    protobuf.map(valueReader.read(_).map(Some(_))).getOrElse(PbSuccess(None)).withPathPrefix(path)

  def required[PV, MV](protobuf: Option[PV], path: String)(implicit valueReader: Reader[PV, MV]): PbResult[MV] =
    protobuf.map(valueReader.read).getOrElse(PbFailure("Value is required.")).withPathPrefix(path)

  def sequence[F[_], PV, MV](protobufs: Seq[PV], path: String)(implicit factory: Factory[MV, F[MV]],
                                                               valueReader: Reader[PV, MV]): PbResult[F[MV]] = {
    val results = protobufs.map(valueReader.read).zipWithIndex

    val errors =
      results.flatMap {
        case (PbFailure(innerErrors), index) =>
          PbFailure(innerErrors).withPathPrefix(s"$path($index)").errors
        case _ =>
          Seq.empty
      }

    if (errors.nonEmpty)
      PbFailure(errors)
    else {
      val modelValues = results.map(_._1.getOrElse(sys.error("Scapegoat...")))
      val builder     = factory.newBuilder
      builder.sizeHint(modelValues)
      builder ++= modelValues
      PbSuccess(builder.result())
    }
  }

  /* Type Readers */

  /**
    * Transforms a string into a big decimal or fails.
    */
  implicit object BigDecimalReader extends Reader[String, BigDecimal] {
    def read(protobuf: String): PbResult[BigDecimal] =
      Try(PbSuccess(BigDecimal(protobuf))).getOrElse(PbFailure("Value must be a valid decimal number."))
  }

  /**
    * Transforms a timestamp into an instant.
    */
  implicit object InstantReader extends Reader[Timestamp, Instant] {
    def read(protobuf: Timestamp): PbResult[Instant] =
      PbSuccess(Instant.ofEpochSecond(protobuf.seconds, protobuf.nanos.toLong))
  }

  /**
    * Transforms a ScalaPB duration into a finite Scala concurrent duration.
    */
  implicit object FiniteDurationReader extends Reader[PBDuration, FiniteDuration] {
    def read(protobuf: PBDuration): PbResult[FiniteDuration] =
      PbSuccess((Duration(protobuf.seconds, SECONDS) + Duration(protobuf.nanos.toLong, NANOSECONDS)).toCoarsest)
  }

  /**
    * Transforms a PB timestamp as fixed point in time (with milliseconds precision) into a Scala concurrent deadline.
    *
    * This decoding is side-effect free but has a problem with divergent system clocks!
    *
    * Depending on the use case either this (based on fixed point in time) or the following reader (based
    * on the time left) makes sense.
    */
  object FixedPointDeadlineReader extends Reader[Timestamp, Deadline] {
    def read(protobuf: Timestamp): PbResult[Deadline] = {
      val fixedPoint = Instant.ofEpochSecond(protobuf.seconds, protobuf.nanos)
      val leftMillis = fixedPoint.toEpochMilli - System.currentTimeMillis()
      PbSuccess(Duration(leftMillis, MILLISECONDS).fromNow)
    }
  }

  /**
    * Transforms duration in PB as into a Scala concurrent deadline with the duration from now.
    *
    * This decoding is not side-effect free since it depends on the clock! Time between encoding and decoding does not
    * count.
    *
    * Depending on the use case either this (based on fixed point in time) or the following reader (based
    * on the time left) makes sense.
    */
  object TimeLeftDeadlineReader extends Reader[PBDuration, Deadline] {
    def read(protobuf: PBDuration): PbResult[Deadline] =
      PbSuccess((Duration(protobuf.seconds, SECONDS) + Duration(protobuf.nanos.toLong, NANOSECONDS)).fromNow)
  }

  /**
    * Transforms a string into a local time or fails.
    */
  object LocalTimeReader extends Reader[String, LocalTime] {
    def read(protobuf: String): PbResult[LocalTime] =
      Try(PbSuccess(LocalTime.parse(protobuf))).getOrElse(PbFailure("Value must be a local time in ISO format."))
  }

  /**
    * Tries to read a map of Protobuf key/values to a sorted map of Scala key/values if reader exists between both types
    * and an ordering is defined on the Scala key.
    */
  implicit def treeMapReader[PK, PV, MK, MV](implicit keyReader: Reader[PK, MK],
                                             valueReader: Reader[PV, MV],
                                             ordering: Ordering[MK]): Reader[Map[PK, PV], TreeMap[MK, MV]] =
    (protobuf: Map[PK, PV]) => mapReader(keyReader, valueReader).read(protobuf).map(entries => TreeMap[MK, MV](entries.toSeq: _*))
}

private[teleproto] trait LowPriorityReads extends LowestPriorityReads {

  /**
    * Tries to read a map of Protobuf key/values to a sorted map of Scala key/values if reader exists between both types
    * and an ordering is defined on the Scala key.
    */
  implicit def mapReader[PK, PV, MK, MV](implicit keyReader: Reader[PK, MK],
                                         valueReader: Reader[PV, MV]): Reader[Map[PK, PV], Map[MK, MV]] =
    (protobuf: Map[PK, PV]) => {
      val modelResults =
        for ((protoKey, protoValue) <- protobuf.toSeq)
          yield {
            for {
              key   <- keyReader.read(protoKey).withPathPrefix(s"/$protoKey")
              value <- valueReader.read(protoValue).withPathPrefix(s"/$key")
            } yield {
              (key, value)
            }
          }

      val errors =
        modelResults.flatMap {
          case PbFailure(innerErrors) => innerErrors
          case _                      => Seq.empty
        }

      if (errors.nonEmpty)
        PbFailure(errors)
      else
        PbSuccess(Map[MK, MV](modelResults.map(_.get): _*))
    }
}

private[teleproto] trait LowestPriorityReads {

  /**
    * Keeps a value of same type in protobuf and model.
    */
  implicit def identityReader[T]: Reader[T, T] =
    (value: T) => PbSuccess(value)
}
