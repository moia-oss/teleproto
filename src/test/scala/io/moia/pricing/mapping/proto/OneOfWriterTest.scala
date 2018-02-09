package io.moia.pricing.mapping.proto

import org.scalatest.{Matchers, OptionValues, WordSpec}

class OneOfWriterTest extends WordSpec with Matchers with OptionValues {

  import Writer._

  object protobuf {

    case class Foo(price: String) // extends AnyRef!!
    case class Bar(number: Int) // extends AnyRef!!

    case class FooOrBar(value: FooOrBar.Value)

    object FooOrBar {

      sealed trait Value {
        def isEmpty: Boolean = false
        def isDefined: Boolean = true
        def isFoo: Boolean = false
        def isBar: Boolean = false
        def foo: scala.Option[protobuf.Foo] = None
        def bar: scala.Option[protobuf.Bar] = None
      }
      object Value extends {

        case object Empty extends Value {
          override def isEmpty: Boolean = true
          override def isDefined: Boolean = false
        }

        case class Foo(value: protobuf.Foo) extends Value {
          override def isFoo: Boolean = true
          override def foo: scala.Option[protobuf.Foo] = Some(value)
        }
        case class Bar(value: protobuf.Bar) extends Value {
          override def isBar: Boolean = true
          override def bar: scala.Option[protobuf.Bar] = Some(value)
        }
      }
    }

    case class Protobuf(fooOrBar: FooOrBar)
  }

  object model {

    sealed trait FooOrBar
    case class Foo(price: BigDecimal) extends FooOrBar
    case class Bar(number: Int) extends FooOrBar

    case class Model(fooOrBar: FooOrBar)
  }

  "Writer for one-of" should {

    implicit val fooWriter: Writer[model.Foo, protobuf.Foo] =
      (m: model.Foo) => protobuf.Foo(transform[BigDecimal, String](m.price))

    implicit val barWriter: Writer[model.Bar, protobuf.Bar] =
      (m: model.Bar) => protobuf.Bar(m.number)

    implicit val fooOrBarWriter: Writer[model.FooOrBar, protobuf.FooOrBar] = {
      case foo: model.Foo =>
        protobuf.FooOrBar(
          protobuf.FooOrBar.Value.Foo(transform[model.Foo, protobuf.Foo](foo)))
      case bar: model.Bar =>
        protobuf.FooOrBar(
          protobuf.FooOrBar.Value.Bar(transform[model.Bar, protobuf.Bar](bar)))
    }

    val writer = new Writer[model.Model, protobuf.Protobuf] {

      def write(m: model.Model): protobuf.Protobuf =
        protobuf.Protobuf(
          transform[model.FooOrBar, protobuf.FooOrBar](m.fooOrBar))
    }

    "write model with sealed trait" in {

      writer.write(model.Model(model.Foo(500.0))) shouldBe protobuf.Protobuf(
        protobuf.FooOrBar(protobuf.FooOrBar.Value.Foo(protobuf.Foo("500.0"))))

      writer.write(model.Model(model.Bar(42))) shouldBe protobuf.Protobuf(
        protobuf.FooOrBar(protobuf.FooOrBar.Value.Bar(protobuf.Bar(42))))
    }
  }
}
