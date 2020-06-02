package io.moia.protos.teleproto

case class GenericProtobuf1(foo: String, bar: Int)

case class GenericProtobuf2(foo: Int, bar: String)

case class GenericModel[S, T](foo: S, bar: T)

object GenericProtobuf {

  type BoundModel1 = GenericModel[String, Int]

  type BoundModel2 = GenericModel[Int, String]

  val reader1: Reader[GenericProtobuf1, BoundModel1] = ProtocolBuffers.reader[GenericProtobuf1, BoundModel1]

  val reader2: Reader[GenericProtobuf2, BoundModel2] = ProtocolBuffers.reader[GenericProtobuf2, BoundModel2]

  val writer1: Writer[BoundModel1, GenericProtobuf1] = ProtocolBuffers.writer[BoundModel1, GenericProtobuf1]

  val writer2: Writer[BoundModel2, GenericProtobuf2] = ProtocolBuffers.writer[BoundModel2, GenericProtobuf2]
}

class TypedProtocolBuffersTest extends UnitTest {

  import GenericProtobuf._

  "TypedProtocolBuffers" should {

    "generate a reader for protobuf matching a typed model" in {

      reader1.read(GenericProtobuf1("foo", 42)) shouldBe PbSuccess(GenericModel("foo", 42))

      reader2.read(GenericProtobuf2(42, "foo")) shouldBe PbSuccess(GenericModel(42, "foo"))
    }

    "generate a writer for a typed model matching a protobuf" in {

      writer1.write(GenericModel("foo", 42)) shouldBe GenericProtobuf1("foo", 42)

      writer2.write(GenericModel(42, "foo")) shouldBe GenericProtobuf2(42, "foo")
    }
  }
}
