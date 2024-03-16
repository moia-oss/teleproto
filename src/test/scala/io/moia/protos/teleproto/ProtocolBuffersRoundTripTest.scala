package io.moia.protos.teleproto

import io.moia.food.food
import io.scalaland.chimney.Transformer
import io.scalaland.chimney.protobufs.{*, given}
import io.scalaland.chimney.dsl.*
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class ProtocolBuffersRoundTripTest extends UnitTest with ScalaCheckPropertyChecks {
  import ProtocolBuffersRoundTripTest.*

  val writer: Transformer[Color, food.Meal.Color] = Transformer
    .define[Color, food.Meal.Color]
    .withCoproductInstance[Color.Yellow.type](_ => food.Meal.Color.COLOR_YELLOW)
    .withCoproductInstance[Color.Red.type](_ => food.Meal.Color.COLOR_RED)
    .withCoproductInstance[Color.orange.type](_ => food.Meal.Color.COLOR_ORANGE)
    .withCoproductInstance[Color.pink.type](_ => food.Meal.Color.COLOR_PINK)
    .withCoproductInstance[Color.Blue.type](_ => food.Meal.Color.COLOR_BLUE)
    .enableMacrosLogging
    .buildTransformer

  "ProtocolBuffers" should {
    "generate writer and reader that round trip successful" in {
      val color: Color = Color.Yellow

      writer.transform(color)
    }
  }
}

object ProtocolBuffersRoundTripTest {
  sealed trait Color
  object Color {
    case object Red    extends Color
    case object orange extends Color
    case object Yellow extends Color
    case object pink   extends Color
    case object Blue   extends Color
  }
}
