package io.moia.protos.teleproto

import io.moia.migration.migration.{V1, V2, V3}

object ProtocolBuffersEnumMigrationTest {
  case class MessageV2(enumeration: V2.Enum)
  case class MessageV3(enumeration: V3.Enum)

  implicit val fromV1toV2: Migration[V1.Enum, V2.Enum]            = ProtocolBuffers.migration[V1.Enum, V2.Enum]()
  implicit val fromV2toV3: Migration[V2.Enum, V3.Enum]            = ProtocolBuffers.migration[V2.Enum, V3.Enum]()
  implicit val messageFromV2toV3: Migration[MessageV2, MessageV3] = ProtocolBuffers.migration[MessageV2, MessageV3]()
}

class ProtocolBuffersEnumMigrationTest extends UnitTest {
  import ProtocolBuffersEnumMigrationTest._

  "ProtocolBuffers (migration for enums)" should {
    "prepare a valid migration for similar enums" in {
      fromV1toV2.migrate(V1.Enum.Case1) shouldBe V2.Enum.Case1
      fromV1toV2.migrate(V1.Enum.Case2) shouldBe V2.Enum.Case2
      fromV1toV2.migrate(V1.Enum.Unrecognized(42)) shouldBe V2.Enum.Unrecognized(42)
    }

    "prepare a valid migration from an enum to an extended enum" in {
      fromV2toV3.migrate(V2.Enum.Case1) shouldBe V3.Enum.Case1
      fromV2toV3.migrate(V2.Enum.Case2) shouldBe V3.Enum.Case2
      fromV2toV3.migrate(V2.Enum.Unrecognized(42)) shouldBe V3.Enum.Unrecognized(42)
    }

    "prepare a valid migration from a class to a class both containing an enum" in {
      messageFromV2toV3.migrate(MessageV2(V2.Enum.Case1)) shouldBe MessageV3(V3.Enum.Case1)
      messageFromV2toV3.migrate(MessageV2(V2.Enum.Case2)) shouldBe MessageV3(V3.Enum.Case2)
      messageFromV2toV3.migrate(MessageV2(V2.Enum.Unrecognized(42))) shouldBe MessageV3(V3.Enum.Unrecognized(42))
    }
  }
}
