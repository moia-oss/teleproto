package io.moia.pricing.mapping.proto

import scala.reflect.macros.whitebox

object WriterImpl {

  /**
    * Validates if business model type can be written to the Protocol Buffers type
    * (matching case classes or matching sealed trait hierarchy).
    * If just forward compatible then raise a warning.
    */
  def writer_impl[M: c.WeakTypeTag, P: c.WeakTypeTag](
      c: whitebox.Context): c.Expr[Writer[M, P]] =
    compile[M, P](c, warnAboutForwardCompatibility = true)

  def forwardWriter_impl[M: c.WeakTypeTag, P: c.WeakTypeTag](
      c: whitebox.Context): c.Expr[Writer[M, P]] =
    compile[M, P](c, warnAboutForwardCompatibility = false)

  private def compile[M: c.WeakTypeTag, P: c.WeakTypeTag](
      c: whitebox.Context,
      warnAboutForwardCompatibility: Boolean): c.Expr[Writer[M, P]] = {
    import FormatImpl._
    import c.universe._

    val modelType = weakTypeTag[M].tpe
    val protobufType = weakTypeTag[P].tpe

    if (checkClassTypes(c)(protobufType, modelType)) {
      ensureValidTypes(c)(protobufType, modelType)
      c.Expr[Writer[M, P]](
        compileClassMapping(c)(protobufType,
                               modelType,
                               warnAboutForwardCompatibility))
    } else if (checkHierarchyTypes(c)(protobufType, modelType)) {
      c.Expr[Writer[M, P]](
        compileTraitMapping(c)(protobufType,
                               modelType,
                               warnAboutForwardCompatibility))
    } else {
      c.abort(
        c.enclosingPosition,
        s"Cannot create a writer from `$modelType` to `$protobufType`. Just mappings between case classes and hierarchies + sealed traits are possible."
      )
    }
  }

  /**
    * Assumes that `tree` requires an implicit `Writer[$specificModelType, $protobufWrappedType]`.
    * If such a type is implicitly available just returns `tree`.
    * Otherwise checks if a writer can be generated.
    *  If not just returns `tree`.
    *  Otherwise generates it and makes it locally visible for `tree`.
    */
  private def wrapWithMissingImplicitWriter(c: whitebox.Context)(
      modelType: c.universe.Type,
      protobufType: c.universe.Type,
      warnAboutForwardCompatibility: Boolean)(
      tree: c.universe.Tree): c.universe.Tree = {
    import FormatImpl._
    import c.universe._

    val mapping = q"io.moia.pricing.mapping.proto"

    if (implicitAvailable(c)(c.typeOf[Writer[_, _]], modelType, protobufType)) {
      tree // implicit is available, so do not generate one!
    } else {
      if (checkClassTypes(c)(protobufType, modelType)) {
        val implicitValue = compileClassMapping(c)(
          protobufType,
          modelType,
          warnAboutForwardCompatibility)
        val result = q"""implicit val ${TermName(c.freshName(
          s"generatedImplicitClassWriter"))}: $mapping.Writer[$modelType, $protobufType] = $implicitValue; $tree"""
        result
      } else if (checkHierarchyTypes(c)(protobufType, modelType)) {
        val implicitValue = compileTraitMapping(c)(
          protobufType,
          modelType,
          warnAboutForwardCompatibility)
        val result = q"""implicit val ${TermName(c.freshName(
          s"generatedImplicitTraitWriter"))}: $mapping.Writer[$modelType, $protobufType] = $implicitValue; $tree"""
        result
      } else {
        tree // no generated mapping possible, let the compiler explain the problem ...
      }
    }
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
  private def compileClassMapping(c: whitebox.Context)(
      protobufType: c.universe.Type,
      modelType: c.universe.Type,
      warnAboutForwardCompatibility: Boolean): c.universe.Tree = {
    import FormatImpl._
    import c.universe._

    // at this point all errors are assumed to be due to evolution

    val protobufCompanion = protobufType.typeSymbol.companion

    val protobufCons = protobufType.member(termNames.CONSTRUCTOR).asMethod
    val modelCons = modelType.member(termNames.CONSTRUCTOR).asMethod

    val protobufParams = protobufCons.paramLists.headOption
      .getOrElse(sys.error("Scapegoat..."))
      .map(_.asTerm)
    val modelParams = modelCons.paramLists.headOption
      .getOrElse(sys.error("Scapegoat..."))
      .map(_.asTerm)

    val mapping = q"io.moia.pricing.mapping.proto"

    val genericWriterType = c.typeOf[Writer[_, _]]

    def transformation(parameters: Seq[MatchingParam[Type, Tree]]) = {

      val namedArguments =
        protobufParams
          .zip(parameters)
          .flatMap(_ match {
            // unmatched parameters with default values are not passed: they get their defaults
            case (_, SkippedDefaultParam(_)) => None
            case (param, TransformParam(from, to)) =>
              val arg =
                if (to <:< weakTypeOf[Option[_]] && !(from <:< weakTypeOf[
                      Option[_]])) {
                  wrapWithMissingImplicitWriter(c)(
                    from,
                    innerType(c)(to),
                    warnAboutForwardCompatibility)(q"""$mapping.Writer.present[$from, ${innerType(
                    c)(to)}](model.${param.name})""")
                } else if (from <:< weakTypeOf[Iterable[_]] && to <:< weakTypeOf[
                             scala.collection.immutable.Seq[_]]) {
                  wrapWithMissingImplicitWriter(c)(
                    innerType(c)(from),
                    to,
                    warnAboutForwardCompatibility)(
                    q"""$mapping.Writer.collection[${innerType(c)(from)}, ${innerType(c)(
                      to)}, scala.collection.immutable.Seq](model.${param.name})"""
                  )
                } else if (from <:< weakTypeOf[Iterable[_]] && to <:< weakTypeOf[
                             Seq[_]]) {
                  wrapWithMissingImplicitWriter(c)(
                    innerType(c)(from),
                    to,
                    warnAboutForwardCompatibility)(
                    q"""$mapping.Writer.sequence[${innerType(c)(from)}, ${innerType(
                      c)(to)}](model.${param.name})"""
                  )
                } else {
                  wrapWithMissingImplicitWriter(c)(
                    from,
                    to,
                    warnAboutForwardCompatibility)(
                    q"""$mapping.Writer.transform[$from, $to](model.${param.name})""")
                }

              Some(param.name -> arg)
          })

      val cons =
        q"""${protobufCompanion.asTerm}.apply(..${for ((name, arg) <- namedArguments)
          yield q"$name = $arg"})"""

      traceCompiled(c)(q"""
          new $mapping.Writer[$modelType, $protobufType] {
            def write(model: $modelType) = $cons
          }""")
    }

    compareCaseAccessors(c)(modelType, protobufParams, modelParams) match {
      case Compatible(parameters) =>
        transformation(parameters)

      case ForwardCompatible(surplusParameters,
                             defaultParameters,
                             parameters) =>
        if (warnAboutForwardCompatibility) {

          val surplusInfo =
            if (surplusParameters.nonEmpty)
              Some(
                s"${surplusParameters.map(name => s"`$name`").mkString(", ")} dropped from `$protobufType`")
            else
              None

          val defaultInfo =
            if (defaultParameters.nonEmpty)
              Some(
                s"default values for ${defaultParameters.map(name => s"`$name`").mkString(", ")}")
            else
              None

          val info = List(surplusInfo, defaultInfo).flatten.mkString(", ")
          warnForwardCompatible(c)(protobufType, modelType, info)
        }

        transformation(parameters)
    }
  }

  private sealed trait Compatibility[+TYPE, +TREE]

  /* Same arity */
  private case class Compatible[TYPE, TREE](
      parameters: Seq[MatchingParam[TYPE, TREE]])
      extends Compatibility[TYPE, TREE]

  /* Missing names on Protobuf side and missing names on Model side */
  private case class ForwardCompatible[TYPE, TREE](
      surplusParameters: Iterable[String],
      defaultParameters: Iterable[String],
      parameters: Seq[MatchingParam[TYPE, TREE]])
      extends Compatibility[TYPE, TREE]

  private sealed trait MatchingParam[TYPE, TREE]

  private case class TransformParam[TYPE, TREE](from: TYPE, to: TYPE)
      extends MatchingParam[TYPE, TREE]

  private case class SkippedDefaultParam[TYPE, TREE](unit: Unit)
      extends MatchingParam[TYPE, TREE]

  private def compareCaseAccessors(c: whitebox.Context)(
      modelType: c.universe.Type,
      protobufParams: List[c.universe.TermSymbol],
      modelParams: List[c.universe.TermSymbol])
    : Compatibility[c.universe.Type, c.universe.Tree] = {

    import c.universe._

    val protobufByName = FormatImpl.symbolsByName(c)(protobufParams)
    val modelByName = FormatImpl.symbolsByName(c)(modelParams)

    val surplusModelNames = modelByName.keySet -- protobufByName.keySet

    val matchingProtobufParams: List[MatchingParam[Type, Tree]] =
      for ((protobufParam, index) <- protobufParams.zipWithIndex;
           number = index + 1) yield {
        modelByName.get(protobufParam.name) match {
          case Some(modelParam) =>
            TransformParam[Type, Tree](modelParam.typeSignature,
                                       protobufParam.typeSignature)

          case None =>
            SkippedDefaultParam[Type, Tree](Unit)
        }
      }

    val namedMatchedParams =
      protobufParams.map(_.name).zip(matchingProtobufParams)

    val forwardCompatibleModelParamNames =
      namedMatchedParams.collect {
        case (name, SkippedDefaultParam(_)) => name
      }

    if (surplusModelNames.nonEmpty || forwardCompatibleModelParamNames.nonEmpty)
      ForwardCompatible[Type, Tree](
        surplusModelNames.map(_.decodedName.toString),
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
  private def compileTraitMapping(c: whitebox.Context)(
      protobufType: c.universe.Type,
      modelType: c.universe.Type,
      warnAboutForwardCompatibility: Boolean): c.universe.Tree = {
    import FormatImpl._
    import c.universe._

    val mapping = q"io.moia.pricing.mapping.proto"

    val protobufCompanion = protobufType.typeSymbol.companion

    val protobufSubClasses =
      symbolsByName(c)(protoHierarchyCaseClasses(c)(protobufType))
    val modelSubClasses =
      symbolsByName(c)(modelType.typeSymbol.asClass.knownDirectSubclasses)

    if (protobufSubClasses.isEmpty)
      c.error(
        c.enclosingPosition,
        s"No case classes were found in object `Value` in `${protobufType.typeSymbol.fullName}`. Your protofile is most likely not as it should!"
      )

    val unmatchedModelClasses = modelSubClasses.keySet -- protobufSubClasses.keySet

    if (unmatchedModelClasses.nonEmpty) {
      c.error(
        c.enclosingPosition,
        s"Object `Value` in `${protobufType.typeSymbol.fullName}` does not match ${unmatchedModelClasses
          .map(c => s"`$c`")
          .mkString(", ")} in `${modelType.typeSymbol.fullName}`."
      )
    }

    val surplusProtobufClasses = protobufSubClasses.keySet -- modelSubClasses.keySet

    if (surplusProtobufClasses.nonEmpty && warnAboutForwardCompatibility)
      warnForwardCompatible(c)(
        protobufType,
        modelType,
        s"${surplusProtobufClasses.map(c => s"`$c`").mkString(", ")} in `${protobufType.typeSymbol.fullName}` are not matched in `${modelType.typeSymbol.fullName}`."
      )

    val checkAndTransforms: Iterable[(Tree, Tree)] =
      for ((className, protobufClass) <- protobufSubClasses;
           modelClass <- modelSubClasses.get(className))
        yield {

          val classNameDecoded = className.decodedName.toString
          val methodName = TermName(
            classNameDecoded.take(1).toLowerCase + classNameDecoded.drop(1))

          val method = protobufClass.typeSignature.decl(methodName)

          val protobufWrappedType = innerType(c)(method.asMethod.returnType)

          val specificModelType = modelClass.asClass.selfType

          val checkSpecific = q"model.isInstanceOf[$specificModelType]"

          val transformSpecific =
            wrapWithMissingImplicitWriter(c)(specificModelType,
                                             protobufWrappedType,
                                             warnAboutForwardCompatibility)(
              q"""$mapping.Writer.transform[$specificModelType, $protobufWrappedType](model.asInstanceOf[$specificModelType])""")

          val transformWrapped =
            q"""${protobufCompanion.asTerm}(${protobufCompanion.asTerm}.Value.${TermName(
              protobufClass.asClass.name.decodedName.toString)}($transformSpecific))"""

          checkSpecific -> transformWrapped
        }

    def ifElses(checkAndTransforms: List[(Tree, Tree)]): Tree = {
      checkAndTransforms match {
        // last entry: cast has to match (in `else` or on top-level)
        case List((_, transform)) =>
          transform

        case (check, transform) :: rest =>
          q"""if($check) $transform else ${ifElses(rest)}"""

        case Nil =>
          q"""throw new IllegalStateException"""
      }
    }

    val transformed = ifElses(checkAndTransforms.toList)

    traceCompiled(c)(q"""
          new $mapping.Writer[$modelType, $protobufType] {
            def write(model: $modelType) = $transformed
          }""")
  }

  private[mapping] def warnForwardCompatible(c: whitebox.Context)(
      protobufType: c.universe.Type,
      modelType: c.universe.Type,
      info: String): Unit = {
    c.warning(
      c.enclosingPosition,
      s"`$modelType` is just forward compatible to `$protobufType` ($info): Use `ProtocolBuffers.forwardWriter[$modelType, $protobufType]` instead!"
    )
  }
}
