package io.moia.protos.teleproto

import java.time.Instant
import java.util.concurrent.TimeUnit

import com.google.protobuf.duration.{Duration => PBDuration}
import com.google.protobuf.timestamp.Timestamp

import scala.concurrent.duration.{Duration, DurationLong, FiniteDuration}

class ReaderTest extends UnitTest {

  import Reader._

  case class Protobuf(id: Option[String],
                      price: Option[String],
                      time: Option[Timestamp],
                      duration: Option[PBDuration],
                      pickupId: Option[String],
                      prices: Seq[String],
                      discounts: Map[String, String])

  case class Model(id: String,
                   price: BigDecimal,
                   time: Instant,
                   duration: Duration,
                   pickupId: Option[String],
                   prices: List[BigDecimal],
                   discounts: Map[String, BigDecimal])

  "Reader" should {

    val reader = new Reader[Protobuf, Model] {

      def read(protobuf: Protobuf): PbResult[Model] =
        for {
          id        <- required[String, String](protobuf.id, "/id")
          price     <- required[String, BigDecimal](protobuf.price, "/price")
          time      <- required[Timestamp, Instant](protobuf.time, "/time")
          duration  <- required[PBDuration, Duration](protobuf.duration, "/duration")
          pickupId  <- optional[String, String](protobuf.pickupId, "/pickupId")
          prices    <- sequence[List, String, BigDecimal](protobuf.prices, "/prices")
          discounts <- transform[Map[String, String], Map[String, BigDecimal]](protobuf.discounts, "/discounts")
        } yield {
          Model(id, price, time, duration, pickupId, prices, discounts)
        }
    }

    "fail if value is missing" in {

      reader.read(
        Protobuf(None, Some("bar"), Some(Timestamp.defaultInstance), Some(PBDuration.defaultInstance), None, Seq.empty, Map.empty)
      ) shouldBe
        PbFailure("/id", "Value is required.")
    }

    "fail if value is not a valid decimal" in {

      reader.read(
        Protobuf(Some("foo"), Some("bar"), Some(Timestamp.defaultInstance), Some(PBDuration.defaultInstance), None, Seq.empty, Map.empty)
      ) shouldBe
        PbFailure("/price", "Value must be a valid decimal number.")
    }

    "fail if sequence entries are not a valid decimal" in {

      reader.read(
        Protobuf(Some("foo"),
                 Some("1.2"),
                 Some(Timestamp.defaultInstance),
                 Some(PBDuration.defaultInstance),
                 None,
                 Seq("1", "Pi", "Wurzel zwei"),
                 Map.empty)
      ) shouldBe
        PbFailure(Seq("/prices(1)" -> "Value must be a valid decimal number.", "/prices(2)" -> "Value must be a valid decimal number."))
    }

    "fail if map entries are not a valid decimal" in {

      reader.read(
        Protobuf(Some("foo"),
                 Some("1.2"),
                 Some(Timestamp.defaultInstance),
                 Some(PBDuration.defaultInstance),
                 None,
                 Seq.empty,
                 Map("1" -> "0", "2" -> "50%", "3" -> "80%"))
      ) shouldBe
        PbFailure(Seq("/discounts/2" -> "Value must be a valid decimal number.", "/discounts/3" -> "Value must be a valid decimal number."))
    }

    "read successfully if list is empty" in {

      reader.read(
        Protobuf(Some("foo"),
                 Some("1.2"),
                 Some(Timestamp.defaultInstance),
                 Some(PBDuration.defaultInstance),
                 Some("pickup"),
                 Seq.empty,
                 Map.empty)
      ) shouldBe
        PbSuccess(Model("foo", 1.2, Instant.ofEpochMilli(0), Duration.Zero, Some("pickup"), Nil, Map.empty))
    }

    "read successfully if an option is undefined" in {

      reader.read(
        Protobuf(Some("foo"),
                 Some("1.2"),
                 Some(Timestamp.defaultInstance),
                 Some(PBDuration.defaultInstance),
                 None,
                 Seq("1", "1.2", "1.23"),
                 Map.empty)
      ) shouldBe
        PbSuccess(Model("foo", 1.2, Instant.ofEpochMilli(0), Duration.Zero, None, List(1, 1.2, 1.23), Map.empty))
    }

    "read durations in coarsest unit" in {

      FiniteDurationReader.read(PBDuration(3600 * 24 * 7)) shouldBe PbSuccess(FiniteDuration(7, TimeUnit.DAYS))
      FiniteDurationReader.read(PBDuration(3600 * 3)) shouldBe PbSuccess(FiniteDuration(3, TimeUnit.HOURS))
      FiniteDurationReader.read(PBDuration(60 * 2)) shouldBe PbSuccess(FiniteDuration(2, TimeUnit.MINUTES))
      FiniteDurationReader.read(PBDuration(12)) shouldBe PbSuccess(FiniteDuration(12, TimeUnit.SECONDS))
      FiniteDurationReader.read(PBDuration(12, 345000000)) shouldBe PbSuccess(FiniteDuration(12345, TimeUnit.MILLISECONDS))
      FiniteDurationReader.read(PBDuration(12, 345678000)) shouldBe PbSuccess(FiniteDuration(12345678, TimeUnit.MICROSECONDS))
      FiniteDurationReader.read(PBDuration(12, 345678912)) shouldBe PbSuccess(FiniteDuration(12345678912L, TimeUnit.NANOSECONDS))
    }

    "read timestamps on nano level" in {

      reader.read(Protobuf(Some("foo"), Some("1.2"), Some(Timestamp(12, 34)), Some(PBDuration.defaultInstance), None, Seq.empty, Map.empty)) shouldBe
        PbSuccess(Model("foo", 1.2, Instant.ofEpochSecond(12, 34), Duration.Zero, None, Nil, Map.empty))
    }

    "read durations on nano level" in {

      reader.read(Protobuf(Some("foo"), Some("1.2"), Some(Timestamp.defaultInstance), Some(PBDuration(12, 34)), None, Seq.empty, Map.empty)) shouldBe
        PbSuccess(Model("foo", 1.2, Instant.ofEpochMilli(0), 12.seconds + 34.nanos, None, Nil, Map.empty))
    }
  }
}
