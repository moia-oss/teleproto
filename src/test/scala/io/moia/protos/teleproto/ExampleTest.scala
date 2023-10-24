package io.moia.protos.teleproto

case class A(i: Int)
case class B(i: Int)

case class TestA(x: A)
case class TestB(x: B)

case class ModelA(c: Int, b: TestA, l: String)
case class ModelB(c: Int, b: TestB, l: String)

class ExampleTest extends UnitTest {

  "ProtocolBuffers" should {
    "generate a simple reader" in {
      val reader = ProtocolBuffers.reader[ModelA, ModelB]
      reader.read(ModelA(0, TestA(A(1)), "A")) shouldBe PbSuccess(ModelB(0, TestB(B(1)), "A"))
    }
  }
}
