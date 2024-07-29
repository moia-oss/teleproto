package io.moia.protos.teleproto.internal

import io.moia.protos.teleproto.Writer

import scala.reflect.macros.blackbox

// Scala 2 macro bundle
class WriterMacros(val c: blackbox.Context) extends WriterDerivationPlatform {

  // Scala 2 is kinda unaware during macro expansion that myTypeClassDerivation takes c.WeakTypeTag, and we need to
  // point it out for it, explicitly
  def derivingImpl[From: c.WeakTypeTag, To: c.WeakTypeTag]: c.Expr[Writer[From, To]] =
    writerDerivation[From, To]
}