package io.moia.protos.teleproto

import scala.reflect.macros.blackbox

object VersionSpecific {
  def lookupFactory(c: blackbox.Context)(innerTo: c.universe.Type, to: c.universe.Type): c.universe.Tree = {
    import c.universe.*
    q"""implicitly[scala.collection.Factory[$innerTo, $to]]"""
  }
}
