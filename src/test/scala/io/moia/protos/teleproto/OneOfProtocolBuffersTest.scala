package io.moia.protos.teleproto

import scalapb.GeneratedOneof
import io.scalaland.chimney.dsl._
import io.scalaland.chimney.protobufs._
import io.scalaland.chimney.{Transformer, PartialTransformer}

/** Tests correct behaviour of generated mappings regarding traits.
  */
object OneOfProtocolBuffersTest {
  object protobuf {
    final case class Foo(price: String) // extends AnyRef!!
    final case class Bar(number: Int)   // extends AnyRef!!

    sealed trait FooOrBar extends GeneratedOneof {
      def isEmpty: Boolean                = false
      def isDefined: Boolean              = true
      def isFoo: Boolean                  = false
      def isBar: Boolean                  = false
      def foo: scala.Option[protobuf.Foo] = None
      def bar: scala.Option[protobuf.Bar] = None
    }

    object FooOrBar {
      case object Empty extends FooOrBar {
        override type ValueType = Nothing
        override def number: Int        = 0
        override def value: ValueType   = ???
        override def isEmpty: Boolean   = true
        override def isDefined: Boolean = false
      }

      final case class Foo(value: protobuf.Foo) extends FooOrBar {
        override type ValueType = protobuf.Foo
        override def number: Int                     = 1
        override def isFoo: Boolean                  = true
        override def foo: scala.Option[protobuf.Foo] = Some(value)
      }

      final case class Bar(value: protobuf.Bar) extends FooOrBar {
        override type ValueType = protobuf.Bar
        override def number: Int                     = 2
        override def isBar: Boolean                  = true
        override def bar: scala.Option[protobuf.Bar] = Some(value)
      }
    }

    final case class Protobuf(fooOrBar: FooOrBar)
  }

  object model {
    sealed trait FooOrBar
    case class Foo(price: BigDecimal) extends FooOrBar
    case class Bar(number: Int)       extends FooOrBar

    case class Model(fooOrBar: FooOrBar)
  }

  given PartialTransformer[protobuf.Foo, model.Foo] =
    PartialTransformer.derive[protobuf.Foo, model.Foo]

  given PartialTransformer[protobuf.Bar, model.Bar] =
    PartialTransformer.derive[protobuf.Bar, model.Bar]

  given PartialTransformer[protobuf.FooOrBar, model.FooOrBar] =
    PartialTransformer.derive[protobuf.FooOrBar, model.FooOrBar]

  val reader: PartialTransformer[protobuf.Protobuf, model.Model] =
    PartialTransformer.derive[protobuf.Protobuf, model.Model]

  given Transformer[model.Foo, protobuf.Foo] =
    Transformer.derive[model.Foo, protobuf.Foo]

  given Transformer[model.Bar, protobuf.Bar] =
    Transformer.derive[model.Bar, protobuf.Bar]

  given Transformer[model.FooOrBar, protobuf.FooOrBar] =
    Transformer.derive[model.FooOrBar, protobuf.FooOrBar]

  val writer: Transformer[model.Model, protobuf.Protobuf] =
    Transformer.derive[model.Model, protobuf.Protobuf]
}

class OneOfProtocolBuffersTest extends UnitTest {

  import OneOfProtocolBuffersTest.{_, given}

  "ProtocolBuffers for one-of" should {

    "generate a reader for matching sealed traits" in {

      reader.transform(protobuf.Protobuf(protobuf.FooOrBar.Empty)).asEitherErrorPathMessageStrings shouldBe
        Left(List(("fooOrBar", "empty value")))

      reader
        .transform(protobuf.Protobuf(protobuf.FooOrBar.Foo(protobuf.Foo("five-hundred"))))
        .asEitherErrorPathMessageStrings shouldBe Left(
        List(("fooOrBar.price", "Character f is neither a decimal digit number, decimal point, nor \"e\" notation exponential mark."))
      )

      reader.transform(protobuf.Protobuf(protobuf.FooOrBar.Foo(protobuf.Foo("500.0")))).asEitherErrorPathMessageStrings shouldBe Right(
        model.Model(model.Foo(500.0))
      )

      reader.transform(protobuf.Protobuf(protobuf.FooOrBar.Bar(protobuf.Bar(42)))).asEitherErrorPathMessageStrings shouldBe Right(
        model.Model(model.Bar(42))
      )
    }

    "generate a writer for matching sealed traits" in {

      writer.transform(model.Model(model.Foo(500.0))) shouldBe protobuf.Protobuf(
        protobuf.FooOrBar.Foo(protobuf.Foo("500.0"))
      )

      writer.transform(model.Model(model.Bar(42))) shouldBe protobuf.Protobuf(protobuf.FooOrBar.Bar(protobuf.Bar(42)))
    }
  }
}
