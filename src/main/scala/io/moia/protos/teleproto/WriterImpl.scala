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
import scala.reflect.macros.blackbox

@SuppressWarnings(Array("all"))
class WriterImpl(val c: blackbox.Context) extends FormatImpl {
  import c.universe._

  /**
    * Validates if business model type can be written to the Protocol Buffers type
    * (matching case classes or matching sealed trait hierarchy).
    * If just forward compatible then raise a warning.
    */
  def writer_impl[M: WeakTypeTag, P: WeakTypeTag]: c.Expr[Writer[M, P]] =
    c.Expr(compile[M, P])

  private def compile[M: WeakTypeTag, P: WeakTypeTag]: Tree = {
    val modelType    = weakTypeTag[M].tpe
    val protobufType = weakTypeTag[P].tpe

    if (checkClassTypes(protobufType, modelType)) {
      ensureValidTypes(protobufType, modelType)
      val (result, compatibility) = compileClassMapping(protobufType, modelType)
      warnForwardCompatible(protobufType, modelType, compatibility)
      traceCompiled(result)
    } else if (checkEnumerationTypes(protobufType, modelType)) {
      val (result, compatibility) = compileEnumerationMapping(protobufType, modelType)
      warnForwardCompatible(protobufType, modelType, compatibility)
      traceCompiled(result)
    } else if (checkHierarchyTypes(protobufType, modelType)) {
      val (result, compatibility) = compileTraitMapping(protobufType, modelType)
      warnForwardCompatible(protobufType, modelType, compatibility)
      traceCompiled(result)
    } else {
      abort(
        s"Cannot create a writer from `$modelType` to `$protobufType`. Just mappings between a) case classes b) hierarchies + sealed traits c) sealed traits from enums are possible."
      )
    }
  }

  /**
    * Passes a tree to `f` that is of type `Writer[$modelType, $protobufType]`.
    *
    * If such a type is not implicitly available checks if a writer can be generated, then generates and returns it.
    * If not "asks" for it implicitly and let the compiler explain the problem if it does not exist.
    *
    * If the writer is generated, that might cause a compatibility issue.
    *
    * The result is `f` applied to the writer expression with the (possible) compatibility issues of writer generation
    * (if happened).
    */
  private def withImplicitWriter(modelType: Type, protobufType: Type)(compileInner: Tree => Tree): Compiled = {
    // look for an implicit writer
    val writerType = appliedType(c.weakTypeTag[Writer[_, _]].tpe, modelType, protobufType)

    val existingWriter = c.inferImplicitValue(writerType)

    // "ask" for the implicit writer or use the found one
    def ask: Compiled = (compileInner(q"implicitly[$writerType]"), Compatibility.full)

    if (existingWriter == EmptyTree)
      if (checkClassTypes(protobufType, modelType)) {
        val (implicitValue, compatibility) = compileClassMapping(protobufType, modelType)
        val result                         = compileInner(implicitValue)
        (result, compatibility)
      } else if (checkEnumerationTypes(protobufType, modelType)) {
        val (implicitValue, compatibility) = compileEnumerationMapping(protobufType, modelType)
        val result                         = compileInner(implicitValue)
        (result, compatibility)
      } else if (checkHierarchyTypes(protobufType, modelType)) {
        val (implicitValue, compatibility) = compileTraitMapping(protobufType, modelType)
        val result                         = compileInner(implicitValue)
        (result, compatibility)
      } else
        ask // let the compiler explain the problem
    else
      ask // use the available implicit
  }

  /**
    * Simple compilation schema for forward compatible writers:
    *
    * Iterate through the parameters of the business model case class and compile arguments for the Protocol Buffers
    * case class:
    * - If name is missing in protobuf, ignore (forward compatible)
    * - If name is missing in model but has a default value, do not pass as argument to get default value (forward compatible)
    * - If name is missing in model but is optional, pass `None` (forward compatible)
    * - Otherwise convert using `transform`, `optional` or `present`.
    */
  private def compileClassMapping(protobufType: Type, modelType: Type): Compiled = {
    // at this point all errors are assumed to be due to evolution
    val protobufCompanion = protobufType.typeSymbol.companion

    val protobufCons = protobufType.member(termNames.CONSTRUCTOR).asMethod
    val modelCons    = modelType.member(termNames.CONSTRUCTOR).asMethod

    val protobufParams = protobufCons.paramLists.headOption.getOrElse(Nil).map(_.asTerm)
    val modelParams    = modelCons.paramLists.headOption.getOrElse(Nil).map(_.asTerm)

    val mapping = q"io.moia.protos.teleproto"

    def transformation(parameters: Seq[MatchingParam], ownCompatibility: Compatibility): Compiled = {

      val namedArguments =
        protobufParams
          .zip(parameters)
          .flatMap(_ match {
            // unmatched parameters with default values are not passed: they get their defaults
            case (_, SkippedDefaultParam) => None
            case (param, TransformParam(from, to)) =>
              val arg =
                if (from <:< to) {
                  (q"""model.${param.name}""", Compatibility.full)
                } else if (to <:< weakTypeOf[Option[_]] && !(from <:< weakTypeOf[Option[_]])) {
                  withImplicitWriter(from, innerType(to))(
                    writerExpr => q"""$mapping.Writer.present[$from, ${innerType(to)}](model.${param.name})($writerExpr)"""
                  )
                } else if (to <:< weakTypeOf[Option[_]] && from <:< weakTypeOf[Option[_]]) {
                  withImplicitWriter(innerType(from), innerType(to))(
                    writerExpr => q"""$mapping.Writer.optional[${innerType(from)}, ${innerType(to)}](model.${param.name})($writerExpr)"""
                  )
                } else if (from <:< weakTypeOf[IterableOnce[_]] && to <:< weakTypeOf[scala.collection.immutable.Seq[_]]) {
                  val innerFrom = innerType(from)
                  val innerTo   = innerType(to)
                  withImplicitWriter(innerFrom, innerTo) { writerExpr =>
                    // collection also needs an implicit sequence generator which must be looked up since the implicit for the value writer is passed explicitly
                    val canBuildFrom = VersionSpecific.lookupFactory(c)(innerTo, to)
                    q"""$mapping.Writer.collection[$innerFrom, $innerTo, scala.collection.immutable.Seq](model.${param.name})($canBuildFrom, $writerExpr)"""
                  }
                } else if (from <:< weakTypeOf[IterableOnce[_]] && to <:< weakTypeOf[Seq[_]]) {
                  val innerFrom = innerType(from)
                  val innerTo   = innerType(to)
                  withImplicitWriter(innerFrom, innerTo)(
                    writerExpr => q"""$mapping.Writer.sequence[$innerFrom, $innerTo](model.${param.name})($writerExpr)"""
                  )
                } else {
                  withImplicitWriter(from, to)(
                    writerExpr => q"""$mapping.Writer.transform[$from, $to](model.${param.name})($writerExpr)"""
                  )
                }

              Some(param.name -> arg)
          })

      val cons =
        q"""${protobufCompanion.asTerm}.apply(..${for ((name, (arg, _)) <- namedArguments) yield q"$name = $arg"})"""

      // a type cast is needed due to type inferencer limitations
      val innerCompatibilities =
        for ((_, (_, innerCompatibility)) <- namedArguments)
          yield innerCompatibility.asInstanceOf[Compatibility]

      val compatibility = innerCompatibilities.foldRight(ownCompatibility)(_.merge(_))

      val result =
        q"""
          new $mapping.Writer[$modelType, $protobufType] {
            def write(model: $modelType) = $cons
          }"""

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

  private def compareCaseAccessors(
      modelType: Type,
      protobufParams: List[TermSymbol],
      modelParams: List[TermSymbol]
  ): Matching = {
    val protobufByName = symbolsByName(protobufParams)
    val modelByName    = symbolsByName(modelParams)

    val surplusModelNames = modelByName.keySet diff protobufByName.keySet

    val matchingProtobufParams: List[MatchingParam] =
      for (protobufParam <- protobufParams) yield {
        modelByName.get(protobufParam.name) match {
          case Some(modelParam) =>
            // resolve type parameters to their actual bindings
            val sourceType = modelParam.typeSignature.asSeenFrom(modelType, modelType.typeSymbol)
            TransformParam(sourceType, protobufParam.typeSignature)
          case None =>
            SkippedDefaultParam
        }
      }

    val namedMatchedParams = protobufParams.map(_.name).zip(matchingProtobufParams)

    val forwardCompatibleModelParamNames =
      namedMatchedParams.collect {
        case (name, SkippedDefaultParam) => name
      }

    if (surplusModelNames.nonEmpty || forwardCompatibleModelParamNames.nonEmpty) {
      ForwardCompatible(
        surplusModelNames.map(_.decodedName.toString),
        forwardCompatibleModelParamNames.map(_.decodedName.toString),
        matchingProtobufParams
      )
    } else {
      Compatible(matchingProtobufParams)
    }
  }

  /**
    * Iterate through the sub-types of the model and check for a corresponding method in the inner value of the protobuf type.
    * If there are more types on the protobuf side, the mapping is forward compatible.
    * If there are more types on the model side, the mapping is not possible.
    *
    * (p: model.FooOrBar) =>
    *   if (p.isInstanceOf[model.Foo])
    *     protobuf.FooOrBar(protobuf.FooOrBar.Value.Foo(transform[model.Foo, protobuf.Foo](value.asInstanceOf[model.Foo])))
    *   else
    *     protobuf.FooOrBar(protobuf.FooOrBar.Value.Bar(transform[model.Bar, protobuf.Bar](value.asInstanceOf[model.Bar])))
    */
  private def compileTraitMapping(protobufType: Type, modelType: Type): Compiled = {
    val mapping           = q"io.moia.protos.teleproto"
    val protobufCompanion = protobufType.typeSymbol.companion

    val protobufSubClasses = symbolsByName(protoHierarchyCaseClasses(protobufType))
    val modelSubClasses    = symbolsByName(modelType.typeSymbol.asClass.knownDirectSubclasses)

    if (protobufSubClasses.isEmpty)
      error(
        s"No case classes were found in object `Value` in `${protobufType.typeSymbol.fullName}`. Your protofile is most likely not as it should!"
      )

    val unmatchedModelClasses = modelSubClasses.keySet diff protobufSubClasses.keySet

    if (unmatchedModelClasses.nonEmpty) {
      error(
        s"Object `Value` in `${protobufType.typeSymbol.fullName}` does not match ${unmatchedModelClasses.map(c => s"`$c`").mkString(", ")} in `${modelType.typeSymbol.fullName}`."
      )
    }

    val surplusProtobufClasses = protobufSubClasses.keySet diff modelSubClasses.keySet
    val ownCompatibility       = Compatibility(Nil, Nil, surplusProtobufClasses.map(name => (protobufType, name.toString)))

    val checkAndTransforms: Iterable[(Tree, Tree, Compatibility)] =
      for {
        (className, protobufClass) <- protobufSubClasses
        modelClass                 <- modelSubClasses.get(className)
      } yield {

        val classNameDecoded = className.decodedName.toString
        val methodName       = TermName(classNameDecoded.take(1).toLowerCase + classNameDecoded.drop(1))

        val method = protobufClass.typeSignature.decl(methodName)

        val protobufWrappedType = innerType(method.asMethod.returnType)

        val specificModelType = modelClass.asClass.selfType

        val checkSpecific = q"model.isInstanceOf[$specificModelType]"

        val (transformSpecific, compatibility) =
          withImplicitWriter(specificModelType, protobufWrappedType)(
            writerExpr =>
              q"""$mapping.Writer.transform[$specificModelType, $protobufWrappedType](model.asInstanceOf[$specificModelType])($writerExpr)"""
          )

        val transformWrapped =
          q"""${protobufCompanion.asTerm}(${protobufCompanion.asTerm}.Value.${TermName(protobufClass.asClass.name.decodedName.toString)}($transformSpecific))"""

        (checkSpecific, transformWrapped, compatibility)
      }

    def ifElses(checkAndTransforms: List[(Tree, Tree, Compatibility)]): Compiled =
      checkAndTransforms match {
        // last entry: cast has to match (in `else` or on top-level)
        case List((_, transform, classCompatibility)) =>
          (transform, classCompatibility)

        case (check, transform, classCompatibility) :: rest =>
          val (restTransformed, restCompatibility) = ifElses(rest)
          (q"""if($check) $transform else $restTransformed""", classCompatibility.merge(restCompatibility))

        case Nil =>
          (q"""throw new IllegalStateException("teleproto contains a software bug compiling trait writers!")""", Compatibility.full)
      }

    val (transformed, innerCompatibility) = ifElses(checkAndTransforms.toList)

    val compatibility = ownCompatibility.merge(innerCompatibility)

    val result = q"""
          new $mapping.Writer[$modelType, $protobufType] {
            def write(model: $modelType) = $transformed
          }"""

    (result, compatibility)
  }

  /**
    * The protobuf and model types have to be sealed traits.
    * Iterate through the known subclasses of the model and match the ScalaPB side.
    *
    * If there are more options on the protobuf side, the mapping is forward compatible.
    * If there are more options on the model side, the mapping is not possible.
    *
    * (model: ModelEnum) => p match {
    *   case ModelEnum.OPTION_1 => ProtoEnum.OPTION_1
    *   ...
    *   case ModelEnum.OPTION_N => ProtoEnum.OPTION_N
    * }
    */
  private def compileEnumerationMapping(protobufType: Type, modelType: Type): Compiled = {
    val mapping = q"io.moia.protos.teleproto"

    val protobufOptions = symbolsByTolerantName(protobufType.typeSymbol.asClass.knownDirectSubclasses.filter(_.isModuleClass))
    val modelOptions    = symbolsByTolerantName(modelType.typeSymbol.asClass.knownDirectSubclasses.filter(_.isModuleClass))

    val unmatchedModelOptions = modelOptions.toList.collect { case (name, v) if !protobufOptions.contains(name) => v.name.decodedName }

    if (unmatchedModelOptions.nonEmpty) {
      error(
        s"The options in `${protobufType.typeSymbol.fullName}` do not match ${unmatchedModelOptions.map(c => s"`$c`").mkString(", ")} in `${modelType.typeSymbol.fullName}`."
      )
    }

    val surplusProtobufOptions = protobufOptions.toList.collect { case (name, v) if !modelOptions.contains(name) => v.name.decodedName }
    val compatibility          = Compatibility(Nil, Nil, surplusProtobufOptions.map(name => (protobufType, name.toString)))

    val cases =
      for {
        (optionName, protobufOption) <- protobufOptions.toList
        modelOption                  <- modelOptions.get(optionName)
      } yield {
        (modelOption.asClass.selfType.termSymbol, protobufOption.asClass.selfType.termSymbol) // expected value to right hand side value
      }

    // construct a de-sugared pattern matching as a cascade of if elses
    def ifElses(cs: List[(Symbol, Symbol)]): Tree =
      cs match {
        case (expected, rhs) :: rest =>
          q"""if(model == $expected) $rhs else ${ifElses(rest)}"""

        case Nil =>
          q"""throw new IllegalStateException("teleproto contains a software bug compiling enum writers: " + model + " is not a matched value.")"""
      }

    val result = q"""
          new $mapping.Writer[$modelType, $protobufType] {
            def write(model: $modelType) = ${ifElses(cases)}
          }"""

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
