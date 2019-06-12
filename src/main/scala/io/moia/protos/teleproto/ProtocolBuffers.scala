package io.moia.protos.teleproto

import scala.concurrent.Future
import scala.language.experimental.macros

@SuppressWarnings(Array("all"))
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
      case PbFailure(errors)  => Future.failed(new Exception(errors.mkString("\n")))
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
    * Compiles a generic writer instance from business model type `M` to Protocol Buffers type `P` if possible.
    * See User's Guide for details.
    */
  def writer[M, P]: Writer[M, P] = macro WriterImpl.writer_impl[M, P]

  /**
    * Constructs a migration from Protocol Buffer class `P` to PB class `Q`.
    * The migration tries to copy/convert fields from a `P` to a new `Q` automatically.
    *
    * That is possible for matching names if value types `VP` and `VQ`
    * - are equal or `VQ` is wider than `VP` (copied)
    * - `VQ` is `Option[VP]` (wrapped with `Some(...)`)
    * - there is an implicit view from `VP` to `VQ` (wrapped with the conversion)
    * - there is an implicit `Migration[VP, VQ]` (wrapped with the migration)
    * - `VP` and `VQ` are nested Protocol Buffers and a trivial migration can be generated (not yet implemented!)
    *
    * If all values of `Q` can be automatically filled by values from `P` the migration is considered trivial.
    *
    * A non-trivial migration requires a migration function for each field in `Q` that cannot be filled from `P`.
    *
    * To use it, just write `migration[P, Q]()`, compile and let the compiler explain the required migration functions.
    */
  def migration[P, Q](args: (P => Any)*): Migration[P, Q] =
    macro MigrationImpl.migration_impl[P, Q]
}
