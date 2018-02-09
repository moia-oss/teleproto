package io.moia.pricing.mapping.proto

import java.time.{Instant, LocalTime}

import com.google.protobuf.timestamp.Timestamp

import scala.annotation.implicitNotFound
import scala.collection.generic.CanBuildFrom
import scala.collection.immutable.TreeMap
import scala.language.higherKinds

/**
  * Provides reading of a generated Protocol Buffers model into a business model.
  */
@implicitNotFound(
  "No mapper from business model type ${M} to Protocol Buffers type ${P} was found. Try to implement an implicit Writer for this type.")
trait Writer[-M, +P] { self =>

  /**
    * Returns the written Protocol Buffer object.
    */
  def write(model: M): P
}

object Writer extends LowPriorityWrites {

  /* Combinators */

  def transform[MV, PV](model: MV)(implicit valueWriter: Writer[MV, PV]): PV =
    valueWriter.write(model)

  def optional[MV, PV](model: Option[MV])(
      implicit valueWriter: Writer[MV, PV]): Option[PV] =
    model.map(valueWriter.write)

  // Opposite of required on Reader side
  def present[MV, PV](model: MV)(
      implicit valueWriter: Writer[MV, PV]): Option[PV] =
    Some(valueWriter.write(model))

  /* Type Writers */

  /**
    * Writes a big decimal as string.
    */
  implicit object BigDecimalWriter extends Writer[BigDecimal, String] {
    def write(protobuf: BigDecimal): String = protobuf.toString
  }

  /**
    * Writes a local time as ISO string.
    */
  implicit object LocalTimeWriter extends Writer[LocalTime, String] {
    def write(protobuf: LocalTime): String = protobuf.toString
  }

  /**
    * Transforms a string into a big decimal or fails.
    */
  implicit object InstantWriter extends Writer[Instant, Timestamp] {
    def write(protobuf: Instant): Timestamp =
      Timestamp(protobuf.getEpochSecond, protobuf.getNano)
  }

  /**
    * Transforms a Scala map into a corresponding map with Protobuf types if writers exists between key and value types.
    */
  implicit def mapWriter[MK, MV, PK, PV](
      implicit keyWriter: Writer[MK, PK],
      valueWriter: Writer[MV, PV]): Writer[Map[MK, MV], Map[PK, PV]] =
    (model: Map[MK, MV]) =>
      for ((key, value) <- model)
        yield (keyWriter.write(key), valueWriter.write(value))

  implicit def treeMapWriter[MK, MV, PK, PV](
      implicit keyWriter: Writer[MK, PK],
      valueWriter: Writer[MV, PV]): Writer[TreeMap[MK, MV], Map[PK, PV]] =
    (model: TreeMap[MK, MV]) =>
      for ((key, value) <- model)
        yield (keyWriter.write(key), valueWriter.write(value))
}

trait LowPriorityWrites extends LowestPriorityWrites {

  def sequence[MV, PV](models: TraversableOnce[MV])(
      implicit valueWriter: Writer[MV, PV]): Seq[PV] =
    models.map(valueWriter.write).toSeq

  def collection[MV, PV, Col[_]](models: TraversableOnce[MV])(
      implicit valueWriter: Writer[MV, PV],
      cbf: CanBuildFrom[Nothing, PV, Col[PV]]): Col[PV] =
    models.map(valueWriter.write).to[Col]
}

trait LowestPriorityWrites {

  /**
    * Keeps a value of same type in protobuf and model.
    */
  implicit def identityWriter[T]: Writer[T, T] =
    (value: T) => value
}
