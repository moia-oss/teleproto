package io.moia.protos.teleproto

import java.time.Instant
import java.util.concurrent.TimeUnit

import com.google.protobuf.duration.{Duration => PBDuration}
import com.google.protobuf.timestamp.Timestamp

import scala.concurrent.duration.{Duration, DurationLong, FiniteDuration}

class ReaderTest extends UnitTest {
  import Reader._
  import TestData._

  "Reader" should {
    val readerLight: Reader[Protobuf, ModelLight] = protobuf =>
      for {
        id       <- required[String, String](protobuf.id, "/id")
        price    <- required[String, BigDecimal](protobuf.price, "/price")
        time     <- required[Timestamp, Instant](protobuf.time, "/time")
        duration <- required[PBDuration, Duration](protobuf.duration, "/duration")
      } yield ModelLight(id, price, time, duration)

    val reader: Reader[Protobuf, Model] = protobuf =>
      for {
        light     <- readerLight.read(protobuf)
        pickupId  <- optional[String, String](protobuf.pickupId, "/pickupId")
        prices    <- sequence[List, String, BigDecimal](protobuf.prices, "/prices")
        discounts <- transform[Map[String, String], Map[String, BigDecimal]](protobuf.discounts, "/discounts")
      } yield light.complete(pickupId, prices, discounts)

    val proto = Protobuf(
      id = Some("foo"),
      price = Some("1.2"),
      time = Some(Timestamp.defaultInstance),
      duration = Some(PBDuration.defaultInstance),
      pickupId = Some("pickup"),
      prices = Nil,
      discounts = Map.empty
    )

    val modelLight = ModelLight(
      id = "foo",
      price = 1.2,
      time = Instant.ofEpochMilli(0),
      duration = Duration.Zero
    )

    val model = modelLight.complete(pickupId = Some("pickup"))

    "fail if value is missing" in {
      reader.read(proto.copy(id = None)) shouldBe PbFailure("/id", "Value is required.")
    }

    "fail if value is not a valid decimal" in {
      reader.read(proto.copy(price = Some("bar"))) shouldBe PbFailure("/price", "Value must be a valid decimal number.")
    }

    "fail if sequence entries are not a valid decimal" in {
      reader.read(proto.copy(prices = Seq("1", "Pi", "Wurzel zwei"))) shouldBe
        PbFailure(Seq("/prices(1)" -> "Value must be a valid decimal number.", "/prices(2)" -> "Value must be a valid decimal number."))
    }

    "fail if map entries are not a valid decimal" in {
      reader.read(proto.copy(discounts = Map("1" -> "0", "2" -> "50%", "3" -> "80%"))) shouldBe
        PbFailure(Seq("/discounts/2" -> "Value must be a valid decimal number.", "/discounts/3" -> "Value must be a valid decimal number."))
    }

    "read successfully if list is empty" in {
      reader.read(proto) shouldBe PbSuccess(model)
    }

    "read successfully if an option is undefined" in {
      reader.read(proto.copy(pickupId = None, prices = Seq("1", "1.2", "1.23"))) shouldBe
        PbSuccess(model.copy(pickupId = None, prices = List(1, 1.2, 1.23)))
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

    "fail reading an invalid Timestamp gracefully" in {
      InstantReader.read(Timestamp(Long.MinValue)) shouldBe PbFailure("Instant exceeds minimum or maximum instant")
    }

    "read timestamps on nano level" in {
      reader.read(proto.copy(time = Some(Timestamp(12, 34)))) shouldBe
        PbSuccess(model.copy(time = Instant.ofEpochSecond(12, 34)))
    }

    "read durations on nano level" in {
      reader.read(proto.copy(duration = Some(PBDuration(12, 34)))) shouldBe
        PbSuccess(model.copy(duration = 12.seconds + 34.nanos))
    }

    "map over the result" in {
      val priceReader = reader.map(_.price)
      priceReader.read(proto) shouldBe PbSuccess(BigDecimal(1.2))
    }

    "contramap over the input" in {
      val firstReader = reader.contramap[(Protobuf, Int)](_._1)
      firstReader.read((proto, 42)) shouldBe PbSuccess(model)
    }

    "pbmap over the result" in {
      val pickupIdReader = reader.pbmap(model => PbResult.fromOption(model.pickupId)(PbFailure("pickupId is missing")))
      pickupIdReader.read(proto) shouldBe PbSuccess("pickup")
      pickupIdReader.read(proto.copy(pickupId = None)) shouldBe PbFailure("pickupId is missing")
    }

    "flatMap over the result" in {
      val pickupIdReader: Reader[Protobuf, String] = proto => PbResult.fromOption(proto.pickupId)(PbFailure("pickupId is missing"))
      val complexReader = pickupIdReader.flatMap {
        case "pickup" => reader
        case other    => readerLight.map(_.complete(pickupId = Some(other)))
      }

      complexReader.read(proto) shouldBe PbSuccess(model)
      complexReader.read(proto.copy(pickupId = None)) shouldBe PbFailure("pickupId is missing")
      complexReader.read(proto.copy(pickupId = Some("other"), prices = Seq("1", "Pi", "Wurzel zwei"))) shouldBe
        PbSuccess(model.copy(pickupId = Some("other")))
    }

    "zip two readers" in {
      val tupleReader = reader.zip(readerLight)
      tupleReader.read(proto) shouldBe PbSuccess((model, modelLight))
    }

    "zip two readers with a function" in {
      val isLightReader = reader.zipWith(readerLight)(_ == _.complete())
      isLightReader.read(proto) shouldBe PbSuccess(false)
      isLightReader.read(proto.copy(pickupId = None)) shouldBe PbSuccess(true)
    }

    "chain two readers one after another" in {
      val cheapReader = reader.andThen(model => PbSuccess(model.price < 1))
      cheapReader.read(proto) shouldBe PbSuccess(false)
      cheapReader.read(proto.copy(price = Some("0.99"))) shouldBe PbSuccess(true)
    }

    "compose two readers one before another" in {
      val hasDiscountReader: Reader[Model, Boolean] = model => PbSuccess(model.discounts.nonEmpty)
      val discountedReader                          = hasDiscountReader.compose(reader)
      discountedReader.read(proto) shouldBe PbSuccess(false)
      discountedReader.read(proto.copy(discounts = Map("2" -> "0.5", "3" -> "0.8"))) shouldBe PbSuccess(true)
    }
  }
}
