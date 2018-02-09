package io.moia.pricing.mapping.proto

import scala.concurrent.Future
import scala.language.experimental.macros

object ProtocolBuffers {

  implicit class WritableModel[M](model: M) {

    def toProto[P](implicit writer: Writer[M, P]): P =
      writer.write(model)
  }

  implicit class ReadableProtocolBuffer[P](pb: P) {

    def toModel[M](implicit reader: Reader[P, M]): PbResult[M] =
      reader.read(pb)
  }

  implicit class Converter[P](pbResult: PbResult[P]) {

    def toFuture: Future[P] = pbResult match {
      case PbSuccess(success) => Future.successful(success)
      case PbFailure(errors) =>
        Future.failed(new Exception(errors.mkString("\n")))
    }
  }

  /**
    * Compiles a generic reader instance from Protocol Buffers type `P` to business model type `M` if possible.
    * See User's Guide for details.
    *
    * Example:
    *
    * {{{ProtocolBuffers.reader[v1.PriceTrips.PriceTrip, PriceTrip]}}}
    */
  def reader[P, M]: Reader[P, M] = macro ReaderImpl.reader_impl[P, M]

  /**
    * Similar to `reader` but does not warn about a just backward compatible reader.
    * Backward compatible readers ignore some fields in the Protocol Buffers type that do not exist in the business
    * model type or pass default values.
    */
  def backwardReader[P, M]: Reader[P, M] =
    macro ReaderImpl.backwardReader_impl[P, M]

  /**
    * Compiles a generic writer instance from business model type `M` to Protocol Buffers type `P` if possible.
    * See User's Guide for details.
    */
  def writer[M, P]: Writer[M, P] = macro WriterImpl.writer_impl[M, P]

  /**
    * Similar to `writer` but does not warn about a just forward compatible writers.
    * Backward compatible writers ignore some fields in the business model type that do not exist in the Protocol
    * Buffers type or pass default values.
    */
  def forwardWriter[M, P]: Writer[M, P] =
    macro WriterImpl.forwardWriter_impl[M, P]
}
