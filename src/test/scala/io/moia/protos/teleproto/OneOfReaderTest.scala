package io.moia.protos.teleproto

class OneOfReaderTest extends UnitTest {

  import Reader._

  object protobuf {

    case class Foo(price: String) // extends AnyRef!!
    case class Bar(number: Int)   // extends AnyRef!!

    case class FooOrBar(value: FooOrBar.Value)

    object FooOrBar {

      sealed trait Value {
        def isEmpty: Boolean                = false
        def isDefined: Boolean              = true
        def isFoo: Boolean                  = false
        def isBar: Boolean                  = false
        def foo: scala.Option[protobuf.Foo] = None
        def bar: scala.Option[protobuf.Bar] = None
      }
      object Value extends {

        case object Empty extends Value {
          override def isEmpty: Boolean   = true
          override def isDefined: Boolean = false
        }

        case class Foo(value: protobuf.Foo) extends Value {
          override def isFoo: Boolean                  = true
          override def foo: scala.Option[protobuf.Foo] = Some(value)
        }
        case class Bar(value: protobuf.Bar) extends Value {
          override def isBar: Boolean                  = true
          override def bar: scala.Option[protobuf.Bar] = Some(value)
        }
      }
    }

    case class Protobuf(fooOrBar: FooOrBar)
  }

  object model {

    sealed trait FooOrBar
    case class Foo(price: BigDecimal) extends FooOrBar
    case class Bar(number: Int)       extends FooOrBar

    case class Model(fooOrBar: FooOrBar)
  }

  "Reader for one-of to sealed trait" should {

    implicit val fooReader: Reader[protobuf.Foo, model.Foo] =
      (p: protobuf.Foo) => transform[String, BigDecimal](p.price, "/price").map(model.Foo)

    implicit val barReader: Reader[protobuf.Bar, model.Bar] =
      (p: protobuf.Bar) => PbSuccess(model.Bar(p.number))

    implicit val fooOrBarReader: Reader[protobuf.FooOrBar, model.FooOrBar] =
      (p: protobuf.FooOrBar) =>
        None
          .orElse(p.value.foo.map(foo => Reader.transform[protobuf.Foo, model.Foo](foo, "/foo")))
          .orElse(p.value.bar.map(bar => Reader.transform[protobuf.Bar, model.Bar](bar, "/bar")))
          .getOrElse(PbFailure("Value is required."))

    val reader = new Reader[protobuf.Protobuf, model.Model] {

      def read(p: protobuf.Protobuf): PbResult[model.Model] =
        for {
          fooOrBar <- Reader.transform[protobuf.FooOrBar, model.FooOrBar](p.fooOrBar, "/fooOrBar")
        } yield {
          model.Model(fooOrBar)
        }
    }

    "fail if no value is present" in {

      reader.read(protobuf.Protobuf(protobuf.FooOrBar(protobuf.FooOrBar.Value.Empty))) shouldBe PbFailure("/fooOrBar", "Value is required.")
    }

    "fail if inner one-of value could not be read" in {

      reader.read(protobuf.Protobuf(protobuf.FooOrBar(protobuf.FooOrBar.Value.Foo(protobuf.Foo("five-hundred"))))) shouldBe PbFailure(
        "/fooOrBar/foo/price",
        "Value must be a valid decimal number."
      )
    }

    "read successfully if a readable value is present" in {

      reader.read(protobuf.Protobuf(protobuf.FooOrBar(protobuf.FooOrBar.Value.Foo(protobuf.Foo("500.0"))))) shouldBe PbSuccess(
        model.Model(model.Foo(500.0))
      )

      reader.read(protobuf.Protobuf(protobuf.FooOrBar(protobuf.FooOrBar.Value.Bar(protobuf.Bar(42))))) shouldBe PbSuccess(
        model.Model(model.Bar(42))
      )
    }
  }
}
