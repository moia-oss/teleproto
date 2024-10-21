package io.moia.protos.teleproto.internal

import io.moia.protos.teleproto.{PbFailure, PbResult, PbSuccess, Reader}
import io.scalaland.chimney.internal.compiletime.DerivationEngine
import io.scalaland.chimney.partial
import scalapb.UnknownFieldSet

trait ReaderDerivation extends DerivationEngine {

  // example of platform-independent type definition
  protected val MyTypes: MyTypesModule
  protected trait MyTypesModule { this: MyTypes.type =>

    // Provides
    //   - Reader.apply[From, To]: Type[MyTypeClass[From, To]]
    //   - Reader.unapply(tpe: Type[Any]): Option[(??, ??)] // existential types
    val Reader: ReaderModule
    trait ReaderModule extends Type.Ctor2[Reader] { this: Reader.type => }

    // use in platform-independent code (it cannot generate Type instances, as opposed to Scala 2/Scala 3 macros)
    object Implicits {
      implicit def ReaderType[From: Type, To: Type]: Type[Reader[From, To]] = Reader[From, To]
    }
  }

  // example of platform-independent expr utility
  protected val MyExprs: MyExprsModule
  protected trait MyExprsModule { this: MyExprs.type =>

    import MyTypes.Implicits._

    def callReader[From: Type, To: Type](tc: Expr[Reader[From, To]], from: Expr[From]): Expr[partial.Result[To]]

    def createReader[From: Type, To: Type](body: Expr[From] => Expr[To]): Expr[Reader[From, To]]

    def summonReader[From: Type, To: Type]: Option[Expr[Reader[From, To]]] =
      Expr.summonImplicit[Reader[From, To]]

    def matchEnumValues[From: Type, To: Type](
        src: Expr[From],
        fromElements: Enum.Elements[From],
        toElements: Enum.Elements[To],
        mapping: Map[String, String]
    ): Expr[To]

    // use in platform-independent code (since it does not have quotes nor quasiquotes)
    object Implicits {

      implicit class ReaderOps[From: Type, To: Type](private val tc: Expr[Reader[From, To]]) {

        def read(from: Expr[From]): Expr[partial.Result[To]] = callReader(tc, from)
      }
    }
  }

  import MyExprs.Implicits._

  // example of a platform-independent Rule
  object ReaderImplicitRule extends Rule("ReaderImplicit") {

    override def expand[From, To](implicit
        ctx: TransformationContext[From, To]
    ): DerivationResult[Rule.ExpansionResult[To]] = {
      if (ctx.config.isImplicitSummoningPreventedFor[From, To]) {
        // Implicit summoning prevented so
        DerivationResult.attemptNextRule
      } else {
        MyExprs.summonReader[From, To] match {
          case Some(reader) => DerivationResult.expandedPartial(reader.read(ctx.src))
          case None         => DerivationResult.attemptNextRule
        }
      }
    }
  }

  class ProtobufEnumRule(ge: Type[scalapb.GeneratedEnum]) extends Rule("ProtobufEnum") {

    private def tolerantName(name: String): String =
      name.toLowerCase.replace("_", "")

    override def expand[From, To](implicit
        ctx: TransformationContext[From, To]
    ): DerivationResult[Rule.ExpansionResult[To]] = {

      /** The protobuf and model types have to be sealed traits. Iterate through the known subclasses of the model and match the ScalaPB
        * side.
        *
        * If there are more options on the protobuf side, the mapping is forward compatible. If there are more options on the model side,
        * the mapping is not possible.
        *
        * {{{
        * (model: ModelEnum) => p match {
        *   case ModelEnum.OPTION_1 => ProtoEnum.OPTION_1
        *   ...
        *   case ModelEnum.OPTION_N => ProtoEnum.OPTION_N
        * }
        * }}}
        */
      def compileEnumerationMapping(
          fromElements: Enum.Elements[From],
          toElements: Enum.Elements[To]
      ): DerivationResult[Rule.ExpansionResult[To]] = {
        val protoPrefix = simpleName[To]

        val fromElementsByTolerantName =
          fromElements.map(element => tolerantName(element.value.name) -> element).toMap
        val toElementsByTolerantName = toElements.map(element => tolerantName(element.value.name) -> element).toMap ++
          toElements.map(element => tolerantName(element.value.name).stripPrefix(tolerantName(protoPrefix)) -> element).toMap

        // Does not retrieve local names to compare (yet)
        val unmatchedModelOptions = fromElementsByTolerantName.collect {
          case (elementName, element) if !toElementsByTolerantName.contains(elementName) => element
        }

        if (unmatchedModelOptions.nonEmpty) {
          return DerivationResult.attemptNextRuleBecause(
            s"Found unmatched subtypes: ${unmatchedModelOptions.map(tpe => Type.prettyPrint(tpe.Underlying)).mkString(", ")}"
          )
        }

        val mapping = (for {
          (modelName, modelElement) <- fromElementsByTolerantName.toList
          protoElement              <- toElementsByTolerantName.get(modelName)
        } yield modelElement.value.name -> protoElement.value.name).toMap

        val result = MyExprs.matchEnumValues(ctx.src, fromElements, toElements, mapping)
        DerivationResult.expandedTotal(result)
      }

      (Type[From], Type[To]) match {
        case (SealedHierarchy(Enum(fromElements)), SealedHierarchy(Enum(toElements))) if Type[To] <:< ge =>
          compileEnumerationMapping(fromElements, toElements)
        case _ => DerivationResult.attemptNextRule
      }
    }

    private def simpleName[A: Type]: String = {
      val colored = Type.prettyPrint[A]
      val mono    = "\u001b\\[([0-9]+)m".r.replaceAllIn(colored, "")
      val start   = mono.lastIndexOf(".") + 1
      val end     = mono.indexOf("[", start) - 1
      mono.substring(start.max(0), if (end < 0) mono.length else end)
    }
  }

  // TODO: use?
  protected def fromPbResult[T](result: PbResult[T]): partial.Result[T] = {
    result match {
      case PbSuccess(value) => partial.Result.Value(value)
      case PbFailure(errors) => {
        def toError(pbError: (String, String)) = partial.Error(
          partial.ErrorMessage.StringMessage(pbError._2),
          partial.Path.Empty.prepend(partial.PathElement.Accessor(pbError._1))
        )

        errors.toList match {
          case head :: tail => partial.Result.Errors(toError(head), tail.map(toError): _*)
          case Nil          => partial.Result.Errors(partial.Error(partial.ErrorMessage.StringMessage("Unknown error")))
        }
      }
    }
  }

  def readerDerivation[From: Type, To: Type](implicit
      ufst: Type[UnknownFieldSet],
      ge: Type[scalapb.GeneratedEnum]
  ): Expr[Reader[From, To]] =
    MyExprs.createReader[From, To] { (from: Expr[From]) =>
      val cfg = TransformerConfiguration(
        flags = TransformerFlags()
      ) // customize, read config with DSL etc
      val context = TransformationContext.ForTotal.create[From, To](from, cfg)

      deriveFinalTransformationResultExpr(context).toEither.fold(
        derivationErrors => reportError(derivationErrors.toString), // customize
        identity
      )
    }
}
