package io.moia.protos.teleproto

import io.scalaland.chimney.{partial, PartialTransformer, Transformer}

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

  val fromV2toV3: Transformer[ProtoV2, ProtoV3] =
    Transformer
      .define[ProtoV2, ProtoV3]
      .withFieldComputed(_.qux, _.baz.getOrElse("default qux"))
      .buildTransformer

  val fromProto3toModel3: PartialTransformer[ProtoV3, ModelForV3] = PartialTransformer.derive[ProtoV3, ModelForV3]

  val readerForV2: PartialTransformer[ProtoV2, ModelForV3] = PartialTransformer { v2 =>
    fromProto3toModel3.transform(fromV2toV3.transform(v2))
  }

  val fromV1toV2: Transformer[ProtoV1, ProtoV2] =
    Transformer.define[ProtoV1, ProtoV2].withFieldRenamed(_.foo, _.renamedFoo).buildTransformer

  val readerForV1: PartialTransformer[ProtoV1, ModelForV3] = PartialTransformer { v1 =>
    fromProto3toModel3.transform(fromV2toV3.transform(fromV1toV2.transform(v1)))
  }
}

class ProtocolBuffersMigrationChainTest extends UnitTest {

  import ProtocolBuffersMigrationChainTest.{*, given}

  "ProtocolBuffers (migration chain)" should {

    "prepare a valid migration for simple case classes" in {

      fromV1toV2.transform(ProtoV1(foo = "foo-value", 42, "baz-value")) shouldBe ProtoV2(renamedFoo = "foo-value", 42, Some("baz-value"))

      fromV2toV3.transform(ProtoV2("foo-value", 42, Some("baz-value"))) shouldBe ProtoV3("foo-value", 42L, "baz-value")
      fromV2toV3.transform(ProtoV2("foo-value", 42, None)) shouldBe ProtoV3("foo-value", 42L, "default qux")
    }

    "create a reader from prepared migration for simple case classes" in {

      readerForV1.transform(ProtoV1(foo = "foo-value", 42, "baz-value")).asEitherErrorPathMessageStrings shouldBe
        Right(ModelForV3("foo-value", 42L, "baz-value"))

      readerForV2.transform(ProtoV2(renamedFoo = "foo-value", 42, Some("baz-value"))).asEitherErrorPathMessageStrings shouldBe
        Right(ModelForV3("foo-value", 42L, "baz-value"))
      readerForV2.transform(ProtoV2(renamedFoo = "foo-value", 42, None)).asEitherErrorPathMessageStrings shouldBe
        Right(ModelForV3("foo-value", 42L, "default qux"))
    }
  }
}
