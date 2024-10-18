package io.moia.protos.teleproto

import java.time.Instant

import com.google.protobuf.Descriptors
import com.google.protobuf.timestamp.Timestamp
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

  implicit val subReader: Reader[SubProtobuf, SubModel] = ProtocolBuffers.reader[SubProtobuf, SubModel]

  val reader: Reader[Protobuf, Model] = ProtocolBuffers.reader[Protobuf, Model]

  @backward("2e0e9b")
  val reader2: Reader[Protobuf, ModelSmaller] = ProtocolBuffers.reader[Protobuf, ModelSmaller]

  @backward("84be06")
  val reader3: Reader[Protobuf, ModelLarger] = ProtocolBuffers.reader[Protobuf, ModelLarger]

  val writer: Writer[Model, Protobuf] = ProtocolBuffers.writer[Model, Protobuf]

  // TODO: uncomment and implement
//  @forward("2e0e9b")
//  val writer2: Writer[ModelSmaller, Protobuf] = ProtocolBuffers.writer[ModelSmaller, Protobuf]

  @forward("84be06")
  val writer3: Writer[ModelLarger, Protobuf] = ProtocolBuffers.writer[ModelLarger, Protobuf]
}

class ProtocolBuffersTest extends UnitTest {

  import Protobuf._

  "ProtocolBuffers" should {

    "generate a reader for matching models" in {

      reader.read(Protobuf(None, Some("1.2"), Some(Timestamp.defaultInstance), None, Nil, Some(SubProtobuf("1", "2")))) shouldBe PbFailure(
        "/id",
        "Value is required."
      )

      reader.read(
        Protobuf(Some("foo"), Some("bar"), Some(Timestamp.defaultInstance), None, Nil, Some(SubProtobuf("1", "2")))
      ) shouldBe PbFailure(
        "/price",
        "Value must be a valid decimal number."
      )

      reader.read(
        Protobuf(
          Some("foo"),
          Some("1.2"),
          Some(Timestamp.defaultInstance),
          Some("pickup"),
          Nil,
          Some(SubProtobuf("1", "2")),
          ProtobufEnum.FirstCase
        )
      ) shouldBe
        PbSuccess(Model("foo", 1.2, Instant.ofEpochMilli(0), Some("pickup"), Nil, SubModel(1, 2), ModelEnum.First_Case))

      reader.read(
        Protobuf(
          Some("foo"),
          Some("1.2"),
          Some(Timestamp.defaultInstance),
          None,
          Seq(SubProtobuf("1", "1.2"), SubProtobuf("1.2", "1.23")),
          Some(SubProtobuf("1", "2")),
          ProtobufEnum.SECOND_CASE
        )
      ) shouldBe
        PbSuccess(
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

      reader.read(
        Protobuf(
          Some("foo"),
          Some("1.2"),
          Some(Timestamp.defaultInstance),
          None,
          Seq(SubProtobuf("1", "1.2"), SubProtobuf("1.2", "Milestein One")),
          Some(SubProtobuf("1", "2"))
        )
      ) shouldBe
        PbFailure("/ranges(1)/to", "Value must be a valid decimal number.")
    }

    "generate a reader that collects all errors" in {

      reader.read(
        Protobuf(
          None,
          None,
          None,
          None,
          Seq(SubProtobuf("foo", "bar"), SubProtobuf("baz", "qux")),
          Some(SubProtobuf("one", "two")),
          ProtobufEnum.Unrecognized(42)
        )
      ) shouldBe
        PbFailure(
          Seq(
            "/id"             -> "Value is required.",
            "/price"          -> "Value is required.",
            "/time"           -> "Value is required.",
            "/ranges(0)/from" -> "Value must be a valid decimal number.",
            "/ranges(0)/to"   -> "Value must be a valid decimal number.",
            "/ranges(1)/from" -> "Value must be a valid decimal number.",
            "/ranges(1)/to"   -> "Value must be a valid decimal number.",
            "/doubleSub/from" -> "Value must be a valid decimal number.",
            "/doubleSub/to"   -> "Value must be a valid decimal number.",
            "/enum"           -> "Enumeration value 42 is unrecognized!"
          )
        )
    }

    "generate a reader for backward compatible models" in {

      reader2.read(
        Protobuf(Some("foo"), Some("1.2"), Some(Timestamp.defaultInstance), None, Seq(SubProtobuf("1", "1.2"), SubProtobuf("1.2", "1.23")))
      ) shouldBe
        PbSuccess(ModelSmaller("foo", 1.2))

      reader3.read(
        Protobuf(
          Some("foo"),
          Some("1.2"),
          Some(Timestamp.defaultInstance),
          None,
          Seq(SubProtobuf("1", "1.2"), SubProtobuf("1.2", "1.23")),
          Some(SubProtobuf("1", "2")),
          ProtobufEnum.Third_Case
        )
      ) shouldBe
        PbSuccess(
          ModelLarger(
            id = "foo",
            price = 1.2,
            time = Instant.ofEpochMilli(0),
            pickupId = None,
            baz = None,
            ranges = List(SubModel(1, 1.2), SubModel(1.2, 1.23)),
            doubleSub = SubModel(1, 2),
            enum = ModelEnum.THIRD_CASE
          )
        )
    }

    "generate a reader for ScalaPB enums that handles Unrecognized as a failure" in {

      reader.read(
        Protobuf(
          Some("foo"),
          Some("1.2"),
          Some(Timestamp.defaultInstance),
          None,
          Seq(SubProtobuf("1", "1.2"), SubProtobuf("1.2", "1.23")),
          Some(SubProtobuf("1", "2")),
          ProtobufEnum.Unrecognized(42)
        )
      ) shouldBe
        PbFailure("/enum", "Enumeration value 42 is unrecognized!")
    }

    "generate a writer for matching models" in {

      writer.write(
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

      writer.write(Model("id", 1.23, Instant.ofEpochMilli(0), None, Nil, SubModel(1, 2), ModelEnum.SecondCase)) shouldBe
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

      // TODO: uncomment
//      writer2.write(ModelSmaller("id", 1.23)) shouldBe
//        Protobuf(Some("id"), Some("1.23"))

      writer3.write(
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

      reader.read(writer.write(modelA)) shouldBe PbSuccess(modelA)

      val modelB = Model("id", 1.23, Instant.ofEpochMilli(0), None, Nil, SubModel(1, 2), ModelEnum.SecondCase)

      reader.read(writer.write(modelB)) shouldBe PbSuccess(modelB)
    }

    "generate a reader/writer pair for reduced backward compatible models" in {

      val model2 = ModelSmaller("id", 1.23)

      // TODO: uncomment
//      reader2.read(writer2.write(model2)) shouldBe PbSuccess(model2)
    }
  }
}
