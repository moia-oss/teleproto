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
import scalapb.GeneratedMessage

import java.util.UUID
import scala.annotation.implicitNotFound
import scala.collection.compat._
import scala.collection.immutable.TreeMap
import scala.concurrent.duration.{Deadline, Duration, FiniteDuration, MILLISECONDS, NANOSECONDS, SECONDS}
import scala.util.Try

/** Provides reading of a generated Protocol Buffers model into a business model.
  */
@implicitNotFound("No Protocol Buffers mapper from type ${P} to ${M} was found. Try to implement an implicit Reader for this type.")
trait Reader[-P, +M] {

  /** Returns the read business model or an error message.
    */
  def read(protobuf: P): PbResult[M]

  /** Transforms successfully read results.
    */
  def map[N](f: M => N): Reader[P, N] =
    Reader.instance(read(_).map(f))

  /** Transforms the protobuf before reading.
    */
  final def contramap[Q](f: Q => P): Reader[Q, M] =
    Reader.instance(protobuf => read(f(protobuf)))

  /** Transforms successfully read results with the option to fail.
    */
  final def pbmap[N](f: M => PbResult[N]): Reader[P, N] =
    Reader.instance(read(_).flatMap(f))

  @deprecated("Use a function that returns a Reader with flatMap or one that returns a PbResult with emap", "1.8.0")
  protected def flatMap[N](f: M => PbSuccess[N]): Reader[P, N] =
    Reader.instance(read(_).flatMap(f))

  /** Transforms successfully read results by stacking another reader on top of the original protobuf.
    */
  final def flatMap[Q <: P, N](f: M => Reader[Q, N])(implicit dummy: DummyImplicit): Reader[Q, N] =
    Reader.instance(protobuf => read(protobuf).flatMap(f(_).read(protobuf)))

  /** Combines two readers with a specified function.
    */
  final def zipWith[Q <: P, N, O](that: Reader[Q, N])(f: (M, N) => O): Reader[Q, O] =
    Reader.instance { protobuf =>
      for {
        m <- this.read(protobuf)
        n <- that.read(protobuf)
      } yield f(m, n)
    }

  /** Combines two readers into a reader of a tuple.
    */
  final def zip[Q <: P, N](that: Reader[Q, N]): Reader[Q, (M, N)] =
    zipWith(that)((_, _))

  /** Chain `that` reader after `this` one.
    */
  final def andThen[N](that: Reader[M, N]): Reader[P, N] =
    Reader.instance(read(_).flatMap(that.read))

  /** Chain `this` reader after `that` one.
    */
  final def compose[Q](that: Reader[Q, P]): Reader[Q, M] =
    that.andThen(this)
}

object Reader extends LowPriorityReads {

  def apply[P, M](implicit reader: Reader[P, M]): Reader[P, M] = reader

  def instance[P, M](f: P => PbResult[M]): Reader[P, M] = f(_)

  /* Combinators */

  def transform[PV, MV](protobuf: PV, path: String)(implicit valueReader: Reader[PV, MV]): PbResult[MV] =
    valueReader.read(protobuf).withPathPrefix(path)

  def optional[PV, MV](protobuf: Option[PV], path: String)(implicit valueReader: Reader[PV, MV]): PbResult[Option[MV]] =
    protobuf.map(valueReader.read(_).map(Some(_))).getOrElse(PbSuccess(None)).withPathPrefix(path)

  def required[PV, MV](protobuf: Option[PV], path: String)(implicit valueReader: Reader[PV, MV]): PbResult[MV] =
    protobuf.map(valueReader.read).getOrElse(PbFailure("Value is required.")).withPathPrefix(path)

  def sequence[F[_], PV, MV](protobufs: Seq[PV], path: String)(implicit
      factory: Factory[MV, F[MV]],
      valueReader: Reader[PV, MV]
  ): PbResult[F[MV]] = {
    val results = protobufs.map(valueReader.read).zipWithIndex
    val errors = results.flatMap {
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

  /** Transforms a string into a big decimal or fails.
    */
  implicit object BigDecimalReader extends Reader[String, BigDecimal] {
    def read(protobuf: String): PbResult[BigDecimal] =
      Try(PbSuccess(BigDecimal(protobuf))).getOrElse(PbFailure("Value must be a valid decimal number."))
  }

  /** Transforms a timestamp into an instant.
    */
  implicit object InstantReader extends Reader[Timestamp, Instant] {
    def read(protobuf: Timestamp): PbResult[Instant] =
      PbResult.fromTry(Try(Instant.ofEpochSecond(protobuf.seconds, protobuf.nanos.toLong)))
  }

  /** Transforms a ScalaPB duration into a finite Scala concurrent duration.
    */
  implicit object FiniteDurationReader extends Reader[PBDuration, FiniteDuration] {
    def read(protobuf: PBDuration): PbResult[FiniteDuration] =
      PbSuccess((Duration(protobuf.seconds, SECONDS) + Duration(protobuf.nanos.toLong, NANOSECONDS)).toCoarsest)
  }

  /** Transforms a string into a UUID.
    */
  implicit object UUIDReader extends Reader[String, UUID] {
    def read(uuid: String): PbResult[UUID] = Try(PbSuccess(UUID.fromString(uuid))).getOrElse(PbFailure("Value must be a UUID."))
  }

  /** Transforms a PB timestamp as fixed point in time (with milliseconds precision) into a Scala concurrent deadline.
    *
    * This decoding is side-effect free but has a problem with divergent system clocks!
    *
    * Depending on the use case either this (based on fixed point in time) or the following reader (based on the time left) makes sense.
    */
  object FixedPointDeadlineReader extends Reader[Timestamp, Deadline] {
    def read(protobuf: Timestamp): PbResult[Deadline] = {
      val fixedPoint = Instant.ofEpochSecond(protobuf.seconds, protobuf.nanos)
      val leftMillis = fixedPoint.toEpochMilli - System.currentTimeMillis()
      PbSuccess(Duration(leftMillis, MILLISECONDS).fromNow)
    }
  }

  /** Transforms duration in PB as into a Scala concurrent deadline with the duration from now.
    *
    * This decoding is not side-effect free since it depends on the clock! Time between encoding and decoding does not count.
    *
    * Depending on the use case either this (based on fixed point in time) or the following reader (based on the time left) makes sense.
    */
  object TimeLeftDeadlineReader extends Reader[PBDuration, Deadline] {
    def read(protobuf: PBDuration): PbResult[Deadline] =
      PbSuccess((Duration(protobuf.seconds, SECONDS) + Duration(protobuf.nanos.toLong, NANOSECONDS)).fromNow)
  }

  /** Transforms a string into a local time or fails.
    */
  object LocalTimeReader extends Reader[String, LocalTime] {
    def read(protobuf: String): PbResult[LocalTime] =
      Try(PbSuccess(LocalTime.parse(protobuf))).getOrElse(PbFailure("Value must be a local time in ISO format."))
  }

  /** Tries to read a map of Protobuf key/values to a sorted map of Scala key/values if reader exists between both types and an ordering is
    * defined on the Scala key.
    */
  implicit def treeMapReader[PK, PV, MK, MV](implicit
      keyReader: Reader[PK, MK],
      valueReader: Reader[PV, MV],
      ordering: Ordering[MK]
  ): Reader[Map[PK, PV], TreeMap[MK, MV]] = instance { protobuf =>
    mapReader(keyReader, valueReader).read(protobuf).map(entries => TreeMap[MK, MV](entries.toSeq*))
  }

  /** A reader that gives access to the inner [[PbResult]]. Use this if you want to allow failures in nested structures and therefore get
    * back a partial result, for example to always deserialize an event to an `Envelope[PbResult[A]]` even if the actual payload of type `A`
    * fails to parse.
    */
  implicit def pbResultReader[PB <: GeneratedMessage, A](implicit reader: Reader[PB, A]): Reader[PB, PbResult[A]] =
    instance(pb => PbSuccess(reader.read(pb)))
}

private[teleproto] trait LowPriorityReads extends LowestPriorityReads {

  /** Tries to read a map of Protobuf key/values to a sorted map of Scala key/values if reader exists between both types and an ordering is
    * defined on the Scala key.
    */
  implicit def mapReader[PK, PV, MK, MV](implicit
      keyReader: Reader[PK, MK],
      valueReader: Reader[PV, MV]
  ): Reader[Map[PK, PV], Map[MK, MV]] = Reader.instance { protobuf =>
    val modelResults = for ((protoKey, protoValue) <- protobuf.toSeq) yield {
      for {
        key   <- keyReader.read(protoKey).withPathPrefix(s"/$protoKey")
        value <- valueReader.read(protoValue).withPathPrefix(s"/$key")
      } yield (key, value)
    }

    val errors = modelResults.flatMap {
      case PbFailure(innerErrors) => innerErrors
      case _                      => Seq.empty
    }

    if (errors.nonEmpty) PbFailure(errors)
    else PbSuccess(Map[MK, MV](modelResults.map(_.get)*))
  }
}

private[teleproto] trait LowestPriorityReads {

  /** Keeps a value of same type in protobuf and model.
    */
  implicit def identityReader[T]: Reader[T, T] =
    Reader.instance(PbSuccess.apply)
}
