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

import scala.reflect.macros.blackbox

@SuppressWarnings(Array("all"))
object WriterImpl {

  /**
    * Validates if business model type can be written to the Protocol Buffers type
    * (matching case classes or matching sealed trait hierarchy).
    * If just forward compatible then raise a warning.
    */
  def writer_impl[M: c.WeakTypeTag, P: c.WeakTypeTag](c: blackbox.Context): c.Expr[Writer[M, P]] =
    compile[M, P](c)

  import FormatImpl._

  private def compile[M: c.WeakTypeTag, P: c.WeakTypeTag](c: blackbox.Context): c.Expr[Writer[M, P]] = {

    import c.universe._

    val modelType    = weakTypeTag[M].tpe
    val protobufType = weakTypeTag[P].tpe

    if (checkClassTypes(c)(protobufType, modelType)) {
      ensureValidTypes(c)(protobufType, modelType)
      val (result, compatibility) = compileClassMapping(c)(protobufType, modelType)
      warnForwardCompatible(c)(protobufType, modelType, compatibility)
      c.Expr[Writer[M, P]](traceCompiled(c)(result))
    } else if (checkEnumerationTypes(c)(protobufType, modelType)) {
      val (result, compatibility) = compileEnumerationMapping(c)(protobufType, modelType)
      warnForwardCompatible(c)(protobufType, modelType, compatibility)
      c.Expr[Writer[M, P]](traceCompiled(c)(result))
    } else if (checkHierarchyTypes(c)(protobufType, modelType)) {
      val (result, compatibility) = compileTraitMapping(c)(protobufType, modelType)
      warnForwardCompatible(c)(protobufType, modelType, compatibility)
      c.Expr[Writer[M, P]](traceCompiled(c)(result))
    } else {
      c.abort(
        c.enclosingPosition,
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
  private def withImplicitWriter(c: blackbox.Context)(modelType: c.universe.Type, protobufType: c.universe.Type)(
      compileInner: c.universe.Tree => c.universe.Tree
  ): Compiled[c.universe.Type, c.universe.Tree] = {
    import FormatImpl._
    import c.universe._

    val mapping = q"io.moia.protos.teleproto"

    // look for an implicit writer
    val writerType = appliedType(c.weakTypeTag[Writer[_, _]].tpe, modelType, protobufType)

    val existingWriter = c.inferImplicitValue(writerType)

    // "ask" for the implicit writer or use the found one
    def ask: Compiled[c.universe.Type, c.universe.Tree] =
      (compileInner(q"implicitly[$writerType]"), Compatibility.full)

    if (existingWriter == EmptyTree)
      if (checkClassTypes(c)(protobufType, modelType)) {
        val (implicitValue, compatibility) = compileClassMapping(c)(protobufType, modelType)
        val result                         = compileInner(implicitValue)
        (result, compatibility)
      } else if (checkEnumerationTypes(c)(protobufType, modelType)) {
        val (implicitValue, compatibility) = compileEnumerationMapping(c)(protobufType, modelType)
        val result                         = compileInner(implicitValue)
        (result, compatibility)
      } else if (checkHierarchyTypes(c)(protobufType, modelType)) {
        val (implicitValue, compatibility) = compileTraitMapping(c)(protobufType, modelType)
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
  private def compileClassMapping(
      c: blackbox.Context
  )(protobufType: c.universe.Type, modelType: c.universe.Type): Compiled[c.universe.Type, c.universe.Tree] = {
    import FormatImpl._
    import c.universe._

    // at this point all errors are assumed to be due to evolution

    val protobufCompanion = protobufType.typeSymbol.companion

    val protobufCons = protobufType.member(termNames.CONSTRUCTOR).asMethod
    val modelCons    = modelType.member(termNames.CONSTRUCTOR).asMethod

    val protobufParams = protobufCons.paramLists.headOption.getOrElse(sys.error("Scapegoat...")).map(_.asTerm)
    val modelParams    = modelCons.paramLists.headOption.getOrElse(sys.error("Scapegoat...")).map(_.asTerm)

    val mapping = q"io.moia.protos.teleproto"

    val genericWriterType = c.typeOf[Writer[_, _]]

    def transformation(parameters: Seq[MatchingParam[Type, Tree]], ownCompatibility: Compatibility[Type]): Compiled[Type, Tree] = {

      val namedArguments =
        protobufParams
          .zip(parameters)
          .flatMap(_ match {
            // unmatched parameters with default values are not passed: they get their defaults
            case (_, SkippedDefaultParam(_)) => None
            case (param, TransformParam(from, to)) =>
              val arg =
                if (from <:< to) {
                  (q"""model.${param.name}""", Compatibility.full)
                } else if (to <:< weakTypeOf[Option[_]] && !(from <:< weakTypeOf[Option[_]])) {
                  withImplicitWriter(c)(from, innerType(c)(to))(
                    writerExpr => q"""$mapping.Writer.present[$from, ${innerType(c)(to)}](model.${param.name})($writerExpr)"""
                  )
                } else if (to <:< weakTypeOf[Option[_]] && from <:< weakTypeOf[Option[_]]) {
                  withImplicitWriter(c)(innerType(c)(from), innerType(c)(to))(
                    writerExpr =>
                      q"""$mapping.Writer.optional[${innerType(c)(from)}, ${innerType(c)(to)}](model.${param.name})($writerExpr)"""
                  )
                } else if (from <:< weakTypeOf[TraversableOnce[_]] && to <:< weakTypeOf[scala.collection.immutable.Seq[_]]) {
                  val innerFrom = innerType(c)(from)
                  val innerTo   = innerType(c)(to)
                  withImplicitWriter(c)(innerFrom, innerTo) { writerExpr =>
                    // collection also needs an implicit sequence generator which must be looked up since the implicit for the value writer is passed explicitly
                    val canBuildFrom =
                      q"""implicitly[scala.collection.generic.CanBuildFrom[scala.collection.immutable.Seq[_], $innerTo, $to]]"""
                    q"""$mapping.Writer.collection[$innerFrom, $innerTo, scala.collection.immutable.Seq](model.${param.name})($canBuildFrom, $writerExpr)"""
                  }
                } else if (from <:< weakTypeOf[TraversableOnce[_]] && to <:< weakTypeOf[Seq[_]]) {
                  val innerFrom = innerType(c)(from)
                  val innerTo   = innerType(c)(to)
                  withImplicitWriter(c)(innerFrom, innerTo)(
                    writerExpr => q"""$mapping.Writer.sequence[$innerFrom, $innerTo](model.${param.name})($writerExpr)"""
                  )
                } else {
                  withImplicitWriter(c)(from, to)(
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
          yield innerCompatibility.asInstanceOf[Compatibility[c.universe.Type]]

      val compatibility = innerCompatibilities.foldRight(ownCompatibility)(_.merge(_))

      val result =
        q"""
          new $mapping.Writer[$modelType, $protobufType] {
            def write(model: $modelType) = $cons
          }"""

      (result, compatibility)
    }

    compareCaseAccessors(c)(modelType, protobufParams, modelParams) match {
      case Compatible(parameters) =>
        transformation(parameters, Compatibility.full)

      case ForwardCompatible(surplusParameters, defaultParameters, parameters) =>
        transformation(parameters, Compatibility(surplusParameters.map(modelType -> _), defaultParameters.map(protobufType -> _), Nil))
    }
  }

  private sealed trait Matching[+TYPE, +TREE]

  /* Same arity */
  private final case class Compatible[TYPE, TREE](parameters: Seq[MatchingParam[TYPE, TREE]]) extends Matching[TYPE, TREE]

  /* Missing names on Protobuf side and missing names on Model side */
  private final case class ForwardCompatible[TYPE, TREE](surplusParameters: Iterable[String],
                                                         defaultParameters: Iterable[String],
                                                         parameters: Seq[MatchingParam[TYPE, TREE]])
      extends Matching[TYPE, TREE]

  private sealed trait MatchingParam[TYPE, TREE]

  private final case class TransformParam[TYPE, TREE](from: TYPE, to: TYPE) extends MatchingParam[TYPE, TREE]

  private final case class SkippedDefaultParam[TYPE, TREE](unit: Unit) extends MatchingParam[TYPE, TREE]

  private def compareCaseAccessors(c: blackbox.Context)(
      modelType: c.universe.Type,
      protobufParams: List[c.universe.TermSymbol],
      modelParams: List[c.universe.TermSymbol]
  ): Matching[c.universe.Type, c.universe.Tree] = {

    import c.universe._

    val protobufByName = FormatImpl.symbolsByName(c)(protobufParams)
    val modelByName    = FormatImpl.symbolsByName(c)(modelParams)

    val surplusModelNames = modelByName.keySet -- protobufByName.keySet

    val matchingProtobufParams: List[MatchingParam[Type, Tree]] =
      for ((protobufParam, index) <- protobufParams.zipWithIndex; number = index + 1) yield {
        modelByName.get(protobufParam.name) match {
          case Some(modelParam) =>
            // resolve type parameters to their actual bindings
            val sourceType = modelParam.typeSignature.asSeenFrom(modelType, modelType.typeSymbol)

            TransformParam[Type, Tree](sourceType, protobufParam.typeSignature)

          case None =>
            SkippedDefaultParam[Type, Tree](Unit)
        }
      }

    val namedMatchedParams = protobufParams.map(_.name).zip(matchingProtobufParams)

    val forwardCompatibleModelParamNames =
      namedMatchedParams.collect {
        case (name, SkippedDefaultParam(_)) => name
      }

    if (surplusModelNames.nonEmpty || forwardCompatibleModelParamNames.nonEmpty)
      ForwardCompatible[Type, Tree](surplusModelNames.map(_.decodedName.toString),
                                    forwardCompatibleModelParamNames.map(_.decodedName.toString),
                                    matchingProtobufParams)
    else
      Compatible(matchingProtobufParams)
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
  private def compileTraitMapping(
      c: blackbox.Context
  )(protobufType: c.universe.Type, modelType: c.universe.Type): Compiled[c.universe.Type, c.universe.Tree] = {
    import FormatImpl._
    import c.universe._

    val mapping = q"io.moia.protos.teleproto"

    val protobufCompanion = protobufType.typeSymbol.companion

    val protobufSubClasses = symbolsByName(c)(protoHierarchyCaseClasses(c)(protobufType))
    val modelSubClasses    = symbolsByName(c)(modelType.typeSymbol.asClass.knownDirectSubclasses)

    if (protobufSubClasses.isEmpty)
      c.error(
        c.enclosingPosition,
        s"No case classes were found in object `Value` in `${protobufType.typeSymbol.fullName}`. Your protofile is most likely not as it should!"
      )

    val unmatchedModelClasses = modelSubClasses.keySet -- protobufSubClasses.keySet

    if (unmatchedModelClasses.nonEmpty) {
      c.error(
        c.enclosingPosition,
        s"Object `Value` in `${protobufType.typeSymbol.fullName}` does not match ${unmatchedModelClasses.map(c => s"`$c`").mkString(", ")} in `${modelType.typeSymbol.fullName}`."
      )
    }

    val surplusProtobufClasses = protobufSubClasses.keySet -- modelSubClasses.keySet
    val ownCompatibility       = Compatibility(Nil, Nil, surplusProtobufClasses.map(name => (protobufType, name.toString)))

    val checkAndTransforms: Iterable[(Tree, Tree, Compatibility[Type])] =
      for {
        (className, protobufClass) <- protobufSubClasses
        modelClass                 <- modelSubClasses.get(className)
      } yield {

        val classNameDecoded = className.decodedName.toString
        val methodName       = TermName(classNameDecoded.take(1).toLowerCase + classNameDecoded.drop(1))

        val method = protobufClass.typeSignature.decl(methodName)

        val protobufWrappedType = innerType(c)(method.asMethod.returnType)

        val specificModelType = modelClass.asClass.selfType

        val checkSpecific = q"model.isInstanceOf[$specificModelType]"

        val (transformSpecific, compatibility) =
          withImplicitWriter(c)(specificModelType, protobufWrappedType)(
            writerExpr =>
              q"""$mapping.Writer.transform[$specificModelType, $protobufWrappedType](model.asInstanceOf[$specificModelType])($writerExpr)"""
          )

        val transformWrapped =
          q"""${protobufCompanion.asTerm}(${protobufCompanion.asTerm}.Value.${TermName(protobufClass.asClass.name.decodedName.toString)}($transformSpecific))"""

        (checkSpecific, transformWrapped, compatibility)
      }

    def ifElses(checkAndTransforms: List[(Tree, Tree, Compatibility[Type])]): Compiled[Type, Tree] =
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
  private def compileEnumerationMapping(
      c: blackbox.Context
  )(protobufType: c.universe.Type, modelType: c.universe.Type): Compiled[c.universe.Type, c.universe.Tree] = {
    import FormatImpl._
    import c.universe._

    val mapping = q"io.moia.protos.teleproto"

    val protobufOptions = symbolsByTolerantName(c)(protobufType.typeSymbol.asClass.knownDirectSubclasses.filter(_.isModuleClass))
    val modelOptions    = symbolsByTolerantName(c)(modelType.typeSymbol.asClass.knownDirectSubclasses.filter(_.isModuleClass))

    val unmatchedModelOptions = modelOptions.filterKeys(name => !protobufOptions.contains(name)).values.map(_.name.decodedName)

    if (unmatchedModelOptions.nonEmpty) {
      c.error(
        c.enclosingPosition,
        s"The options in `${protobufType.typeSymbol.fullName}` do not match ${unmatchedModelOptions.map(c => s"`$c`").mkString(", ")} in `${modelType.typeSymbol.fullName}`."
      )
    }

    val surplusProtobufOptions = protobufOptions.filterKeys(name => !modelOptions.contains(name)).values.map(_.name.decodedName)
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

  private[teleproto] def warnForwardCompatible(
      c: blackbox.Context
  )(protobufType: c.universe.Type, modelType: c.universe.Type, compatibility: Compatibility[c.universe.Type]): Unit = {

    def info           = compatibilityInfo(c)(compatibility)
    lazy val signature = compatibilitySignature(c)(compatibility)

    compatibilityAnnotation(c)(c.typeOf[forward]) match {
      case None if compatibility.hasIssues =>
        c.warning(
          c.enclosingPosition,
          s"""`$modelType` is just forward compatible to `$protobufType`:\n$info\nAnnotate `@forward("$signature")` to remove this warning!"""
        )

      case Some(_) if !compatibility.hasIssues =>
        c.error(
          c.enclosingPosition,
          s"""`$modelType` is compatible to `$protobufType`.\nAnnotation `@forward` should be removed!"""
        )

      case Some(actual) if actual != signature =>
        c.error(
          c.enclosingPosition,
          s"""Forward compatibility of `$modelType` to `$protobufType` changed!\n$info\nValidate the compatibility and annotate `@forward("$signature")` to remove this error!"""
        )

      case _ =>
      // everything as expected
    }
  }
}
