/*
 * Copyright 2019 MOIA GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.moia.protos.teleproto

import io.scalaland.chimney.Transformer
import scala.reflect.macros.blackbox

@SuppressWarnings(Array("all"))
class WriterImpl(val c: blackbox.Context) extends FormatImpl {
  import c.universe._

  private[this] val writerObj      = objectRef[Writer.type]
  private[this] val transformerObj = objectRef[Transformer.type]

  /** Validates if business model type can be written to the Protocol Buffers type (matching case classes or matching sealed trait
    * hierarchy). If just forward compatible then raise a warning.
    */
  def writer_impl[M: WeakTypeTag, P: WeakTypeTag]: c.Expr[Writer[M, P]] =
    c.Expr(compile[M, P])

  private def compile[M: WeakTypeTag, P: WeakTypeTag]: Tree = {
    val modelType    = weakTypeTag[M].tpe
    val protobufType = weakTypeTag[P].tpe

    if (checkEnumerationTypes(protobufType, modelType)) {
      val (result, compatibility) = compileEnumerationMapping(protobufType, modelType)
      warnForwardCompatible(protobufType, modelType, compatibility)
      traceCompiled(result)
    } else {
      def askTransformer =
        q"import io.moia.protos.teleproto.Writer._; $transformerObj.define[$modelType, $protobufType].enableDefaultValues.buildTransformer"

      def writerFromTransformer: Tree =
        (q"$writerObj.fromTransformer[$modelType, $protobufType]($askTransformer)")

      writerFromTransformer // use the available transformer implicit
    }
  }

  /** The protobuf and model types have to be sealed traits. Iterate through the known subclasses of the model and match the ScalaPB side.
    *
    * If there are more options on the protobuf side, the mapping is forward compatible. If there are more options on the model side, the
    * mapping is not possible.
    *
    * {{{
    * (model: ModelEnum) => p match {
    *   case ModelEnum.OPTION_1 => ProtoEnum.OPTION_1
    *   ...
    *   case ModelEnum.OPTION_N => ProtoEnum.OPTION_N
    * }
    * }}}
    */
  private def compileEnumerationMapping(protobufType: Type, modelType: Type): Compiled = {
    val protobufClass   = protobufType.typeSymbol.asClass
    val modelClass      = modelType.typeSymbol.asClass
    val protobufOptions = symbolsByTolerantName(protobufClass.knownDirectSubclasses.filter(_.isModuleClass), protobufClass)
    val modelOptions    = symbolsByTolerantName(modelClass.knownDirectSubclasses.filter(_.isModuleClass), modelClass)

    val unmatchedModelOptions = modelOptions.toList.collect {
      case (name, symbol) if !protobufOptions.contains(name) => symbol.name.decodedName
    }

    if (unmatchedModelOptions.nonEmpty)
      error(s"The options in `${protobufClass.fullName}` do not match ${showNames(unmatchedModelOptions)} in `${modelClass.fullName}`.")

    val surplusProtobufOptions = protobufOptions.toList.collect {
      case (name, symbol) if !modelOptions.contains(name) && name != InvalidEnum => symbol.name.decodedName
    }

    val compatibility = Compatibility(Nil, Nil, surplusProtobufOptions.map(name => (protobufType, name.toString)))
    val cases = for {
      (optionName, protobufOption) <- protobufOptions.toList
      modelOption                  <- modelOptions.get(optionName)
    } yield cq"_: ${objectReferenceTo(modelOption)}.type => ${objectReferenceTo(protobufOption)}"

    val result = q"$writerObj.instance[$modelType, $protobufType] { case ..$cases }"
    (result, compatibility)
  }

  private[teleproto] def warnForwardCompatible(protobufType: Type, modelType: Type, compatibility: Compatibility): Unit = {
    def info           = compatibilityInfo(compatibility)
    lazy val signature = compatibilitySignature(compatibility)

    compatibilityAnnotation(typeOf[forward]) match {
      case None if compatibility.hasIssues =>
        warn(
          s"""`$modelType` is just forward compatible to `$protobufType`:\n$info\nAnnotate `@forward("$signature")` to remove this warning!"""
        )

      case Some(_) if !compatibility.hasIssues =>
        error(
          s"""`$modelType` is compatible to `$protobufType`.\nAnnotation `@forward` should be removed!"""
        )

      case Some(actual) if actual != signature =>
        error(
          s"""Forward compatibility of `$modelType` to `$protobufType` changed!\n$info\nValidate the compatibility and annotate `@forward("$signature")` to remove this error!"""
        )

      case _ =>
      // everything as expected
    }
  }
}
