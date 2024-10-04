package io.moia.protos.teleproto

import io.moia.food.food
import org.scalacheck.Gen
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
//import io.moia.protos.teleproto.BaseTransformers._ // TODO: remove

import scala.util.Success

class ProtocolBuffersRoundTripTest extends UnitTest with ScalaCheckPropertyChecks {
  import ProtocolBuffersRoundTripTest._

  // TODO: remove?
  implicit val colorReader: Reader[food.Meal.Color, Color] = ProtocolBuffers.reader[food.Meal.Color, Color]

  implicit val reader: Reader[food.Meal, Meal] = ProtocolBuffers.reader[food.Meal, Meal]
//  implicit val writer: Writer[Meal, food.Meal] = ProtocolBuffers.writer[Meal, food.Meal]
  val writerFB: Writer[FruitBasket, food.Meal.FruitBasket] = ProtocolBuffers.writer[FruitBasket, food.Meal.FruitBasket]
//  val writerLFB: Writer[FruitBasket, food.Meal.Lunch.FruitBasket] = ProtocolBuffers.writer[FruitBasket, food.Meal.Lunch.FruitBasket]
  val writerLFB: Writer[FruitBasket, food.Meal.Lunch.FruitBasket] = new Writer[FruitBasket, food.Meal.Lunch.FruitBasket] {
    override def write(model: FruitBasket): food.Meal.Lunch.FruitBasket = food.Meal.Lunch.FruitBasket(value = writerFB.write(model))
  }
  val writerLB: Writer[LunchBox, food.Meal.LunchBox] = ProtocolBuffers.writer[LunchBox, food.Meal.LunchBox]
//  val writerLLB: Writer[LunchBox, food.Meal.Lunch.LunchBox] = ProtocolBuffers.writer[LunchBox, food.Meal.Lunch.LunchBox]
  val writerLLB: Writer[LunchBox, food.Meal.Lunch.LunchBox] = new Writer[LunchBox, food.Meal.Lunch.LunchBox] {
    override def write(model: LunchBox): food.Meal.Lunch.LunchBox = food.Meal.Lunch.LunchBox(value = writerLB.write(model))
  }
  val writerL: Writer[Lunch, food.Meal.Lunch] = ProtocolBuffers.writer[Lunch, food.Meal.Lunch]
  implicit val writer: Writer[Meal, food.Meal] = new Writer[Meal, food.Meal] {
    override def write(model: Meal): food.Meal = food.Meal(
      lunch = writerL.write(model.lunch)
    )
  }
//  implicit val writer: Writer[Meal, food.Meal] = ProtocolBuffers.writer[Meal, food.Meal]

  val version     = 1
  val modelReader = VersionedModelReader[Int, Meal](version -> food.Meal)
  val modelWriter = VersionedModelWriter[Int, Meal](version -> food.Meal)

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
        reader.read(writer.write(meal)) shouldBe PbSuccess(meal)
      }
    }
    "do that thing" in {
      val meal = Meal(
        FruitBasket(
          List(
            Fruit("z", Color.Red),
            Fruit("cqxnceqq", Color.pink),
            Fruit("hXRwEsHU", Color.Red),
            Fruit("ysBUXsSQDr", Color.Yellow),
            Fruit("hMldwXGXA", Color.pink),
            Fruit("oyg", Color.Yellow),
            Fruit("dGQ", Color.Yellow),
            Fruit("vpy", Color.Blue)
          )
        )
      )
      val proto = writer.write(meal)
      println(s"proto ${proto}")
      reader.read(proto) shouldBe PbSuccess(meal)
    }

    "create model writer and reader that round trip successfully via JSON" in {
      forAll(mealGen) { meal =>
        modelWriter.toJson(meal, version).flatMap(modelReader.fromJson(_, version)) shouldBe Success(PbSuccess(meal))
      }
    }

    "create model writer and reader that round trip successfully via Protocol Buffers" in {
      forAll(mealGen) { meal =>
        modelWriter.toByteArray(meal, version).flatMap(modelReader.fromProto(_, version)) shouldBe Success(PbSuccess(meal))
      }
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
