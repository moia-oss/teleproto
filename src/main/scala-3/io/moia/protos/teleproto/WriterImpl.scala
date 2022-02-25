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

import scala.collection.compat._
import scala.compiletime.error
import scala.quoted.*

object WriterImpl extends FormatImpl {
  /** Validates if business model type can be written to the Protocol Buffers type (matching case classes or matching sealed trait
    * hierarchy). If just forward compatible then raise a warning.
    */
  def writer_impl[M, P](using quotes: Quotes, modelType: Type[M], protobufType: Type[P]): Expr[Writer[M, P]] = Expr {
    if (checkClassTypes(protobufType, modelType)) {
      ensureValidTypes(protobufType, modelType)
      val (result, compatibility) = compileClassMapping(protobufType, modelType)
      warnForwardCompatible(protobufType, modelType, compatibility)
      result
    } else if (checkEnumerationTypes[P, M]) {
      val (result, compatibility) = compileEnumerationMapping(protobufType, modelType)
      warnForwardCompatible(protobufType, modelType, compatibility)
      result
    } else if (checkHierarchyTypes[P, M]) {
      val (result, compatibility) = compileTraitMapping(protobufType, modelType)
      warnForwardCompatible(protobufType, modelType, compatibility)
      result
    } else {
      error(
        s"Cannot create a writer from `$modelType` to `$protobufType`. Just mappings between a) case classes b) hierarchies + sealed traits c) sealed traits from enums are possible."
      )
    }
  }

  /** Passes a tree to `f` that is of type `Writer[$modelType, $protobufType]`.
    *
    * If such a type is not implicitly available checks if a writer can be generated, then generates and returns it. If not "asks" for it
    * implicitly and let the compiler explain the problem if it does not exist.
    *
    * If the writer is generated, that might cause a compatibility issue.
    *
    * The result is `f` applied to the writer expression with the (possible) compatibility issues of writer generation (if happened).
    */
  private def withImplicitWriter[M, P](modelType: Type[M], protobufType: Type[P])(compileInner: Tree => Tree)(using Quotes): Compiled = {
    // look for an implicit writer
    val writerType = appliedType(weakTypeTag[Writer[_, _]].tpe, modelType, protobufType)

    val existingWriter = inferImplicitValue(writerType)

    // "ask" for the implicit writer or use the found one
    def ask: Compiled = (compileInner(q"implicitly[$writerType]"), Compatibility.full)

    if (existingWriter == EmptyTree)
      if (checkClassTypes(protobufType, modelType)) {
        val (implicitValue, compatibility) = compileClassMapping(protobufType, modelType)
        val result                         = compileInner(implicitValue)
        (result, compatibility)
      } else if (checkEnumerationTypes[P, M]) {
        val (implicitValue, compatibility) = compileEnumerationMapping(protobufType, modelType)
        val result                         = compileInner(implicitValue)
        (result, compatibility)
      } else if (checkHierarchyTypes[P, M]) {
        val (implicitValue, compatibility) = compileTraitMapping(protobufType, modelType)
        val result                         = compileInner(implicitValue)
        (result, compatibility)
      } else ask // let the compiler explain the problem
    else
      ask // use the available implicit
  }

  /** Simple compilation schema for forward compatible writers:
    *
    * Iterate through the parameters of the business model case class and compile arguments for the Protocol Buffers case class:
    *   - If name is missing in protobuf, ignore (forward compatible)
    *   - If name is missing in model but has a default value, do not pass as argument to get default value (forward compatible)
    *   - If name is missing in model but is optional, pass `None` (forward compatible)
    *   - Otherwise convert using `transform`, `optional` or `present`.
    */
  private def compileClassMapping[P, M](protobufType: Type[P], modelType: Type[M])(using Quotes): Compiled = {
    import quotes.reflect.*

    // at this point all errors are assumed to be due to evolution
    val protobufCompanion = TypeTree.of[P].symbol.companion
    val protobufCons      = protobufType.member(termNames.CONSTRUCTOR).asMethod
    val modelCons         = modelType.member(termNames.CONSTRUCTOR).asMethod
    val protobufParams    = protobufCons.paramLists.headOption.getOrElse(Nil).map(_.asTerm)
    val modelParams       = modelCons.paramLists.headOption.getOrElse(Nil).map(_.asTerm)

    def transformation(parameters: Seq[MatchingParam], ownCompatibility: Compatibility): Compiled = {
      val model = c.freshName(TermName("model"))

      val namedArguments = protobufParams.zip(parameters).flatMap {
        // unmatched parameters with default values are not passed: they get their defaults
        case (_, SkippedDefaultParam) => None
        case (paramSym, TransformParam(from, to)) =>
          val param = paramSym.name
          val arg = if (from <:< to) {
            (q"$model.$param", Compatibility.full)
          } else if (to <:< weakTypeOf[Option[_]] && !(from <:< weakTypeOf[Option[_]])) {
            withImplicitWriter(from, innerType(to)) { writer =>
              '{Writer.present[$from, ${innerType(to)}]($model.$param)($writer)}
            }
          } else if (to <:< weakTypeOf[Option[_]] && from <:< weakTypeOf[Option[_]]) {
            withImplicitWriter(innerType(from), innerType(to)) { writer =>
              '{Writer.optional[${innerType(from)}, ${innerType(to)}]($model.$param)($writer)}
            }
          } else if (from <:< weakTypeOf[IterableOnce[_]] && to <:< weakTypeOf[scala.collection.immutable.Seq[_]]) {
            val innerFrom = innerType(from)
            val innerTo   = innerType(to)
            withImplicitWriter(innerFrom, innerTo) { writer =>
              // collection also needs an implicit sequence generator which must be looked up since the implicit for the value writer is passed explicitly
              val canBuildFrom = VersionSpecific.lookupFactory(c)(innerTo, to)
              '{Writer.collection[$innerFrom, $innerTo, scala.collection.immutable.Seq]($model.$param)($canBuildFrom, $writer)}
            }
          } else if (from <:< weakTypeOf[IterableOnce[_]] && to <:< weakTypeOf[Seq[_]]) {
            val innerFrom = innerType(from)
            val innerTo   = innerType(to)
            withImplicitWriter(innerFrom, innerTo) { writer =>
              '{Writer.sequence[$innerFrom, $innerTo]($model.$param)($writer)}
            }
          } else {
            withImplicitWriter(from, to) { writer =>
              '{Writer.transform[$from, $to]($model.$param)($writer)}
            }
          }

          Some(param -> arg)
      }

      val args                 = for ((name, (arg, _)) <- namedArguments) yield q"$name = $arg"
      val cons                 = q"${protobufCompanion.asTerm}.apply(..$args)"
      val innerCompatibilities = for ((_, (_, innerCompatibility)) <- namedArguments) yield innerCompatibility
      val compatibility        = innerCompatibilities.foldRight(ownCompatibility)(_.merge(_))
      val result               = '{Writer.instance[M, P] { case $model => $cons }}
      (result, compatibility)
    }

    compareCaseAccessors(modelType, protobufParams, modelParams) match {
      case Compatible(parameters) =>
        transformation(parameters, Compatibility.full)

      case ForwardCompatible(surplusParameters, defaultParameters, parameters) =>
        transformation(parameters, Compatibility(surplusParameters.map(modelType -> _), defaultParameters.map(protobufType -> _), Nil))
    }
  }

  private sealed trait Matching

  /* Same arity */
  private case class Compatible(parameters: Seq[MatchingParam]) extends Matching

  /* Missing names on Protobuf side and missing names on Model side */
  private case class ForwardCompatible(
      surplusParameters: Iterable[String],
      defaultParameters: Iterable[String],
      parameters: Seq[MatchingParam]
  ) extends Matching

  private sealed trait MatchingParam
  private case class TransformParam(from: Type, to: Type) extends MatchingParam
  private case object SkippedDefaultParam                 extends MatchingParam

  private def compareCaseAccessors[M: Type, P: Type](using Quotes): Matching = {
    import quotes.reflect.*

    val protobufByName  = TypeTree.of[P].symbol.caseFields.map(symbol => symbol.name -> symbol).toMap
    val modelType       = TypeTree.of[M]
    val modelTypeSymbol = modelType.symbol
    val modelByName     = modelTypeSymbol.caseFields.map(symbol => symbol.name -> symbol).toMap

    val surplusModelNames = modelByName.keySet diff protobufByName.keySet

    val matchingProtobufParams: List[MatchingParam] =
      for (protobufParam <- protobufByName) yield {
        modelByName.get(protobufParam._1) match {
          case Some(modelParam) =>
            // resolve type parameters to their actual bindings
            val sourceType = modelParam.signature.asSeenFrom(modelType, modelTypeSymbol)
            TransformParam(sourceType, protobufParam._2.signature)
          case None =>
            SkippedDefaultParam
        }
      }

    val namedMatchedParams = protobufByName.map(_._1).zip(matchingProtobufParams)

    val forwardCompatibleModelParamNames = namedMatchedParams.collect { case (name, SkippedDefaultParam) =>
      name
    }

    if (surplusModelNames.nonEmpty || forwardCompatibleModelParamNames.nonEmpty)
      ForwardCompatible(surplusModelNames, forwardCompatibleModelParamNames, matchingProtobufParams)
    else
      Compatible(matchingProtobufParams)
  }

  /** Iterate through the sub-types of the model and check for a corresponding method in the inner value of the protobuf type. If there are
    * more types on the protobuf side, the mapping is forward compatible. If there are more types on the model side, the mapping is not
    * possible.
    *
    * (p: model.FooOrBar) => if (p.isInstanceOf[model.Foo]) protobuf.FooOrBar(protobuf.FooOrBar.Value.Foo(transform[model.Foo,
    * protobuf.Foo](value.asInstanceOf[model.Foo]))) else protobuf.FooOrBar(protobuf.FooOrBar.Value.Bar(transform[model.Bar,
    * protobuf.Bar](value.asInstanceOf[model.Bar])))
    */
  private def compileTraitMapping[P, M](protobufType: Type[P], modelType: Type[M])(using Quotes): Compiled = {
    import quotes.reflect.*

    val protobufClass      = TypeTree.of[P].symbol
    val modelClass         = TypeTree.of[M].symbol
    val protobufSubclasses = protobufClass.children.map(symbol => symbol.name -> symbol).toMap
    val modelSubclasses    = modelClass.children.map(symbol => symbol.name -> symbol).toMap

    if (protobufSubclasses.isEmpty)
      error(s"No case subclasses of sealed trait `${protobufClass.fullName}` found.")

    val unmatchedModelClasses = modelSubclasses.keySet diff protobufSubclasses.keySet

    if (unmatchedModelClasses.nonEmpty)
      error(s"`${protobufClass.fullName}` does not match ${showNames(unmatchedModelClasses)} subclasses of `${modelClass.fullName}`.")

    val surplusProtobufClasses = protobufSubclasses.keySet - EmptyOneOf diff modelSubclasses.keySet
    val ownCompatibility       = Compatibility(Nil, Nil, surplusProtobufClasses.map(name => (protobufType, name.toString)))
    val valueMethod            = protobufType.member(ValueMethod).asMethod
    val model                  = c.freshName(TermName("model"))

    val subTypes = for {
      (className, protobufSubclass) <- protobufSubclasses
      modelSubclass                 <- modelSubclasses.get(className)
    } yield {
      withImplicitWriter(classTypeOf(modelSubclass), valueMethod.infoIn(classTypeOf(protobufSubclass))) { writer =>
        cq"$model: $modelSubclass => new $protobufSubclass($writer.write($model))"
      }
    }

    val (cases, compatibility) = subTypes.unzip
    val result                 = '{Writer.instance[M, P] { case ..$cases }}
    (result, compatibility.fold(ownCompatibility)(_ merge _))
  }

  /** The protobuf and model types have to be sealed traits. Iterate through the known subclasses of the model and match the ScalaPB side.
    *
    * If there are more options on the protobuf side, the mapping is forward compatible. If there are more options on the model side, the
    * mapping is not possible.
    *
    * (model: ModelEnum) => p match { case ModelEnum.OPTION_1 => ProtoEnum.OPTION_1 ... case ModelEnum.OPTION_N => ProtoEnum.OPTION_N }
    */
  private def compileEnumerationMapping[P, M](protobufType: Type[P], modelType: Type[M])(using Quotes): Compiled = {
    import quotes.reflect.*

    val protobufClass   = TypeTree.of[P].symbol
    val modelClass      = TypeTree.of[M].symbol
    val protobufOptions = symbolsByTolerantName(protobufClass.children.filter(_.isModuleClass), protobufClass)
    val modelOptions    = symbolsByTolerantName(modelClass.children.filter(_.isModuleClass), modelClass)

    val unmatchedModelOptions = modelOptions.toList.collect {
      case (name, symbol) if !protobufOptions.contains(name) => symbol.name
    }

    if (unmatchedModelOptions.nonEmpty)
      error(s"The options in `${protobufClass.fullName}` do not match ${showNames(unmatchedModelOptions)} in `${modelClass.fullName}`.")

    val surplusProtobufOptions = protobufOptions.toList.collect {
      case (name, symbol) if !modelOptions.contains(name) && name != InvalidEnum => symbol.name
    }

    val compatibility = Compatibility(Nil, Nil, surplusProtobufOptions.map(name => (protobufType, name.toString)))
    val cases = for {
      (optionName, protobufOption) <- protobufOptions.toList
      modelOption                  <- modelOptions.get(optionName)
    } yield cq"_: ${objectReferenceTo(modelOption)}.type => ${objectReferenceTo(protobufOption)}"

    val result = '{Writer.instance[M, P] { case ..$cases }}
    (result, compatibility)
  }

  private[teleproto] def warnForwardCompatible[P, M](protobufType: Type[P], modelType: Type[M], compatibility: Compatibility)(using
      Quotes
  ): Unit = {
    def info           = compatibilityInfo(compatibility)
    lazy val signature = compatibilitySignature(compatibility)

    compatibilityAnnotation(typeOf[forward]) match {
      case None if compatibility.hasIssues =>
        error(
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
