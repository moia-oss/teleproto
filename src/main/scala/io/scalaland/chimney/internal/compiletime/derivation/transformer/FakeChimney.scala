package io.scalaland.chimney.internal.compiletime.derivation.transformer

//import io.scalaland.chimney.dsl
import io.scalaland.chimney.internal.runtime

import scala.reflect.macros.blackbox
//import scala.reflect.runtime.universe.Quasiquote

final class FakeChimney(val c: blackbox.Context) extends DerivationPlatform with Gateway {
  import c.universe._

//  import c.universe.{internal as _, Transformer as _, *}

  def deriveWriter[M: WeakTypeTag, P: WeakTypeTag] = {
    println(s"Running FakeChimney.deriveWriter ${weakTypeTag[M].tpe} -> ${weakTypeTag[P].tpe}")
    deriveTotalTransformer[
    M,
    P,
    runtime.TransformerOverrides.Empty,
    runtime.TransformerFlags.Enable[runtime.TransformerFlags.DefaultValues, runtime.TransformerFlags.Default],
    runtime.TransformerFlags.Enable[runtime.TransformerFlags.DefaultValues, runtime.TransformerFlags.Default]
  ](
    // Called by TransformerDefinition => prefix is TransformerDefinition
    ChimneyExpr.RuntimeDataStore.empty
//    c.Expr[dsl.TransformerDefinitionCommons.RuntimeDataStore](q"${c.prefix.tree}.runtimeData")
  )
  }

}
