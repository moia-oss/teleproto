package io.moia.protos.teleproto

import io.moia.food.food
import io.scalaland.chimney.{PartialTransformer, Transformer, partial}
import io.scalaland.chimney.protobufs.{*, given}
import org.scalacheck.Gen
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import scalapb.json4s.{Parser, Printer}

class ProtocolBuffersRoundTripTest extends UnitTest with ScalaCheckPropertyChecks {
  import ProtocolBuffersRoundTripTest._

  given PartialTransformer[food.Meal.Color, Color] = PartialTransformer.apply {
    case food.Meal.Color.COLOR_YELLOW    => partial.Result.fromValue(Color.Yellow)
    case food.Meal.Color.COLOR_RED       => partial.Result.fromValue(Color.Red)
    case food.Meal.Color.COLOR_ORANGE    => partial.Result.fromValue(Color.orange)
    case food.Meal.Color.COLOR_PINK      => partial.Result.fromValue(Color.pink)
    case food.Meal.Color.COLOR_BLUE      => partial.Result.fromValue(Color.Blue)
    case food.Meal.Color.COLOR_INVALID   => partial.Result.fromErrorString("Invalid color")
    case food.Meal.Color.Unrecognized(_) => partial.Result.fromErrorString("Unrecognized color")
  }

  PartialTransformer
    .define[food.Meal.Color, Color]
    .withCoproductInstance[food.Meal.Color.COLOR_YELLOW.type](_ => Color.Yellow)
    .withCoproductInstance[food.Meal.Color.COLOR_RED.type](_ => Color.Red)
    .withCoproductInstance[food.Meal.Color.COLOR_ORANGE.type](_ => Color.orange)
    .withCoproductInstance[food.Meal.Color.COLOR_PINK.type](_ => Color.pink)
    .withCoproductInstance[food.Meal.Color.COLOR_BLUE.type](_ => Color.Blue)
    .withCoproductInstancePartial[food.Meal.Color.COLOR_INVALID.type](_ => partial.Result.fromErrorString("Invalid color"))
    .withCoproductInstancePartial[food.Meal.Color.Unrecognized](_ => partial.Result.fromErrorString("Unrecognized color"))
    .buildTransformer

  val reader: PartialTransformer[food.Meal, Meal] = PartialTransformer.derive[food.Meal, Meal]

  given Transformer[Color, food.Meal.Color] = Transformer
    .define[Color, food.Meal.Color]
    .withCoproductInstance[Color.Yellow.type](_ => food.Meal.Color.COLOR_YELLOW)
    .withCoproductInstance[Color.Red.type](_ => food.Meal.Color.COLOR_RED)
    .withCoproductInstance[Color.orange.type](_ => food.Meal.Color.COLOR_ORANGE)
    .withCoproductInstance[Color.pink.type](_ => food.Meal.Color.COLOR_PINK)
    .withCoproductInstance[Color.Blue.type](_ => food.Meal.Color.COLOR_BLUE)
    .buildTransformer

  val writer: Transformer[Meal, food.Meal] = Transformer.define[Meal, food.Meal].enableDefaultValues.buildTransformer

  val colorGen: Gen[Color] =
    Gen.oneOf(Color.Red, Color.orange, Color.Yellow, Color.pink, Color.Blue)

  val fruitGen: Gen[Fruit] = for {
    name  <- Gen.alphaStr
    color <- colorGen
  } yield Fruit(name, color)

  val drinkGen: Gen[Drink] = for {
    name  <- Gen.alphaStr
    color <- colorGen
  } yield Drink(name, color)

  val fruitBasketGen: Gen[FruitBasket] = Gen.listOf(fruitGen).map(FruitBasket.apply)

  val lunchBoxGen: Gen[LunchBox] = for {
    fruit <- fruitGen
    drink <- drinkGen
  } yield LunchBox(fruit, drink)

  val mealGen: Gen[Meal] = Gen.oneOf(fruitBasketGen, lunchBoxGen).map(Meal.apply)

  "ProtocolBuffers" should {
    "generate writer and reader that round trip successfully" in {
      forAll(mealGen) { meal =>
        reader.transform(writer.transform(meal)).asEitherErrorPathMessageStrings shouldBe Right(meal)
      }
    }

    "create model writer and reader that round trip successfully via JSON" in {
      forAll(mealGen) { meal =>
        val printer = new Printer().includingDefaultValueFields.formattingLongAsNumber
        val x       = printer.print(writer.transform(meal))
        val parser  = Parser()
        val y       = parser.fromJsonString(x)(using food.Meal)
        reader.transform(y).asEitherErrorPathMessageStrings shouldBe Right(meal)
      }
    }

    "create model writer and reader that round trip successfully via Protocol Buffers" in {
      forAll(mealGen) { meal =>
        val x = writer.transform(meal).toByteArray
        val y = food.Meal.parseFrom(x)
        reader.transform(y).asEitherErrorPathMessageStrings shouldBe Right(meal)
      }
    }
  }
}

object ProtocolBuffersRoundTripTest {
  enum Color {
    case Red, orange, Yellow, pink, Blue
  }

  final case class Fruit(name: String, color: Color)
  final case class Drink(name: String, color: Color)

  sealed trait Lunch
  final case class FruitBasket(fruits: List[Fruit])     extends Lunch
  final case class LunchBox(fruit: Fruit, drink: Drink) extends Lunch

  final case class Meal(lunch: Lunch)
}
