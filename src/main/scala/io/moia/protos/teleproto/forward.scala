package io.moia.protos.teleproto

/**
  * Signals a macro for Protocol Buffers readers to not raise warnings about a forward compatible writer.
  * The signature is used to validate whether the annotation was placed based on the same assumption
  * (ignored fields, used default values etc.).
  * If the model (or even the Protocol Buffers definition) somehow change an error will be raised so that the change in
  * behavior must be verified.
  */
@SuppressWarnings(Array("UnusedMethodParameter", "ClassNames"))
class forward(signature: String) extends scala.annotation.Annotation {}
