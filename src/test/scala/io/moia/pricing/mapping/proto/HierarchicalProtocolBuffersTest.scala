package io.moia.pricing.mapping.proto

import org.scalatest.{Matchers, WordSpec}

/**
  * Tests correct behaviour of generated mappings regarding hierarchical types
  * where a reader/writer for an inner case class can be generated, too.
  */
object HierarchicalProtocolBuffersTest {

  object protobuf {

    // Foo <- top level
    //   oneof
    //   - Bar
    //   - Baz
    //       Qux

    case class Foo(price: String, barOrBaz: BarOrBaz)

    case class BarOrBaz(value: BarOrBaz.Value)

    case class Bar(number: Option[Int]) // extends AnyRef!!
    case class Baz(qux: Qux) // extends AnyRef!!

    case class Qux(text: String)

    object BarOrBaz {

      sealed trait Value {
        def isEmpty: Boolean = false
        def isDefined: Boolean = true
        def isBar: Boolean = false
        def isBaz: Boolean = false
        def bar: scala.Option[protobuf.Bar] = None
        def baz: scala.Option[protobuf.Baz] = None
      }
      object Value extends {

        case object Empty extends Value {
          override def isEmpty: Boolean = true
          override def isDefined: Boolean = false
        }

        case class Bar(value: protobuf.Bar) extends Value {
          override def isBar: Boolean = true
          override def bar: scala.Option[protobuf.Bar] = Some(value)
        }
        case class Baz(value: protobuf.Baz) extends Value {
          override def isBaz: Boolean = true
          override def baz: scala.Option[protobuf.Baz] = Some(value)
        }
      }
    }
  }

  object model {

    case class Foo(price: BigDecimal, barOrBaz: BarOrBaz)

    sealed trait BarOrBaz
    case class Bar(number: Int) extends BarOrBaz
    case class Baz(qux: Qux) extends BarOrBaz

    case class Qux(text: String)
  }
}

class HierarchicalProtocolBuffersTest extends WordSpec with Matchers {

  import HierarchicalProtocolBuffersTest._

  "ProtocolBuffers for hierarchical types" should {

    "generate a writer for all types in hierarchy of a generated type pair" in {

      val writer = ProtocolBuffers.writer[model.Foo, protobuf.Foo]

      writer.write(model.Foo(1.2, model.Bar(42))) shouldBe
        protobuf.Foo("1.2",
                     protobuf.BarOrBaz(
                       protobuf.BarOrBaz.Value.Bar(protobuf.Bar(Some(42)))))

      writer.write(model.Foo(1.2, model.Baz(model.Qux("some text")))) shouldBe
        protobuf.Foo("1.2",
                     protobuf.BarOrBaz(
                       protobuf.BarOrBaz.Value
                         .Baz(protobuf.Baz(protobuf.Qux("some text")))))
    }

    "use an 'explicit' implicit writer before generating a writer for a type in hierarchy of a generated type pair" in {

      implicit val explicitQuxWriter: Writer[model.Qux, protobuf.Qux] =
        (p: model.Qux) => protobuf.Qux("other text")

      val writer = ProtocolBuffers.writer[model.Foo, protobuf.Foo]

      writer.write(model.Foo(1.2, model.Baz(model.Qux("some text")))) shouldBe
        protobuf.Foo("1.2",
                     protobuf.BarOrBaz(
                       protobuf.BarOrBaz.Value
                         .Baz(protobuf.Baz(protobuf.Qux("other text")))))
    }

    "generate a reader for all types in hierarchy of a generated type pair" in {

      val reader = ProtocolBuffers.reader[protobuf.Foo, model.Foo]

      reader.read(
        protobuf.Foo(
          "1.2",
          protobuf.BarOrBaz(
            protobuf.BarOrBaz.Value.Bar(protobuf.Bar(Some(42)))))) shouldBe
        PbSuccess(model.Foo(1.2, model.Bar(42)))

      reader.read(
        protobuf.Foo("1.2",
                     protobuf.BarOrBaz(protobuf.BarOrBaz.Value
                       .Baz(protobuf.Baz(protobuf.Qux("some text")))))) shouldBe
        PbSuccess(model.Foo(1.2, model.Baz(model.Qux("some text"))))
    }

    "use an 'explicit' implicit reader before generating a reader for a type in hierarchy of a generated type pair" in {

      implicit val explicitQuxReader: Reader[protobuf.Qux, model.Qux] =
        (p: protobuf.Qux) => PbFailure("Used the 'explicit' implicit!")

      val reader = ProtocolBuffers.reader[protobuf.Foo, model.Foo]

      reader.read(
        protobuf.Foo("1.2",
                     protobuf.BarOrBaz(protobuf.BarOrBaz.Value
                       .Baz(protobuf.Baz(protobuf.Qux("some text")))))) shouldBe
        PbFailure("/barOrBaz/baz/qux", "Used the 'explicit' implicit!")
    }
  }
}
