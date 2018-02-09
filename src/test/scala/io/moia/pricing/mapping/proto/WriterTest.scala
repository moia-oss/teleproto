package io.moia.pricing.mapping.proto

import java.time.Instant

import com.google.protobuf.timestamp.Timestamp
import org.scalatest.{Matchers, OptionValues, WordSpec}

class WriterTest extends WordSpec with Matchers with OptionValues {

  import Writer._

  case class Protobuf(id: Option[String],
                      price: Option[String],
                      time: Option[Timestamp],
                      pickupId: Option[String],
                      prices: Seq[String],
                      discounts: Map[String, String])

  case class Model(id: String,
                   price: BigDecimal,
                   time: Instant,
                   pickupId: Option[String],
                   prices: List[BigDecimal],
                   discounts: Map[String, BigDecimal])

  "Writer" should {

    val writer = new Writer[Model, Protobuf] {

      def write(model: Model): Protobuf =
        Protobuf(present(model.id),
                 present(model.price),
                 present(model.time),
                 transform(model.pickupId),
                 sequence(model.prices),
                 transform(model.discounts))
    }

    "write complete model" in {

      writer.write(
        Model("id",
              1.23,
              Instant.ofEpochMilli(0),
              Some("pickup-id"),
              List(1.2, 3.45),
              Map("1" -> 1.2, "2" -> 2))) shouldBe
        Protobuf(Some("id"),
                 Some("1.23"),
                 Some(Timestamp.defaultInstance),
                 Some("pickup-id"),
                 Seq("1.2", "3.45"),
                 Map("1" -> "1.2", "2" -> "2"))
    }

    "write partial model" in {

      writer.write(
        Model("id",
              1.23,
              Instant.ofEpochMilli(0),
              None,
              Nil,
              Map("1" -> 1.2, "2" -> 2))) shouldBe
        Protobuf(Some("id"),
                 Some("1.23"),
                 Some(Timestamp.defaultInstance),
                 None,
                 Seq.empty,
                 Map("1" -> "1.2", "2" -> "2"))
    }
  }
}
