package io.moia.protos.teleproto

import io.moia.food.food
import io.scalaland.chimney.Transformer
import io.scalaland.chimney.protobufs.{*, given}
//import org.scalacheck.Gen
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

//import scala.util.Success

class ProtocolBuffersRoundTripTest extends UnitTest with ScalaCheckPropertyChecks {
  import ProtocolBuffersRoundTripTest._

//  given PartialTransformer[food.Meal.Color, Color] = PartialTransformer.apply {
//    case food.Meal.Color.COLOR_YELLOW    => partial.Result.fromValue(Color.Yellow)
//    case food.Meal.Color.COLOR_RED       => partial.Result.fromValue(Color.Red)
//    case food.Meal.Color.COLOR_ORANGE    => partial.Result.fromValue(Color.orange)
//    case food.Meal.Color.COLOR_PINK      => partial.Result.fromValue(Color.pink)
//    case food.Meal.Color.COLOR_BLUE      => partial.Result.fromValue(Color.Blue)
//    case food.Meal.Color.COLOR_INVALID   => partial.Result.fromErrorString("Invalid color")
//    case food.Meal.Color.Unrecognized(_) => partial.Result.fromErrorString("Unrecognized color")
//  }
//
//  PartialTransformer
//    .define[food.Meal.Color, Color]
//    .withCoproductInstance[food.Meal.Color.COLOR_YELLOW.type](_ => Color.Yellow)
//    .withCoproductInstance[food.Meal.Color.COLOR_RED.type](_ => Color.Red)
//    .withCoproductInstance[food.Meal.Color.COLOR_ORANGE.type](_ => Color.orange)
//    .withCoproductInstance[food.Meal.Color.COLOR_PINK.type](_ => Color.pink)
//    .withCoproductInstance[food.Meal.Color.COLOR_BLUE.type](_ => Color.Blue)
//    .withCoproductInstancePartial[food.Meal.Color.COLOR_INVALID.type](_ => partial.Result.fromErrorString("Invalid color"))
//    .withCoproductInstancePartial[food.Meal.Color.Unrecognized](_ => partial.Result.fromErrorString("Unrecognized color"))
//    .buildTransformer
//
//  val reader: PartialTransformer[food.Meal, Meal] = PartialTransformer.derive[food.Meal, Meal]

  given Transformer[Color, food.Meal.Color] = Transformer
    .define[Color, food.Meal.Color]
    .withCoproductInstance[Color.Yellow.type](_ => food.Meal.Color.COLOR_YELLOW)
    .withCoproductInstance[Color.Red.type](_ => food.Meal.Color.COLOR_RED)
    .withCoproductInstance[Color.orange.type](_ => food.Meal.Color.COLOR_ORANGE)
    .withCoproductInstance[Color.pink.type](_ => food.Meal.Color.COLOR_PINK)
    .withCoproductInstance[Color.Blue.type](_ => food.Meal.Color.COLOR_BLUE)
    .buildTransformer

  val writer: Transformer[Meal, food.Meal] = Transformer.define[Meal, food.Meal].enableDefaultValues.buildTransformer

  // val version = 1
  // val modelReader = VersionedModelReader[Int, Meal](version -> food.Meal)
  // val modelWriter = VersionedModelWriter[Int, Meal](version -> food.Meal)

//  val colorGen: Gen[Color] =
//    Gen.oneOf(Color.Red, Color.orange, Color.Yellow, Color.pink, Color.Blue)
//
//  val fruitGen: Gen[Fruit] = for {
//    name  <- Gen.alphaStr
//    color <- colorGen
//  } yield Fruit(name, color)
//
//  val drinkGen: Gen[Drink] = for {
//    name  <- Gen.alphaStr
//    color <- colorGen
//  } yield Drink(name, color)
//
//  val fruitBasketGen: Gen[FruitBasket] = Gen.listOf(fruitGen).map(FruitBasket.apply)
//
//  val lunchBoxGen: Gen[LunchBox] = for {
//    fruit <- fruitGen
//    drink <- drinkGen
//  } yield LunchBox(fruit, drink)

  // val mealGen: Gen[Meal] = Gen.oneOf(fruitBasketGen, lunchBoxGen).map(Meal.apply)

  "ProtocolBuffers" should {
    "generate writer and reader that round trip successful" in {
      val meal = Meal(
        FruitBasket(
          List(
            Fruit("bdZe", Color.Yellow)
            // Fruit("DaV", Color.Yellow),
            // Fruit("MiszARSoKvjoqqYNPeV", Color.Blue),
            // Fruit("PKufaRKYExYlmIcebgk", Color.Red),
            // Fruit("cOOGOjxFLNSuejMtXuK", Color.Yellow),
            // Fruit("bqKWDekmwshntg", Color.pink),
            // Fruit("CXMLalJFzw", Color.pink),
            // Fruit("GFSFWpW", Color.Yellow),
            // Fruit("", Color.Yellow),
            // Fruit("aHdeFnptHavuUtc", Color.Red)
          )
        )
      )
      println(meal)
      val y: food.Meal = writer.transform(meal)
      println(y)
      // val x = reader.transform(y).asEitherErrorPathMessageStrings
      // println(x)
      // x shouldBe Right(meal)
    }

    "generate writer and reader that round trip successfully" ignore {
//      forAll(mealGen) { meal =>
//        println(meal)
//        val y = writer.transform(meal)
//        println(y)
//        val x = reader.transform(y).asEitherErrorPathMessageStrings
//        println(x)
//        x shouldBe Right(meal)
//      }
    }

    "create model writer and reader that round trip successfully via JSON" ignore {
//      forAll(mealGen) { meal =>
//        // modelWriter.toJson(meal, version).flatMap(modelReader.fromJson(_, version)) shouldBe Success(PbSuccess(meal))
//      }
    }

    "create model writer and reader that round trip successfully via Protocol Buffers" ignore {
//      forAll(mealGen) { meal =>
//        // modelWriter.toByteArray(meal, version).flatMap(modelReader.fromProto(_, version)) shouldBe Success(PbSuccess(meal))
//      }
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

  final case class Fruit(name: String, color: Color)
  final case class Drink(name: String, color: Color)

  sealed trait Lunch
  final case class FruitBasket(fruits: List[Fruit])     extends Lunch
  final case class LunchBox(fruit: Fruit, drink: Drink) extends Lunch

  final case class Meal(lunch: Lunch)
}
