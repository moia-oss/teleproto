package io.moia.protos.teleproto

import java.time.Instant

import com.google.protobuf.duration.{Duration => PBDuration}
import com.google.protobuf.timestamp.Timestamp

import scala.concurrent.duration.DurationLong

class WriterTest extends UnitTest {
  import TestData._
  import Writer._

  "Writer" should {
    val writerLight: Writer[Model, ProtobufLight] = model =>
      ProtobufLight(
        id = present(model.id),
        price = present(model.price),
        time = present(model.time),
        duration = present(model.duration)
    )

    val writer: Writer[Model, Protobuf] = model =>
      Protobuf(
        id = present(model.id),
        price = present(model.price),
        time = present(model.time),
        duration = present(model.duration),
        pickupId = transform(model.pickupId),
        prices = sequence(model.prices),
        discounts = transform(model.discounts)
    )

    val modelLight = ModelLight(
      id = "id",
      price = 1.23,
      time = Instant.ofEpochSecond(12, 34),
      duration = 45.seconds + 67.nanos,
    )

    val model = modelLight.complete(
      pickupId = Some("pickup-id"),
      prices = List(1.2, 3.45),
      discounts = Map("1" -> 1.2, "2" -> 2)
    )

    val protoLight = ProtobufLight(
      id = Some("id"),
      price = Some("1.23"),
      time = Some(Timestamp(12, 34)),
      duration = Some(PBDuration(45, 67)),
    )

    val proto = protoLight.complete(
      pickupId = Some("pickup-id"),
      prices = Seq("1.2", "3.45"),
      discounts = Map("1" -> "1.2", "2" -> "2")
    )

    "write complete model" in {
      writer.write(model) shouldBe proto
    }

    "write partial model" in {
      writer.write(model.copy(pickupId = None, prices = Nil)) shouldBe proto.copy(pickupId = None, prices = Nil)
    }

    "map over the result" in {
      val pricesWriter = writer.map(_.prices)
      pricesWriter.write(model) shouldBe proto.prices
    }

    "contramap over the input" in {
      val secondWriter = writer.contramap[(Int, Model)](_._2)
      secondWriter.write((42, model)) shouldBe proto
    }

    "flatMap over the result" in {
      val pickupIdWriter: Writer[Model, Option[String]] = _.pickupId
      val complexWriter = pickupIdWriter.flatMap {
        case Some(_) => writer
        case None    => writerLight.map(_.complete())
      }

      complexWriter.write(model) shouldBe proto
      complexWriter.write(model.copy(pickupId = None)) shouldBe protoLight.complete()
    }

    "zip two writers" in {
      val tupleWriter           = writer.zip(writerLight)
      val (result, resultLight) = tupleWriter.write(model)
      result shouldBe proto
      resultLight shouldBe protoLight
    }

    "zip two writers with a function" in {
      val isLightWriter = writer.zipWith(writerLight)(_ == _.complete())
      isLightWriter.write(model) shouldBe false
      isLightWriter.write(model.copy(pickupId = None, prices = Nil, discounts = Map.empty)) shouldBe true
    }

    "chain two writers one after another" in {
      val pricesWriter = writer.andThen(_.prices)
      pricesWriter.write(model) shouldBe proto.prices
    }

    "compose two writers one before another" in {
      val fromLightWriter = writer.compose[ModelLight](_.complete())
      fromLightWriter.write(modelLight) shouldBe protoLight.complete()
    }
  }
}
