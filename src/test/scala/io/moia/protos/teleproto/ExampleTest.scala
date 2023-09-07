package io.moia.protos.teleproto

case class A(i: Int)
case class B(i: Int)

case class TestA(x: A)
case class TestB(x: B)

case class ModelA(c: Int, b: TestA, l: String)
case class ModelB(c: Int, b: TestB, l: String)

object ExampleProtobuf {

  @trace
  val added = ProtocolBuffers.reader[ModelA, ModelB]


// Reader.instance[io.moia.protos.teleproto.ModelA, io.moia.protos.teleproto.ModelB](<empty> match {
//   case (protobuf$macro$1 @ _) => {
//     val c = protobuf$macro$1.c;
//     {
//       val b = Reader.transform[io.moia.protos.teleproto.TestA, io.moia.protos.teleproto.TestB](protobuf$macro$1.b, "/".$plus("b"))(Reader.instance[io.moia.protos.teleproto.TestA, io.moia.protos.teleproto.TestB](<empty> match {
//         case (protobuf$macro$2 @ _) => {
//           val x = Reader.transform[io.moia.protos.teleproto.A, io.moia.protos.teleproto.B](protobuf$macro$2.x, "/".$plus("x"))(Reader.instance[io.moia.protos.teleproto.A, io.moia.protos.teleproto.B](<empty> match {
//             case (protobuf$macro$3 @ _) => {
//               val i = protobuf$macro$3.i;
//               PbSuccess[io.moia.protos.teleproto.B](B.apply(i = i)).orElse(PbFailure.combine())
//             }
//           }));
//           x.flatMap(<empty> match {
//           case (x @ _) => PbSuccess[io.moia.protos.teleproto.TestB](TestB.apply(x = x))
//         }).orElse(PbFailure.combine(x))
//         }
//       }));
//       {
//         val l = protobuf$macro$1.l;
//         b.flatMap(<empty> match {
//           case (b @ _) => PbSuccess[io.moia.protos.teleproto.ModelB](ModelB.apply(c = c, b = b, l = l))
//         }).orElse(PbFailure.combine(b))
//       }
//     }
//   }
// })

}

class ExampleTest extends UnitTest {

  "ProtocolBuffers" should {
    "generate a reader" in {
      println(ExampleProtobuf.added.read(ModelA(0, TestA(A(1)), "A")))
    }
  }
}
