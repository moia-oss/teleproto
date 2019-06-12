package io.moia.protos.teleproto

import scalapb.json4s.Printer
import scalapb.{GeneratedMessage, GeneratedMessageCompanion, Message}

import scala.collection.immutable.ListMap
import scala.util.{Failure, Success, Try}

/**
  * The versioned model writer is a lookup table for the specific version models (ScalaPB classes) for some particular
  * detached model.
  * The lookup table is a mapping from a (generic) version to a the model in that version.
  * Those are combined with writer from that detached model to expected version models.
  *
  * The interface allows to write protocol buffers bytes / JSON directly from the detached model type for a given
  * version that is in the lookup table.
  *
  * The generic version type should be independent of the writing as JSON or bytes.
  * That is, a Mime Type is not a good version type.
  *
  * Usage Example:
  *
  * {{{
  * implicit val someModelWriter: ModelWriter[SomeDetachedModel] =
  *    new ModelWriter[MyVersion, SomeDetachedModel] {
  *      val writerMappings: WriterMappings =
  *        ListMap(
  *          VN -> writerMapping(vN.SomeApiModel),
  *          ...
  *          V2 -> writerMapping(v2.SomeApiModel),
  *          V1 -> writerMapping(v1.SomeApiModel)
  *        )
  *    }
  * }}}
  */
trait VersionedModelWriter[Version, DetachedModel] {

  /**
    * Write the detached model as JSON string.
    *
    * @return the model written as JSON
    */
  def toJson(model: DetachedModel, version: Version, printer: Printer = VersionedModelWriter.printer): Try[String] =
    toMessage(model, version).map(printer.print)

  /**
    * Write the detached model as Scala PB message.
    *
    * @return the written message
    */
  def toMessage(model: DetachedModel, version: Version): Try[GeneratedMessage with Message[_]] =
    lookupOrFail(version).map(_.write(model))

  /**
    * Write the detached model as Protocol Buffers byte array.
    *
    * @return the written PB
    */
  def toByteArray(model: DetachedModel, version: Version): Try[Array[Byte]] =
    toMessage(model, version).map(_.toByteArray)

  /**
    * Exposes the supported versions.
    *
    * @return the supported versions
    */
  def supportedWriterVersions: Set[Version] = writerMappings.keySet

  /**
    * Looks up the version writer for a specific version.
    *
    * Implementation can override the matching strategy for looked up and provided versions.
    *
    * E.g. a matching check could be performed just on a major version if the `Version` type contains major and minor.
    *
    * @param version the version that should be read
    * @return the matching reader or none
    */
  def lookupWriter(version: Version): Option[VersionWriter] =
    writerMappings.get(version)

  protected type WriterMappings = ListMap[Version, VersionWriter]

  /**
    * Maps the supported versions to a writer in the particular version.
    */
  def writerMappings: WriterMappings

  /**
    * For a companion of a specific ScalaPB class looks up the corresponding writer from the detached model.
    */
  protected def writerMapping[SpecificModel <: GeneratedMessage with Message[SpecificModel]](
      companion: GeneratedMessageCompanion[SpecificModel]
  )(implicit writer: Writer[DetachedModel, SpecificModel]): VersionWriter =
    (model: DetachedModel) => writer.write(model)

  /**
    * Models a writer from the detached business model to a Scala PB instance.
    */
  type VersionWriter = Writer[DetachedModel, GeneratedMessage with Message[_]]

  private def lookupOrFail(version: Version): Try[VersionWriter] =
    lookupWriter(version).map(Success(_)).getOrElse(Failure(new VersionNotSupportedException(version, supportedWriterVersions)))
}

object VersionedModelWriter {

  private val printer = new Printer().includingDefaultValueFields.formattingLongAsNumber
}
