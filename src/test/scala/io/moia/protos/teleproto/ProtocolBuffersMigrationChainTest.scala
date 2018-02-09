package io.moia.protos.teleproto

import org.scalatest.{Matchers, WordSpec}

object ProtocolBuffersMigrationChainTest {

  // V3 is the latest version, the "business model" matches the latest version
  // Since V2 four changes happened:
  // - `baz` has been removed                           -> no problem, ignored
  // - `bar` has been widened from Int to Long          -> no problem, implicit view is available
  // - `qux` was added                                  -> requires a value

  case class ProtoV3(renamedFoo: String, bar: Long, qux: String)

  case class ModelForV3(renamedFoo: String, bar: Long, qux: String)

  // V2 is the version before V3, there was a business model at that time
  // Since V1 three changes happened:
  // - `foo` has been removed       -> no problem, ignored
  // - `renamedFoo` has been added  -> requires a value
  // - `baz` was made optional      -> no problem, can be wrapped with `Some`

  case class ProtoV2(renamedFoo: String, bar: Int, baz: Option[String])

  // case class ModelAtV2(renamedFoo: String, bar: Int, baz: Option[BigDecimal])

  // V1 is the version before V2, there was a business model at that time
  case class ProtoV1(foo: String, bar: Int, baz: String)

  // case class ModelAtV1(foo: String, bar: Int, baz: Option[BigDecimal])

  implicit val readerForV3: Reader[ProtoV3, ModelForV3] = ProtocolBuffers.reader[ProtoV3, ModelForV3]

  implicit val fromV2toV3: Migration[ProtoV2, ProtoV3] = ProtocolBuffers.migration[ProtoV2, ProtoV3](_.baz.getOrElse("default qux"))

  implicit val readerForV2: Reader[ProtoV2, ModelForV3] = fromV2toV3.reader[ModelForV3]

  implicit val fromV1toV2: Migration[ProtoV1, ProtoV2] = ProtocolBuffers.migration[ProtoV1, ProtoV2](_.foo)

  implicit val readerForV1: Reader[ProtoV1, ModelForV3] = fromV1toV2.reader[ModelForV3]
}

class ProtocolBuffersMigrationChainTest extends WordSpec with Matchers {

  import ProtocolBuffersMigrationChainTest._

  "ProtocolBuffers (migration chain)" should {

    "prepare a valid migration for simple case classes" in {

      fromV1toV2.migrate(ProtoV1(foo = "foo-value", 42, "baz-value")) shouldBe ProtoV2(renamedFoo = "foo-value", 42, Some("baz-value"))

      fromV2toV3.migrate(ProtoV2("foo-value", 42, Some("baz-value"))) shouldBe ProtoV3("foo-value", 42L, "baz-value")
      fromV2toV3.migrate(ProtoV2("foo-value", 42, None)) shouldBe ProtoV3("foo-value", 42L, "default qux")
    }

    "create a reader from prepared migration for simple case classes" in {

      fromV1toV2.reader[ModelForV3].read(ProtoV1(foo = "foo-value", 42, "baz-value")) shouldBe
        PbSuccess(ModelForV3("foo-value", 42L, "baz-value"))

      fromV2toV3.reader[ModelForV3].read(ProtoV2(renamedFoo = "foo-value", 42, Some("baz-value"))) shouldBe
        PbSuccess(ModelForV3("foo-value", 42L, "baz-value"))
      fromV2toV3.reader[ModelForV3].read(ProtoV2(renamedFoo = "foo-value", 42, None)) shouldBe
        PbSuccess(ModelForV3("foo-value", 42L, "default qux"))
    }
  }
}
