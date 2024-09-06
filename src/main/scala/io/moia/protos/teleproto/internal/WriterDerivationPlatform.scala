package io.moia.protos.teleproto.internal

import io.moia.protos.teleproto.Writer
import io.scalaland.chimney.internal.compiletime.{DerivationEnginePlatform, StandardRules}

trait WriterDerivationPlatform extends DerivationEnginePlatform with WriterDerivation with StandardRules {

  // in Scala-2-specific code, remember to import content of the universe
  import c.universe._

  protected object MyTypes extends MyTypesModule {

    import Type.platformSpecific._

    object Writer extends WriterModule {
      def apply[From: Type, To: Type]: Type[Writer[From, To]] = weakTypeTag[Writer[From, To]]
      def unapply[A](A: Type[A]): Option[(??, ??)] =
        A.asCtor[Writer[?, ?]].map(A0 => A0.param(0) -> A0.param(1)) // utility from Type.platformSpecific.*
    }
  }

  protected object MyExprs extends MyExprsModule {

    def callMyTypeClass[From: Type, To: Type](tc: Expr[Writer[From, To]], from: Expr[From]): Expr[To] =
      c.Expr[To](q"""$tc.write($from)""")

    def createTypeClass[From: Type, To: Type](body: Expr[From] => Expr[To]): Expr[Writer[From, To]] = {
      println(s"Creating type class for _root_.io.moia.protos.teleproto.Writer[${Type[From]}, ${Type[To]}]")
      val name = freshTermName("from")
      // remember to use full qualified names in Scala 2 macros!!!
      c.Expr[Writer[From, To]](
        q"""
        new _root_.io.moia.protos.teleproto.Writer[${Type[From]}, ${Type[To]}] {
          def write($name: ${Type[From]}): ${Type[To]} = ${body(c.Expr[From](q"$name"))}
        }
        """
      )
    }

    // TODO: should it be here?
    private def freshTermName(prefix: String): ExprPromiseName =
      // Scala 3 generate prefix$macro$[n] while Scala 2 prefix[n] and we want to align the behavior
      c.internal.reificationSupport.freshTermName(prefix.toLowerCase + "$macro$")
  }

  final override protected val rulesAvailableForPlatform: List[Rule] = List(
    WriterImplicitRule, // replacing TransformImplicitRule
    TransformSubtypesRule,
    TransformToSingletonRule,
    TransformOptionToOptionRule,
    TransformPartialOptionToNonOptionRule,
    TransformToOptionRule,
    TransformValueClassToValueClassRule,
    TransformValueClassToTypeRule,
    TransformTypeToValueClassRule,
    TransformEitherToEitherRule,
    TransformMapToMapRule,
    TransformIterableToIterableRule,
    TransformProductToProductRule,
    TransformSealedHierarchyToSealedHierarchyRule
  )
}
