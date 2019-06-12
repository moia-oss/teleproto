package io.moia.protos.teleproto

import scala.annotation.implicitNotFound

/**
  * Allows bijection from model to proto.
  *
  * @tparam M the model type
  * @tparam P the proto type
  */
@implicitNotFound("No Protocol Buffers mapper from type ${M} to ${P} was found. Try to implement an implicit Format for this type.")
trait Format[M, P] extends Reader[P, M] with Writer[M, P]

object Format {

  /**
    * Create a format by passing in functions for reading and writing.
    */
  def apply[M, P](reader: P => PbResult[M], writer: M => P): Format[M, P] =
    new Format[M, P] {
      override def write(model: M): P             = writer(model)
      override def read(protobuf: P): PbResult[M] = reader(protobuf)
    }

  /**
    * A format can be combined by using an existing reader and writer.
    * If both are available in implicit scope, the format can be created implicitly.
    */
  implicit def fromReaderWriter[M, P](implicit reader: Reader[P, M], writer: Writer[M, P]): Format[M, P] =
    new Format[M, P] {
      override def write(model: M): P             = writer.write(model)
      override def read(protobuf: P): PbResult[M] = reader.read(protobuf)
    }
}
