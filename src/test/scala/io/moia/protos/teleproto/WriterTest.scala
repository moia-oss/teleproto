package io.moia.protos.teleproto

import java.time.Instant
import com.google.protobuf.duration.{Duration => PBDuration}
import com.google.protobuf.timestamp.Timestamp

import scala.concurrent.duration.{Duration, DurationLong}

class WriterTest extends UnitTest {

  import Writer._

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

  "Writer" should {

    val writer = new Writer[Model, Protobuf] {

      def write(model: Model): Protobuf =
        Protobuf(
          present(model.id),
          present(model.price),
          present(model.time),
          present(model.duration),
          transform(model.pickupId),
          sequence(model.prices),
          transform(model.discounts)
        )
    }

    "write complete model" in {

      writer.write(
        Model("id",
              1.23,
              Instant.ofEpochSecond(12, 34),
              45.seconds + 67.nanos,
              Some("pickup-id"),
              List(1.2, 3.45),
              Map("1" -> 1.2, "2" -> 2))
      ) shouldBe
        Protobuf(Some("id"),
                 Some("1.23"),
                 Some(Timestamp(12, 34)),
                 Some(PBDuration(45, 67)),
                 Some("pickup-id"),
                 Seq("1.2", "3.45"),
                 Map("1" -> "1.2", "2" -> "2"))
    }

    "write partial model" in {

      writer.write(Model("id", 1.23, Instant.ofEpochSecond(12, 34), 45.seconds + 67.nanos, None, Nil, Map("1" -> 1.2, "2" -> 2))) shouldBe
        Protobuf(Some("id"),
                 Some("1.23"),
                 Some(Timestamp(12, 34)),
                 Some(PBDuration(45, 67)),
                 None,
                 Seq.empty,
                 Map("1" -> "1.2", "2" -> "2"))
    }
  }
}
