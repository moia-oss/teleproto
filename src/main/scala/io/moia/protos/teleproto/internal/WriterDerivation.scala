package io.moia.protos.teleproto.internal

import io.moia.protos.teleproto.Writer
import io.scalaland.chimney.internal.compiletime.DerivationEngine
import scalapb.UnknownFieldSet

trait WriterDerivation extends DerivationEngine {

  // example of platform-independent type definition
  protected val MyTypes: MyTypesModule
  protected trait MyTypesModule { this: MyTypes.type =>

    // Provides
    //   - Writer.apply[From, To]: Type[MyTypeClass[From, To]]
    //   - Writer.unapply(tpe: Type[Any]): Option[(??, ??)] // existential types
    val Writer: WriterModule
    trait WriterModule extends Type.Ctor2[Writer] { this: Writer.type => }

    // use in platform-independent code (it cannot generate Type instances, as opposed to Scala 2/Scala 3 macros)
    object Implicits {
      implicit def WriterType[From: Type, To: Type]: Type[Writer[From, To]] = Writer[From, To]
    }
  }

  // example of platform-independent expr utility
  protected val MyExprs: MyExprsModule
  protected trait MyExprsModule { this: MyExprs.type =>

    import MyTypes.Implicits._

    def callMyTypeClass[From: Type, To: Type](tc: Expr[Writer[From, To]], from: Expr[From]): Expr[To]

    def createTypeClass[From: Type, To: Type](body: Expr[From] => Expr[To]): Expr[Writer[From, To]]

    def summonMyTypeClass[From: Type, To: Type]: Option[Expr[Writer[From, To]]] =
      Expr.summonImplicit[Writer[From, To]]

    def matchEnumValues[From: Type, To: Type](
        src: Expr[From],
        fromElements: Enum.Elements[From],
        toElements: Enum.Elements[To],
        mapping: Map[String, String]
    ): Expr[To]

    // use in platform-independent code (since it does not have quotes nor quasiquotes)
    object Implicits {

      implicit class WriterOps[From: Type, To: Type](private val tc: Expr[Writer[From, To]]) {

        def write(from: Expr[From]): Expr[To] = callMyTypeClass(tc, from)
      }
    }
  }

  import MyExprs.Implicits._

  // example of a platform-independent Rule
  object WriterImplicitRule extends Rule("WriterImplicit") {

    override def expand[From, To](implicit
        ctx: TransformationContext[From, To]
    ): DerivationResult[Rule.ExpansionResult[To]] = {
      if (ctx.config.isImplicitSummoningPreventedFor[From, To]) {
        // Implicit summoning prevented so
        DerivationResult.attemptNextRule
      } else {
        MyExprs.summonMyTypeClass[From, To] match {
          case Some(writer) => DerivationResult.expandedTotal(writer.write(ctx.src))
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

//  val flags = TransformerFlags().setDefaultValueOfType[UnknownFieldSet](true)

  def writerDerivation[From: Type, To: Type](implicit
      ufst: Type[UnknownFieldSet],
      ge: Type[scalapb.GeneratedEnum]
  ): Expr[Writer[From, To]] =
    MyExprs.createTypeClass[From, To] { (from: Expr[From]) =>
      val cfg = TransformerConfiguration(
        flags = TransformerFlags()
          .setDefaultValueOfType[UnknownFieldSet](true)
      ) // customize, read config with DSL etc
      val context = TransformationContext.ForTotal.create[From, To](from, cfg)

      deriveFinalTransformationResultExpr(context).toEither.fold(
        derivationErrors => reportError(derivationErrors.toString), // customize
        identity
      )
    }
}
