package io.moia.protos.teleproto

import io.scalaland.chimney.{Transformer, PartialTransformer}

case class GenericProtobuf1(foo: String, bar: Int)

case class GenericProtobuf2(foo: Int, bar: String)

case class GenericModel[S, T](foo: S, bar: T)

object GenericProtobuf {
  val reader1: PartialTransformer[GenericProtobuf1, GenericModel[String, Int]] =
    PartialTransformer.derive[GenericProtobuf1, GenericModel[String, Int]]

  val reader2: PartialTransformer[GenericProtobuf2, GenericModel[Int, String]] =
    PartialTransformer.derive[GenericProtobuf2, GenericModel[Int, String]]

  val writer1: Transformer[GenericModel[String, Int], GenericProtobuf1] = Transformer.derive[GenericModel[String, Int], GenericProtobuf1]

  val writer2: Transformer[GenericModel[Int, String], GenericProtobuf2] = Transformer.derive[GenericModel[Int, String], GenericProtobuf2]
}

class TypedProtocolBuffersTest extends UnitTest {

  import GenericProtobuf.*

  "TypedProtocolBuffers" should {

    "generate a reader for protobuf matching a typed model" in {

      reader1.transform(GenericProtobuf1("foo", 42)).asEitherErrorPathMessageStrings shouldBe Right(GenericModel("foo", 42))

      reader2.transform(GenericProtobuf2(42, "foo")).asEitherErrorPathMessageStrings shouldBe Right(GenericModel(42, "foo"))
    }

    "generate a writer for a typed model matching a protobuf" in {

      writer1.transform(GenericModel("foo", 42)) shouldBe GenericProtobuf1("foo", 42)

      writer2.transform(GenericModel(42, "foo")) shouldBe GenericProtobuf2(42, "foo")
    }
  }
}
