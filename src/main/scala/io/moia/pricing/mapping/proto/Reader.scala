package io.moia.pricing.mapping.proto

import java.time.{Instant, LocalTime}

import com.google.protobuf.timestamp.Timestamp

import scala.annotation.implicitNotFound
import scala.collection.generic
import scala.collection.immutable.TreeMap
import scala.language.higherKinds
import scala.util.Try

/**
  * Provides reading of a generated Protocol Buffers model into a business model.
  */
@implicitNotFound(
  "No Protocol Buffers mapper from type ${P} to ${M} was found. Try to implement an implicit Reader for this type.")
trait Reader[-P, +M] { self =>

  /**
    * Returns the read business model or an error message.
    */
  def read(protobuf: P): PbResult[M]
}

object Reader extends LowPriorityReads {

  /* Combinators */

  def transform[PV, MV](protobuf: PV, path: String)(
      implicit valueReader: Reader[PV, MV]): PbResult[MV] =
    valueReader.read(protobuf).withPathPrefix(path)

  def optional[PV, MV](protobuf: Option[PV], path: String)(
      implicit valueReader: Reader[PV, MV]): PbResult[Option[MV]] =
    protobuf
      .map(valueReader.read(_).map(Some(_)))
      .getOrElse(PbSuccess(None))
      .withPathPrefix(path)

  def required[PV, MV](protobuf: Option[PV], path: String)(
      implicit valueReader: Reader[PV, MV]): PbResult[MV] =
    protobuf
      .map(valueReader.read)
      .getOrElse(PbFailure("Value is required."))
      .withPathPrefix(path)

  def sequence[F[_], PV, MV](protobufs: Seq[PV], path: String)(
      implicit bf: generic.CanBuildFrom[F[_], MV, F[MV]],
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
      val builder = bf()
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
      Try(PbSuccess(BigDecimal(protobuf)))
        .getOrElse(PbFailure("Value must be a valid decimal number."))
  }

  /**
    * Transforms a string into a big decimal or fails.
    */
  implicit object InstantReader extends Reader[Timestamp, Instant] {
    def read(protobuf: Timestamp): PbResult[Instant] =
      PbSuccess(Instant.ofEpochSecond(protobuf.seconds, protobuf.nanos.toLong))
  }

  /**
    * Transforms a string into a local time or fails.
    */
  implicit object LocalTimeReader extends Reader[String, LocalTime] {
    def read(protobuf: String): PbResult[LocalTime] =
      Try(PbSuccess(LocalTime.parse(protobuf)))
        .getOrElse(PbFailure("Value must be a local time in ISO format."))
  }

  /**
    * Tries to read a map of Protobuf key/values to a sorted map of Scala key/values if reader exists between both types
    * and an ordering is defined on the Scala key.
    */
  implicit def treeMapReader[PK, PV, MK, MV](
      implicit keyReader: Reader[PK, MK],
      valueReader: Reader[PV, MV],
      ordering: Ordering[MK]): Reader[Map[PK, PV], TreeMap[MK, MV]] =
    (protobuf: Map[PK, PV]) =>
      mapReader(keyReader, valueReader)
        .read(protobuf)
        .map(entries => TreeMap[MK, MV](entries.toSeq: _*))
}

private[mapping] trait LowPriorityReads extends LowestPriorityReads {

  /**
    * Tries to read a map of Protobuf key/values to a sorted map of Scala key/values if reader exists between both types
    * and an ordering is defined on the Scala key.
    */
  implicit def mapReader[PK, PV, MK, MV](
      implicit keyReader: Reader[PK, MK],
      valueReader: Reader[PV, MV]): Reader[Map[PK, PV], Map[MK, MV]] =
    (protobuf: Map[PK, PV]) => {
      val modelResults =
        for ((protoKey, protoValue) <- protobuf.toSeq)
          yield {
            for {
              key <- keyReader.read(protoKey).withPathPrefix(s"/$protoKey")
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

private[mapping] trait LowestPriorityReads {

  /**
    * Keeps a value of same type in protobuf and model.
    */
  implicit def identityReader[T]: Reader[T, T] =
    (value: T) => PbSuccess(value)
}
