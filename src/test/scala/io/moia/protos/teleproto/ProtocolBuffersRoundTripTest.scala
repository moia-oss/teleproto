package io.moia.protos.teleproto

import io.moia.food.food
import org.scalacheck.Gen
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class ProtocolBuffersRoundTripTest extends UnitTest with ScalaCheckPropertyChecks {
  import ProtocolBuffersRoundTripTest._

  val mealReader: Reader[food.Meal, Meal] = ProtocolBuffers.reader[food.Meal, Meal]
  val mealWriter: Writer[Meal, food.Meal] = ProtocolBuffers.writer[Meal, food.Meal]

  val colorGen: Gen[Color] =
    Gen.oneOf(Color.Red, Color.Orange, Color.Yellow, Color.Pink, Color.Blue)

  val fruitGen: Gen[Fruit] = for {
    name  <- Gen.alphaStr
    color <- colorGen
  } yield Fruit(name, color)

  val drinkGen: Gen[Drink] = for {
    name  <- Gen.alphaStr
    color <- colorGen
  } yield Drink(name, color)

  val fruitBasketGen: Gen[FruitBasket] =
    Gen.listOf(fruitGen).map(FruitBasket)

  val lunchBoxGen: Gen[LunchBox] = for {
    fruit <- fruitGen
    drink <- drinkGen
  } yield LunchBox(fruit, drink)

  val mealGen: Gen[Meal] =
    Gen.oneOf(fruitBasketGen, lunchBoxGen).map(Meal)

  "ProtocolBuffers" should {
    "generate writer and reader that round trip successfully" in {
      forAll(mealGen) { meal =>
        mealReader.read(mealWriter.write(meal)) shouldBe PbSuccess(meal)
      }
    }
  }
}

object ProtocolBuffersRoundTripTest {
  sealed trait Color
  object Color {
    case object Red    extends Color
    case object Orange extends Color
    case object Yellow extends Color
    case object Pink   extends Color
    case object Blue   extends Color
  }

  final case class Fruit(name: String, color: Color)
  final case class Drink(name: String, color: Color)

  sealed trait Lunch
  final case class FruitBasket(fruits: List[Fruit])     extends Lunch
  final case class LunchBox(fruit: Fruit, drink: Drink) extends Lunch

  final case class Meal(lunch: Lunch)
}
