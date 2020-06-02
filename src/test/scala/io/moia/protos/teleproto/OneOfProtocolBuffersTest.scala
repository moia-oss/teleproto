package io.moia.protos.teleproto

/**
  * Tests correct behaviour of generated mappings regarding traits.
  */
object OneOfProtocolBuffersTest {

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

  implicit val fooReader: Reader[protobuf.Foo, model.Foo] =
    ProtocolBuffers.reader[protobuf.Foo, model.Foo]

  implicit val barReader: Reader[protobuf.Bar, model.Bar] =
    ProtocolBuffers.reader[protobuf.Bar, model.Bar]

  implicit val fooOrBarReader: Reader[protobuf.FooOrBar, model.FooOrBar] =
    ProtocolBuffers.reader[protobuf.FooOrBar, model.FooOrBar]

  val reader: Reader[protobuf.Protobuf, model.Model] =
    ProtocolBuffers.reader[protobuf.Protobuf, model.Model]

  implicit val fooWriter: Writer[model.Foo, protobuf.Foo] =
    ProtocolBuffers.writer[model.Foo, protobuf.Foo]

  implicit val barWriter: Writer[model.Bar, protobuf.Bar] =
    ProtocolBuffers.writer[model.Bar, protobuf.Bar]

  implicit val fooOrBarWriter: Writer[model.FooOrBar, protobuf.FooOrBar] =
    ProtocolBuffers.writer[model.FooOrBar, protobuf.FooOrBar]

  val writer: Writer[model.Model, protobuf.Protobuf] =
    ProtocolBuffers.writer[model.Model, protobuf.Protobuf]
}

class OneOfProtocolBuffersTest extends UnitTest {

  import OneOfProtocolBuffersTest._

  "ProtocolBuffers for one-of" should {

    "generate a reader for matching sealed traits" in {

      reader.read(protobuf.Protobuf(protobuf.FooOrBar(protobuf.FooOrBar.Value.Empty))) shouldBe PbFailure("/fooOrBar", "Value is required.")

      reader.read(protobuf.Protobuf(protobuf.FooOrBar(protobuf.FooOrBar.Value.Foo(protobuf.Foo("five-hundred"))))) shouldBe PbFailure(
        "/fooOrBar/foo/price",
        "Value must be a valid decimal number."
      )

      reader.read(protobuf.Protobuf(protobuf.FooOrBar(protobuf.FooOrBar.Value.Foo(protobuf.Foo("500.0"))))) shouldBe PbSuccess(
        model.Model(model.Foo(500.0))
      )

      reader.read(protobuf.Protobuf(protobuf.FooOrBar(protobuf.FooOrBar.Value.Bar(protobuf.Bar(42))))) shouldBe PbSuccess(
        model.Model(model.Bar(42))
      )
    }

    "generate a writer for matching sealed traits" in {

      writer.write(model.Model(model.Foo(500.0))) shouldBe protobuf.Protobuf(
        protobuf.FooOrBar(protobuf.FooOrBar.Value.Foo(protobuf.Foo("500.0")))
      )

      writer.write(model.Model(model.Bar(42))) shouldBe protobuf.Protobuf(protobuf.FooOrBar(protobuf.FooOrBar.Value.Bar(protobuf.Bar(42))))
    }
  }
}
