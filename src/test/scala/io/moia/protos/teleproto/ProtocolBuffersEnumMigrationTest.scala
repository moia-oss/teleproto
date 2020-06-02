package io.moia.protos.teleproto

object ProtocolBuffersEnumMigrationTest {
  sealed trait EnumV1 {
    type EnumType = EnumV1
  }
  object EnumV1 {
    case object Case1                   extends EnumV1
    case object Case2                   extends EnumV1
    case class Unrecognized(other: Int) extends EnumV1
  }

  sealed trait EnumV2 {
    type EnumType = EnumV2
  }
  object EnumV2 {
    case object Case1                   extends EnumV2
    case object Case2                   extends EnumV2
    case class Unrecognized(other: Int) extends EnumV2
  }

  sealed trait EnumV3 {
    type EnumType = EnumV3
  }
  object EnumV3 {
    case object Case1                   extends EnumV3
    case object Case2                   extends EnumV3
    case object Case3                   extends EnumV3
    case class Unrecognized(other: Int) extends EnumV3
  }

  implicit val fromV1toV2: Migration[EnumV1, EnumV2] = ProtocolBuffers.migration[EnumV1, EnumV2]()

  implicit val fromV2toV3: Migration[EnumV2, EnumV3] = ProtocolBuffers.migration[EnumV2, EnumV3]()

  case class MessageV2(enum: EnumV2)

  case class MessageV3(enum: EnumV3)

  implicit val messageFromV2toV3: Migration[MessageV2, MessageV3] = ProtocolBuffers.migration[MessageV2, MessageV3]()
}

class ProtocolBuffersEnumMigrationTest extends UnitTest {

  import ProtocolBuffersEnumMigrationTest._

  "ProtocolBuffers (migration for enums)" should {

    "prepare a valid migration for similar enums" in {

      fromV1toV2.migrate(EnumV1.Case1) shouldBe EnumV2.Case1
      fromV1toV2.migrate(EnumV1.Case2) shouldBe EnumV2.Case2
      fromV1toV2.migrate(EnumV1.Unrecognized(42)) shouldBe EnumV2.Unrecognized(42)
    }

    "prepare a valid migration from an enum to an extended enum" in {

      fromV2toV3.migrate(EnumV2.Case1) shouldBe EnumV3.Case1
      fromV2toV3.migrate(EnumV2.Case2) shouldBe EnumV3.Case2
      fromV2toV3.migrate(EnumV2.Unrecognized(42)) shouldBe EnumV3.Unrecognized(42)
    }

    "prepare a valid migration from a class to a class both containing an enum" in {

      messageFromV2toV3.migrate(MessageV2(EnumV2.Case1)) shouldBe MessageV3(EnumV3.Case1)
      messageFromV2toV3.migrate(MessageV2(EnumV2.Case2)) shouldBe MessageV3(EnumV3.Case2)
      messageFromV2toV3.migrate(MessageV2(EnumV2.Unrecognized(42))) shouldBe MessageV3(EnumV3.Unrecognized(42))
    }
  }
}
