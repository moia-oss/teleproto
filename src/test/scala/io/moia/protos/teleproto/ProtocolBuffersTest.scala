package io.moia.protos.teleproto

import java.time.Instant
import com.google.protobuf.Descriptors
import com.google.protobuf.timestamp.Timestamp
import io.scalaland.chimney.{Transformer, partial, PartialTransformer}
import io.scalaland.chimney.protobufs.*
import io.scalaland.chimney.dsl.*
import scalapb.descriptors.EnumDescriptor
import scalapb.{GeneratedEnum, GeneratedEnumCompanion, UnrecognizedEnum}

sealed abstract class ProtobufEnum(val value: Int) extends GeneratedEnum {
  type EnumType = ProtobufEnum
  override def companion: GeneratedEnumCompanion[ProtobufEnum] = ProtobufEnum
}

object ProtobufEnum extends GeneratedEnumCompanion[ProtobufEnum] {
  // Not used for testing.
  override def fromValue(value: Int): ProtobufEnum        = ???
  override def values: Seq[ProtobufEnum]                  = ???
  override def javaDescriptor: Descriptors.EnumDescriptor = ???
  override def scalaDescriptor: EnumDescriptor            = ???

  case object FirstCase extends ProtobufEnum(0) {
    override def index: Int                                     = value
    override def name: String                                   = "FirstCase"
    override def asRecognized: Option[FirstCase.RecognizedType] = None
  }

  case object SECOND_CASE extends ProtobufEnum(1) {
    override def index: Int                                       = value
    override def name: String                                     = "SECOND_CASE"
    override def asRecognized: Option[SECOND_CASE.RecognizedType] = None
  }

  case object Third_Case extends ProtobufEnum(2) {
    override def index: Int                                      = value
    override def name: String                                    = "Third_Case"
    override def asRecognized: Option[Third_Case.RecognizedType] = None
  }

  final case class Unrecognized(other: Int) extends ProtobufEnum(other) with UnrecognizedEnum {
    override def asRecognized: Option[Unrecognized.this.RecognizedType] = None
  }
}

case class SubProtobuf(from: String, to: String)

case class Protobuf(
    id: Option[String] = None,
    price: Option[String] = None,
    time: Option[Timestamp] = None,
    pickupId: Option[String] = None,
    ranges: Seq[SubProtobuf] = Seq.empty,
    doubleSub: Option[SubProtobuf] = None,
    `enum`: ProtobufEnum = ProtobufEnum.FirstCase
)

sealed trait ModelEnum
object ModelEnum {
  case object First_Case extends ModelEnum
  case object SecondCase extends ModelEnum
  case object THIRD_CASE extends ModelEnum
}

case class SubModel(from: BigDecimal, to: BigDecimal)

case class Model(
    id: String,
    price: BigDecimal,
    time: Instant,
    pickupId: Option[String],
    ranges: List[SubModel],
    doubleSub: SubModel,
    `enum`: ModelEnum
)

case class ModelSmaller(id: String, price: BigDecimal)

case class ModelLarger(
    id: String,
    price: BigDecimal,
    foo: Option[String] = Some("bar"),
    time: Instant,
    bar: String = "baz",
    pickupId: Option[String],
    baz: Option[String],
    ranges: List[SubModel],
    doubleSub: SubModel,
    `enum`: ModelEnum
)

object Protobuf {

  given PartialTransformer[ProtobufEnum, ModelEnum] = PartialTransformer
    .define[ProtobufEnum, ModelEnum]
    .withCoproductInstance[ProtobufEnum.FirstCase.type](_ => ModelEnum.First_Case)
    .withCoproductInstance[ProtobufEnum.SECOND_CASE.type](_ => ModelEnum.SecondCase)
    .withCoproductInstance[ProtobufEnum.Third_Case.type](_ => ModelEnum.THIRD_CASE)
    .withCoproductInstancePartial[ProtobufEnum.Unrecognized](unrecognizedEnum =>
      partial.Result.fromErrorString(s"Enumeration value ${unrecognizedEnum.value} is unrecognized!")
    )
    .buildTransformer

  given PartialTransformer[SubProtobuf, SubModel] = PartialTransformer.derive[SubProtobuf, SubModel]

  val reader: PartialTransformer[Protobuf, Model] = PartialTransformer.derive[Protobuf, Model]

  val reader2: PartialTransformer[Protobuf, ModelSmaller] = PartialTransformer.derive[Protobuf, ModelSmaller]

  val reader3: PartialTransformer[Protobuf, ModelLarger] =
    PartialTransformer.define[Protobuf, ModelLarger].enableDefaultValues.enableOptionDefaultsToNone.buildTransformer

  given Transformer[ModelEnum, ProtobufEnum] = Transformer
    .define[ModelEnum, ProtobufEnum]
    .withCoproductInstance[ModelEnum.First_Case.type](_ => ProtobufEnum.FirstCase)
    .withCoproductInstance[ModelEnum.SecondCase.type](_ => ProtobufEnum.SECOND_CASE)
    .withCoproductInstance[ModelEnum.THIRD_CASE.type](_ => ProtobufEnum.Third_Case)
    .buildTransformer

  val writer: Transformer[Model, Protobuf] = Transformer.derive[Model, Protobuf]

  val writer2: Transformer[ModelSmaller, Protobuf] = Transformer.define[ModelSmaller, Protobuf].enableDefaultValues.buildTransformer

  val writer3: Transformer[ModelLarger, Protobuf] = Transformer.derive[ModelLarger, Protobuf]
}

class ProtocolBuffersTest extends UnitTest {

  import Protobuf._

  "ProtocolBuffers" should {

    "generate a reader for matching models" in {

      reader
        .transform(Protobuf(None, Some("1.2"), Some(Timestamp.defaultInstance), None, Nil, Some(SubProtobuf("1", "2"))))
        .asEitherErrorPathMessageStrings shouldBe Left(List("id" -> "empty value"))

      reader
        .transform(Protobuf(Some("foo"), Some("bar"), Some(Timestamp.defaultInstance), None, Nil, Some(SubProtobuf("1", "2"))))
        .asEitherErrorPathMessageStrings shouldBe Left(
        List("price" -> "Character b is neither a decimal digit number, decimal point, nor \"e\" notation exponential mark.")
      )

      reader
        .transform(
          Protobuf(
            Some("foo"),
            Some("1.2"),
            Some(Timestamp.defaultInstance),
            Some("pickup"),
            Nil,
            Some(SubProtobuf("1", "2")),
            ProtobufEnum.FirstCase
          )
        )
        .asEitherErrorPathMessageStrings shouldBe
        Right(Model("foo", 1.2, Instant.ofEpochMilli(0), Some("pickup"), Nil, SubModel(1, 2), ModelEnum.First_Case))

      reader
        .transform(
          Protobuf(
            Some("foo"),
            Some("1.2"),
            Some(Timestamp.defaultInstance),
            None,
            Seq(SubProtobuf("1", "1.2"), SubProtobuf("1.2", "1.23")),
            Some(SubProtobuf("1", "2")),
            ProtobufEnum.SECOND_CASE
          )
        )
        .asEitherErrorPathMessageStrings shouldBe
        Right(
          Model(
            "foo",
            1.2,
            Instant.ofEpochMilli(0),
            None,
            List(SubModel(1, 1.2), SubModel(1.2, 1.23)),
            SubModel(1, 2),
            ModelEnum.SecondCase
          )
        )
    }

    "generate a reader that provides nested paths in error messages" in {

      reader
        .transform(
          Protobuf(
            Some("foo"),
            Some("1.2"),
            Some(Timestamp.defaultInstance),
            None,
            Seq(SubProtobuf("1", "1.2"), SubProtobuf("1.2", "Milestein One")),
            Some(SubProtobuf("1", "2"))
          )
        )
        .asEitherErrorPathMessageStrings shouldBe
        Left(List("ranges(1).to" -> "Character M is neither a decimal digit number, decimal point, nor \"e\" notation exponential mark."))
    }

    "generate a reader that collects all errors" in {

      reader
        .transform(
          Protobuf(
            None,
            None,
            None,
            None,
            Seq(SubProtobuf("foo", "bar"), SubProtobuf("baz", "qux")),
            Some(SubProtobuf("one", "two")),
            ProtobufEnum.Unrecognized(42)
          )
        )
        .asEitherErrorPathMessageStrings shouldBe
        Left(
          List(
            "id"             -> "empty value",
            "price"          -> "empty value",
            "time"           -> "empty value",
            "ranges(0).from" -> "Character f is neither a decimal digit number, decimal point, nor \"e\" notation exponential mark.",
            "ranges(0).to"   -> "Character b is neither a decimal digit number, decimal point, nor \"e\" notation exponential mark.",
            "ranges(1).from" -> "Character b is neither a decimal digit number, decimal point, nor \"e\" notation exponential mark.",
            "ranges(1).to"   -> "Character q is neither a decimal digit number, decimal point, nor \"e\" notation exponential mark.",
            "doubleSub.from" -> "Character o is neither a decimal digit number, decimal point, nor \"e\" notation exponential mark.",
            "doubleSub.to"   -> "Character t is neither a decimal digit number, decimal point, nor \"e\" notation exponential mark.",
            "enum"           -> "Enumeration value 42 is unrecognized!"
          )
        )
    }

    "generate a reader for backward compatible models" in {

      reader2
        .transform(
          Protobuf(
            Some("foo"),
            Some("1.2"),
            Some(Timestamp.defaultInstance),
            None,
            Seq(SubProtobuf("1", "1.2"), SubProtobuf("1.2", "1.23"))
          )
        )
        .asEitherErrorPathMessageStrings shouldBe
        Right(ModelSmaller("foo", 1.2))

      reader3
        .transform(
          Protobuf(
            Some("foo"),
            Some("1.2"),
            Some(Timestamp.defaultInstance),
            None,
            Seq(SubProtobuf("1", "1.2"), SubProtobuf("1.2", "1.23")),
            Some(SubProtobuf("1", "2")),
            ProtobufEnum.Third_Case
          )
        )
        .asEitherErrorPathMessageStrings shouldBe
        Right(
          ModelLarger(
            id = "foo",
            price = 1.2,
            time = Instant.ofEpochMilli(0),
            pickupId = None,
            baz = None,
            ranges = List(SubModel(1, 1.2), SubModel(1.2, 1.23)),
            doubleSub = SubModel(1, 2),
            `enum` = ModelEnum.THIRD_CASE
          )
        )
    }

    "generate a reader for ScalaPB enums that handles Unrecognized as a failure" in {
      reader
        .transform(
          Protobuf(
            Some("foo"),
            Some("1.2"),
            Some(Timestamp.defaultInstance),
            None,
            Seq(SubProtobuf("1", "1.2"), SubProtobuf("1.2", "1.23")),
            Some(SubProtobuf("1", "2")),
            ProtobufEnum.Unrecognized(42)
          )
        )
        .asEitherErrorPathMessageStrings shouldBe
        Left(List("enum" -> "Enumeration value 42 is unrecognized!"))
    }

    "generate a writer for matching models" in {

      writer.transform(
        Model("id", 1.23, Instant.ofEpochMilli(0), Some("pickup-id"), List(SubModel(1.2, 3.45)), SubModel(1, 2), ModelEnum.First_Case)
      ) shouldBe
        Protobuf(
          Some("id"),
          Some("1.23"),
          Some(Timestamp.defaultInstance),
          Some("pickup-id"),
          Seq(SubProtobuf("1.2", "3.45")),
          Some(SubProtobuf("1", "2")),
          ProtobufEnum.FirstCase
        )

      writer.transform(Model("id", 1.23, Instant.ofEpochMilli(0), None, Nil, SubModel(1, 2), ModelEnum.SecondCase)) shouldBe
        Protobuf(
          Some("id"),
          Some("1.23"),
          Some(Timestamp.defaultInstance),
          None,
          Seq.empty,
          Some(SubProtobuf("1", "2")),
          ProtobufEnum.SECOND_CASE
        )
    }

    "generate a writer for backward compatible models" in {

      writer2.transform(ModelSmaller("id", 1.23)) shouldBe
        Protobuf(Some("id"), Some("1.23"))

      writer3.transform(
        ModelLarger("id", 1.23, Some("bar"), Instant.ofEpochMilli(0), "baz", None, Some("foo"), Nil, SubModel(1, 2), ModelEnum.THIRD_CASE)
      ) shouldBe
        Protobuf(
          Some("id"),
          Some("1.23"),
          Some(Timestamp.defaultInstance),
          None,
          Seq.empty,
          Some(SubProtobuf("1", "2")),
          ProtobufEnum.Third_Case
        )
    }

    "generate a reader/writer pair for matching models" in {

      val modelA =
        Model("id", 1.23, Instant.ofEpochMilli(0), Some("pickup-id"), List(SubModel(1.2, 3.45)), SubModel(1, 2), ModelEnum.THIRD_CASE)

      reader.transform(writer.transform(modelA)).asEitherErrorPathMessageStrings shouldBe Right(modelA)

      val modelB = Model("id", 1.23, Instant.ofEpochMilli(0), None, Nil, SubModel(1, 2), ModelEnum.SecondCase)

      reader.transform(writer.transform(modelB)).asEitherErrorPathMessageStrings shouldBe Right(modelB)
    }

    "generate a reader/writer pair for reduced backward compatible models" in {

      val model2 = ModelSmaller("id", 1.23)

      reader2.transform(writer2.transform(model2)).asEitherErrorPathMessageStrings shouldBe Right(model2)
    }
  }
}
