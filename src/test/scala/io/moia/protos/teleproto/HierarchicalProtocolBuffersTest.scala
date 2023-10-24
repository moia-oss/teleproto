package io.moia.protos.teleproto

import scalapb.GeneratedOneof

/** Tests correct behaviour of generated mappings regarding hierarchical types where a reader/writer for an inner case class can be
  * generated, too.
  */
object HierarchicalProtocolBuffersTest {

  object protobuf {

    // Foo <- top level
    //   oneof
    //   - Bar
    //   - Baz
    //       Qux

    final case class Foo(price: String, barOrBaz: BarOrBaz)

    final case class Bar(number: Option[Int]) // extends AnyRef!!
    final case class Baz(qux: Qux)            // extends AnyRef!!

    final case class Qux(text: String)

    sealed trait BarOrBaz extends GeneratedOneof {
      def isEmpty: Boolean                = false
      def isDefined: Boolean              = true
      def isBar: Boolean                  = false
      def isBaz: Boolean                  = false
      def bar: scala.Option[protobuf.Bar] = None
      def baz: scala.Option[protobuf.Baz] = None
    }

    object BarOrBaz {
      case object Empty extends BarOrBaz {
        override type ValueType = Nothing
        override def number: Int        = 0
        override def value: ValueType   = ???
        override def isEmpty: Boolean   = true
        override def isDefined: Boolean = false
      }

      final case class Bar(value: protobuf.Bar) extends BarOrBaz {
        override type ValueType = protobuf.Bar
        override def number: Int                     = 1
        override def isBar: Boolean                  = true
        override def bar: scala.Option[protobuf.Bar] = Some(value)
      }

      final case class Baz(value: protobuf.Baz) extends BarOrBaz {
        override type ValueType = protobuf.Baz
        override def number: Int                     = 2
        override def isBaz: Boolean                  = true
        override def baz: scala.Option[protobuf.Baz] = Some(value)
      }
    }
  }

  object model {

    final case class Foo(price: BigDecimal, barOrBaz: BarOrBaz)

    sealed trait BarOrBaz
    final case class Bar(number: Int) extends BarOrBaz
    final case class Baz(qux: Qux)    extends BarOrBaz

    final case class Qux(text: String)
  }
}

class HierarchicalProtocolBuffersTest extends UnitTest {

  import HierarchicalProtocolBuffersTest._

  "ProtocolBuffers for hierarchical types" should {

//    "generate a writer for all types in hierarchy of a generated type pair" in {
//
//      val writer = ProtocolBuffers.writer[model.Foo, protobuf.Foo]
//
//      writer.write(model.Foo(1.2, model.Bar(42))) shouldBe
//        protobuf.Foo("1.2", protobuf.BarOrBaz.Bar(protobuf.Bar(Some(42))))
//
//      writer.write(model.Foo(1.2, model.Baz(model.Qux("some text")))) shouldBe
//        protobuf.Foo("1.2", protobuf.BarOrBaz.Baz(protobuf.Baz(protobuf.Qux("some text"))))
//    }

//    "use an 'explicit' implicit writer before generating a writer for a type in hierarchy of a generated type pair" in {
//
//      implicit val explicitQuxWriter: Writer[model.Qux, protobuf.Qux] =
//        (p: model.Qux) => protobuf.Qux("other text")
//
//      val writer = ProtocolBuffers.writer[model.Foo, protobuf.Foo]
//
//      writer.write(model.Foo(1.2, model.Baz(model.Qux("some text")))) shouldBe
//        protobuf.Foo("1.2", protobuf.BarOrBaz.Baz(protobuf.Baz(protobuf.Qux("other text"))))
//    }

    "generate a reader for all types in hierarchy of a generated type pair" in {

      val reader = ProtocolBuffers.reader[protobuf.BarOrBaz, model.BarOrBaz]
    }

//    "use an 'explicit' implicit reader before generating a reader for a type in hierarchy of a generated type pair" in {
//
//      implicit val explicitQuxReader: Reader[protobuf.Qux, model.Qux] =
//        (p: protobuf.Qux) => PbFailure("Used the 'explicit' implicit!")
//
//      val reader = ProtocolBuffers.reader[protobuf.Foo, model.Foo]
//
//      reader.read(protobuf.Foo("1.2", protobuf.BarOrBaz.Baz(protobuf.Baz(protobuf.Qux("some text"))))) shouldBe
//        PbFailure("/barOrBaz/baz/qux", "Used the 'explicit' implicit!")
//    }
  }
}
