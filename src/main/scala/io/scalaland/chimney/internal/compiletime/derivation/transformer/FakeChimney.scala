package io.scalaland.chimney.internal.compiletime.derivation.transformer

//import io.scalaland.chimney.dsl
import io.scalaland.chimney.Transformer
import io.scalaland.chimney.internal.compiletime.DerivationResult
import io.scalaland.chimney.internal.runtime

import scala.reflect.macros.blackbox
//import scala.reflect.runtime.universe.Quasiquote

// TODO: remove
final class FakeChimney(val c: blackbox.Context) extends DerivationPlatform with Gateway {
//  import c.universe._

  import io.moia.protos.teleproto.BaseTransformers._

  import c.universe.{internal => _, Transformer => _, _}

  def deriveWriter[M: WeakTypeTag, P: WeakTypeTag] = {
    println(s"Running FakeChimney.deriveWriter ${weakTypeTag[M].tpe} -> ${weakTypeTag[P].tpe}")
//    deriveTotalTransformer[
//    M,
//    P,
//    runtime.TransformerOverrides.Empty,
//    runtime.TransformerFlags.Enable[runtime.TransformerFlags.DefaultValues, runtime.TransformerFlags.Default],
//    runtime.TransformerFlags.Enable[runtime.TransformerFlags.DefaultValues, runtime.TransformerFlags.Default]
//  ](
//    // Called by TransformerDefinition => prefix is TransformerDefinition
//    ChimneyExpr.RuntimeDataStore.empty
//  )

    val runtimeDataStore = ChimneyExpr.RuntimeDataStore.empty
    val expr = cacheDefinition(runtimeDataStore) { runtimeDataStore =>
      val result = DerivationResult.direct[Expr[P], Expr[Transformer[M, P]]] { await =>
        ChimneyExpr.Transformer.instance[M, P] { (src: Expr[M]) =>
          val context = TransformationContext.ForTotal
            .create[M, P](
              src,
              TransformerConfiguration(flags = TransformerFlags(processDefaultValues = true))
            )

          await(enableLoggingIfFlagEnabled(deriveFinalTransformationResultExpr(context), context))
        }
      }

      extractExprAndLog[M, P, Transformer[M, P]](result)
    }

    expr
  }

  /** Adapts TransformationExpr[To] to expected type of transformation */
  override def deriveFinalTransformationResultExpr[From, To](implicit
      ctx: TransformationContext[From, To]
  ): DerivationResult[Expr[ctx.Target]] =
    DerivationResult.log(s"Start derivation with context: $ctx") >>
      deriveTransformationResultExpr[From, To]
        .map { transformationExpr =>
          ctx.fold(_ => transformationExpr.ensureTotal.asInstanceOf[Expr[ctx.Target]])(_ =>
            transformationExpr.ensurePartial.asInstanceOf[Expr[ctx.Target]]
          )
        }

  private def enableLoggingIfFlagEnabled[A](
      result: => DerivationResult[A],
      ctx: TransformationContext[?, ?]
  ): DerivationResult[A] =
    enableLoggingIfFlagEnabled[A](
      DerivationResult.catchFatalErrors(result),
      ctx.config.flags.displayMacrosLogging,
      ctx.derivationStartedAt
    )

  private def extractExprAndLog[From: Type, To: Type, Out: Type](result: DerivationResult[Expr[Out]]): Expr[Out] =
    extractExprAndLog[Out](
      result,
      s"""Chimney can't derive transformation from ${Type.prettyPrint[From]} to ${Type.prettyPrint[To]}"""
    )

}
