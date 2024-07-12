package io.moia.protos.teleproto

import com.google.protobuf.timestamp.Timestamp
import io.moia.protos.teleproto.Writer.instance
import io.scalaland.chimney.Transformer
import com.google.protobuf.duration.{Duration => PBDuration}

import java.time.{Instant, LocalTime}
import java.util.UUID
import scala.collection.immutable.TreeMap
import scala.concurrent.duration.{Deadline, Duration}

object BaseTransformers {
  /* Type Writers */

  /** Writes a big decimal as string.
    */
  implicit object BigDecimalWriter extends Transformer[BigDecimal, String] {
    def transform(model: BigDecimal): String = model.toString
  }

  /** Writes a local time as ISO string.
    */
  implicit object LocalTimeWriter extends Transformer[LocalTime, String] {
    def transform(model: LocalTime): String = model.toString
  }

  /** Writes an instant into timestamp.
    */
  implicit object InstantWriter extends Transformer[Instant, Timestamp] {
    def transform(instant: Instant): Timestamp =
      Timestamp(instant.getEpochSecond, instant.getNano)
  }

  /** Writes a Scala duration into ScalaPB duration.
    */
  implicit object DurationWriter extends Transformer[Duration, PBDuration] {
    def transform(duration: Duration): PBDuration =
      PBDuration(duration.toSeconds, (duration.toNanos % 1000000000).toInt)
  }

  /** Writes a UUID as string.
    */
  implicit object UUIDWriter extends Transformer[UUID, String] {
    def transform(uuid: UUID): String = uuid.toString
  }

  /** Writes a Scala deadline into a ScalaPB Timestamp as fixed point in time.
    *
    * The decoding of this value is side-effect free but has a problem with divergent system clocks!
    *
    * Depending on the use case either this (based on fixed point in time) or the following writer (based on the time left) makes sense.
    */
  object FixedPointDeadlineWriter extends Transformer[Deadline, Timestamp] {
    def transform(deadline: Deadline): Timestamp = {
      val absoluteDeadline = Instant.now.plusNanos(deadline.timeLeft.toNanos)
      Timestamp(absoluteDeadline.getEpochSecond, absoluteDeadline.getNano)
    }
  }

  /** Writes a Scala deadline into a ScalaPB int as time left duration.
    *
    * The decoding of this value is not side-effect free since it depends on the clock! Time between encoding and decoding does not count.
    *
    * Depending on the use case either this (based on time left) or the following writer (based on fixed point in time) makes sense.
    */
  object TimeLeftDeadlineWriter extends Transformer[Deadline, PBDuration] {
    def transform(deadline: Deadline): PBDuration = {
      val timeLeft       = deadline.timeLeft
      val nanoAdjustment = timeLeft.toNanos % 1000000000L
      PBDuration(timeLeft.toSeconds, nanoAdjustment.toInt)
    }
  }

  /** Transforms a Scala map into a corresponding map with Protobuf types if writers exists between key and value types.
    */
  implicit def mapWriter[MK, MV, PK, PV](implicit
      keyWriter: Writer[MK, PK],
      valueWriter: Writer[MV, PV]
  ): Writer[Map[MK, MV], Map[PK, PV]] = instance { model =>
    for ((key, value) <- model) yield (keyWriter.write(key), valueWriter.write(value))
  }

  implicit def treeMapWriter[MK, MV, PK, PV](implicit
      keyWriter: Writer[MK, PK],
      valueWriter: Writer[MV, PV],
      ordering: Ordering[PK]
  ): Writer[TreeMap[MK, MV], Map[PK, PV]] = instance { model =>
    for ((key, value) <- model) yield (keyWriter.write(key), valueWriter.write(value))
  }
}
