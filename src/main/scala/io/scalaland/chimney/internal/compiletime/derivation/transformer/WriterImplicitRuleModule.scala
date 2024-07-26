package io.scalaland.chimney.internal.compiletime.derivation.transformer

import io.scalaland.chimney.dsl.{PreferPartialTransformer, PreferTotalTransformer}
import io.scalaland.chimney.internal.compiletime.DerivationResult

trait WriterImplicitRuleModule { this: Derivation =>

  protected object WriterImplicitRule extends Rule("WriterImplicit") {

    // TODO: look for writer instead

    def expand[From, To](implicit ctx: TransformationContext[From, To]): DerivationResult[Rule.ExpansionResult[To]] =
      if (ctx.config.areLocalFlagsAndOverridesEmpty) transformWithImplicitIfAvailable[From, To]
      else DerivationResult.attemptNextRuleBecause("Configuration has defined overrides")

    private def transformWithImplicitIfAvailable[From, To](implicit
                                                           ctx: TransformationContext[From, To]
                                                          ): DerivationResult[Rule.ExpansionResult[To]] = ctx match {
      case TransformationContext.ForTotal(src) =>
        summonTransformerSafe[From, To].fold(DerivationResult.attemptNextRule[To]) { totalTransformer =>
          // We're constructing:
          // '{ ${ totalTransformer }.transform(${ src }) } }
          DerivationResult.expandedTotal(totalTransformer.transform(src))
        }
      case TransformationContext.ForPartial(src, failFast) =>
        import ctx.config.flags.implicitConflictResolution
        (summonTransformerSafe[From, To], summonPartialTransformerSafe[From, To]) match {
          case (Some(total), Some(partial)) if implicitConflictResolution.isEmpty =>
            DerivationResult.ambiguousImplicitPriority(total, partial)
          case (Some(totalTransformer), partialTransformerOpt)
            if partialTransformerOpt.isEmpty || implicitConflictResolution.contains(PreferTotalTransformer) =>
            // We're constructing:
            // '{ ${ totalTransformer }.transform(${ src }) } }
            DerivationResult.expandedTotal(totalTransformer.transform(src))
          case (totalTransformerOpt, Some(partialTransformer))
            if totalTransformerOpt.isEmpty || implicitConflictResolution.contains(PreferPartialTransformer) =>
            // We're constructing:
            // '{ ${ partialTransformer }.transform(${ src }, ${ failFast }) } }
            DerivationResult.expandedPartial(partialTransformer.transform(src, failFast))
          case _ => DerivationResult.attemptNextRule
        }
    }
  }
}
