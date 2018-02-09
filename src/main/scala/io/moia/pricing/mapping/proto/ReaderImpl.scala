package io.moia.pricing.mapping.proto

import scala.reflect.macros.whitebox

object ReaderImpl {

  def reader_impl[P: c.WeakTypeTag, M: c.WeakTypeTag](
      c: whitebox.Context): c.Expr[Reader[P, M]] =
    compile[P, M](c, warnAboutBackwardCompatibility = true)

  def backwardReader_impl[P: c.WeakTypeTag, M: c.WeakTypeTag](
      c: whitebox.Context): c.Expr[Reader[P, M]] =
    compile[P, M](c, warnAboutBackwardCompatibility = false)

  private def compile[P: c.WeakTypeTag, M: c.WeakTypeTag](
      c: whitebox.Context,
      warnAboutBackwardCompatibility: Boolean): c.Expr[Reader[P, M]] = {
    import FormatImpl._
    import c.universe._

    val protobufType = weakTypeTag[P].tpe
    val modelType = weakTypeTag[M].tpe

    if (checkClassTypes(c)(protobufType, modelType))
      c.Expr[Reader[P, M]](
        traceCompiled(c)(
          compileClassMapping(c)(protobufType,
                                 modelType,
                                 warnAboutBackwardCompatibility)))
    else if (checkHierarchyTypes(c)(protobufType, modelType))
      c.Expr[Reader[P, M]](
        traceCompiled(c)(
          compileTraitMapping(c)(protobufType,
                                 modelType,
                                 warnAboutBackwardCompatibility)))
    else {
      c.abort(
        c.enclosingPosition,
        s"Cannot create a reader from `$protobufType` to `$modelType`. Just mappings between case classes and sealed traits are possible."
      )
    }
  }

  /**
    * Assumes that `tree` requires an implicit `Reader[$specificModelType, $protobufWrappedType]`.
    * If such a type is implicitly available just returns `tree`.
    * Otherwise checks if a reader can be generated.
    *  If not just returns `tree`.
    *  Otherwise generates it and makes it locally visible for `tree`.
    */
  private def wrapWithMissingImplicitReader(c: whitebox.Context)(
      protobufType: c.universe.Type,
      modelType: c.universe.Type,
      warnAboutBackwardCompatibility: Boolean)(
      tree: c.universe.Tree): c.universe.Tree = {
    import FormatImpl._
    import c.universe._

    val mapping = q"io.moia.pricing.mapping.proto"

    if (implicitAvailable(c)(c.typeOf[Reader[_, _]], protobufType, modelType)) {
      tree // implicit is available, so do not generate one!
    } else {
      if (checkClassTypes(c)(protobufType, modelType)) {
        val implicitValue = compileClassMapping(c)(
          protobufType,
          modelType,
          warnAboutBackwardCompatibility)
        val result = q"""implicit val ${TermName(c.freshName(
          s"generatedImplicitClassReader"))}: $mapping.Reader[$protobufType, $modelType] = $implicitValue; $tree"""
        result
      } else if (checkHierarchyTypes(c)(protobufType, modelType)) {
        val implicitValue = compileTraitMapping(c)(
          protobufType,
          modelType,
          warnAboutBackwardCompatibility)
        val result = q"""implicit val ${TermName(c.freshName(
          s"generatedImplicitTraitReader"))}: $mapping.Reader[$protobufType, $modelType] = $implicitValue; $tree"""
        result
      } else {
        tree // no generated mapping possible, let the compiler explain the problem ...
      }
    }
  }

  /**
    * Iterate through the parameters and compile for loop entries:
    *
    * - If name is missing in model, ignore (backward compatible)
    * - If name is missing in protobuf and has default value in model, do not pass to the constructor to set to the default value (backward compatible)
    * - If name is missing in protobuf and optional in model, set to `None` (backward compatible)
    * - If name is missing in protobuf and collection in model, set to empty collection (backward compatible)
    * - If name is missing in protobuf otherwise, fail (not compatible)
    * - If name with type in protobuf PV matches name with type in model MV
    *   - If PV is Option[PV'] and model Option[MV'],  compile `name <- optional[PV', MV'](protobuf.name, "/name")`
    *   - If PV is Option[PV'] and model MV',          compile `name <- required[PV', MV'](protobuf.name, "/name")`
    *   - If PV is Seq[PV'] and model Collection[MV'], compile `name <- sequence[Collection, PV', MV'](protobuf.name, "/name")`
    *   - Otherwise                                    compile `name <- transform[PV, MV](protobuf.name, "/name")` <- requires implicit reader for exact case
    */
  private def compileClassMapping(c: whitebox.Context)(
      protobufType: c.universe.Type,
      modelType: c.universe.Type,
      warnAboutBackwardCompatibility: Boolean): c.universe.Tree = {
    import FormatImpl._
    import c.universe._

    val modelCompanion = modelType.typeSymbol.companion

    val protobufCons = protobufType.member(termNames.CONSTRUCTOR).asMethod
    val modelCons = modelType.member(termNames.CONSTRUCTOR).asMethod

    val protobufParams = protobufCons.paramLists.headOption
      .getOrElse(sys.error("Scapegoat..."))
      .map(_.asTerm)
    val modelParams = modelCons.paramLists.headOption
      .getOrElse(sys.error("Scapegoat..."))
      .map(_.asTerm)

    val mapping = q"io.moia.pricing.mapping.proto"

    def forLoop(parameters: List[(TermSymbol, MatchingParam[Type, Tree])],
                cons: Tree): Tree = {
      parameters match {
        case Nil =>
          cons

        case (termSymbol, matchingParam) :: rest =>
          val restTransformed = forLoop(rest, cons)

          val path = q""""/"+${termSymbol.name.decodedName.toString}"""

          matchingParam match {
            case TransformParam(from, to)
                if from <:< weakTypeOf[Option[_]] && !(to <:< weakTypeOf[
                  Option[_]]) =>
              wrapWithMissingImplicitReader(c)(
                innerType(c)(from),
                to,
                warnAboutBackwardCompatibility)(q"""$mapping.Reader.required[${innerType(
                c)(from)}, $to](protobuf.${termSymbol.name}, $path).flatMap { case ${termSymbol.name} => $restTransformed }""")
            case TransformParam(from, to)
                if from <:< weakTypeOf[Seq[_]] && to <:< weakTypeOf[
                  Iterable[_]] =>
              wrapWithMissingImplicitReader(c)(innerType(c)(from),
                                               innerType(c)(to),
                                               warnAboutBackwardCompatibility)(
                q"""$mapping.Reader.sequence[${to.typeConstructor}, ${innerType(
                  c)(from)}, ${innerType(c)(to)}](protobuf.${termSymbol.name}, $path).flatMap { case ${termSymbol.name} => $restTransformed }""")
            case TransformParam(from, to) =>
              wrapWithMissingImplicitReader(c)(from,
                                               to,
                                               warnAboutBackwardCompatibility)(
                q"""$mapping.Reader.transform[$from, $to](protobuf.${termSymbol.name}, $path).flatMap { case ${termSymbol.name} => $restTransformed }""")
            case ExplicitDefaultParam(expr) =>
              q"""val ${termSymbol.name} = $expr; $restTransformed"""
            case SkippedDefaultParam(_) =>
              restTransformed
          }
      }
    }

    def transformation(parameters: Seq[MatchingParam[Type, Tree]]) = {

      val passedArgumentNames =
        modelParams
          .zip(parameters)
          .flatMap(_ match {
            // unmatched parameters with default values are not passed: they get their defaults
            case (_, SkippedDefaultParam(_)) => None
            case (param, _)                  => Some(q"${param.name} = ${param.name}")
          })

      val cons =
        q"""$mapping.PbSuccess[$modelType](${modelCompanion.asTerm}.apply(..$passedArgumentNames))"""

      val transformed = forLoop(modelParams.zip(parameters), cons)

      traceCompiled(c)(q"""
          new $mapping.Reader[$protobufType, $modelType] {
            def read(protobuf: $protobufType) =
              $transformed
          }""")
    }

    compareCaseAccessors(c)(modelType, protobufParams, modelParams) match {
      case Compatible(parameters) =>
        transformation(parameters)

      case BackwardCompatible(surplusParameters,
                              defaultParameters,
                              parameters) =>
        if (warnAboutBackwardCompatibility) {

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

          c.warning(
            c.enclosingPosition,
            s"`$protobufType` is just backward compatible to $modelType ($info): Use `ProtocolBuffers.backwardReader[$protobufType, $modelType]` instead!"
          )
        }

        transformation(parameters)

      case Incompatible(missingParameters) =>
        c.abort(
          c.enclosingPosition,
          s"`$protobufType` and $modelType are not compatible (${missingParameters
            .map(name => s"`$name`")
            .mkString(", ")} missing in `$protobufType`)!"
        )
    }
  }

  private sealed trait Matching[+TYPE, +TREE]

  /* Same arity */
  private case class Compatible[TYPE, TREE](
      parameters: Seq[MatchingParam[TYPE, TREE]])
      extends Matching[TYPE, TREE]

  /* Missing names on Model side and no missing names Protobuf side that are not optional or without default on model side */
  private case class BackwardCompatible[TYPE, TREE](
      surplusParameters: Iterable[String],
      defaultParameters: Iterable[String],
      parameters: Seq[MatchingParam[TYPE, TREE]])
      extends Matching[TYPE, TREE]

  /* All remaining */
  private case class Incompatible[TYPE, TREE](
      missingParameters: Iterable[String])
      extends Matching[TYPE, TREE]

  private sealed trait MatchingParam[TYPE, TREE]

  private case class TransformParam[TYPE, TREE](from: TYPE, to: TYPE)
      extends MatchingParam[TYPE, TREE]

  private case class ExplicitDefaultParam[TYPE, TREE](value: TREE)
      extends MatchingParam[TYPE, TREE]

  private case class SkippedDefaultParam[TYPE, TREE](unit: Unit)
      extends MatchingParam[TYPE, TREE]

  private def compareCaseAccessors(c: whitebox.Context)(
      modelType: c.universe.Type,
      protobufParams: List[c.universe.TermSymbol],
      modelParams: List[c.universe.TermSymbol])
    : Matching[c.universe.Type, c.universe.Tree] = {

    import c.universe._

    val protobufByName = protobufParams
      .groupBy(_.name)
      .mapValues(_.headOption.getOrElse(sys.error("Scapegoat...")))
    val modelByName = modelParams
      .groupBy(_.name)
      .mapValues(_.headOption.getOrElse(sys.error("Scapegoat...")))

    val protobufNames = protobufByName.keySet

    val surplusProtobufNames = protobufNames -- modelByName.keySet

    val matchedParams: List[Option[MatchingParam[Type, Tree]]] =
      for ((modelParam, index) <- modelParams.zipWithIndex; number = index + 1)
        yield {
          protobufByName.get(modelParam.name) match {
            case Some(protobufParam) =>
              Some(
                TransformParam[Type, Tree](protobufParam.typeSignature,
                                           modelParam.typeSignature))

            case None if modelParam.isParamWithDefault =>
              Some(SkippedDefaultParam[Type, Tree](Unit))

            case None if modelParam.typeSignature <:< weakTypeOf[Option[_]] =>
              Some(ExplicitDefaultParam[Type, Tree](q"""None"""))

            case None =>
              None
          }
        }

    val namedMatchedParams = modelParams.map(_.name).zip(matchedParams)

    val missingModelParamNames =
      namedMatchedParams.collect {
        case (name, None) => name
      }

    val matchingModelParams =
      namedMatchedParams.collect {
        case (name, Some(matchingParam)) => (name, matchingParam)
      }

    val backwardCompatibleModelParamNames =
      matchingModelParams.collect {
        case (name, SkippedDefaultParam(_))  => name
        case (name, ExplicitDefaultParam(_)) => name
      }

    if (missingModelParamNames.nonEmpty)
      Incompatible(missingModelParamNames.map(_.decodedName.toString))
    else if (surplusProtobufNames.nonEmpty || backwardCompatibleModelParamNames.nonEmpty)
      BackwardCompatible[Type, Tree](
        surplusProtobufNames.map(_.decodedName.toString),
        backwardCompatibleModelParamNames.map(_.decodedName.toString),
        matchingModelParams.map(_._2))
    else
      Compatible(matchingModelParams.map(_._2))
  }

  /**
    * Iterate through the sub-types of the model and check for a corresponding method in the protobuf type.
    * If there are more types on the model side, the mapping is backward compatible.
    * If there are more types on the protobuf side (), the mapping is not possible.
    *
    * (p: protobuf.FooOrBar) =>
    * None
    * .orElse(p.value.foo.map(foo => transform[protobuf.Foo, model.Foo](foo, "/foo")))
    * .orElse(p.value.bar.map(bar => transform[protobuf.Bar, model.Bar](bar, "/bar")))
    * .getOrElse(PbFailure("Value is required."))
    */
  private def compileTraitMapping(c: whitebox.Context)(
      protobufType: c.universe.Type,
      modelType: c.universe.Type,
      warnAboutBackwardCompatibility: Boolean): c.universe.Tree = {
    import FormatImpl._
    import c.universe._

    val mapping = q"io.moia.pricing.mapping.proto"

    val protobufSubClasses =
      symbolsByName(c)(protoHierarchyCaseClasses(c)(protobufType))
    val modelSubClasses =
      symbolsByName(c)(modelType.typeSymbol.asClass.knownDirectSubclasses)

    if (protobufSubClasses.isEmpty)
      c.error(
        c.enclosingPosition,
        s"No case classes were found in object `Value` in `${protobufType.typeSymbol.fullName}`. Your protofile is most likely not as it should!"
      )

    val unmatchedProtobufClasses = protobufSubClasses.keySet -- modelSubClasses.keySet

    if (unmatchedProtobufClasses.nonEmpty)
      c.error(
        c.enclosingPosition,
        s"`${modelType.typeSymbol.name}` does not match ${unmatchedProtobufClasses
          .map(c => s"`$c`")
          .mkString(", ")} of object `Value` in `${protobufType.typeSymbol.name}`."
      )

    val surplusModelClasses = modelSubClasses.keySet -- protobufSubClasses.keySet

    if (surplusModelClasses.nonEmpty && warnAboutBackwardCompatibility)
      warnBackwardCompatible(c)(
        protobufType,
        modelType,
        s"${surplusModelClasses.map(c => s"`$c`").mkString(", ")} in `${modelType.typeSymbol.name}` are not matched in `${protobufType.typeSymbol.name}`."
      )

    val maybeTransformedTrees =
      for ((className, protobufClass) <- protobufSubClasses;
           modelClass <- modelSubClasses.get(className))
        yield {

          val classNameDecoded = className.decodedName.toString
          val methodName = TermName(
            classNameDecoded.take(1).toLowerCase + classNameDecoded.drop(1))

          val method = protobufClass.typeSignature.decl(methodName)

          val protobufWrappedType = innerType(c)(method.asMethod.returnType)

          val specificModelType = modelClass.asClass.selfType

          wrapWithMissingImplicitReader(c)(protobufWrappedType,
                                           specificModelType,
                                           warnAboutBackwardCompatibility)(
            q"""protobuf.value.$methodName.map(value => $mapping.Reader.transform[$protobufWrappedType, $specificModelType](value, "/" + ${methodName.toString}))""")
        }

    val maybeTransformedTree = maybeTransformedTrees
      .reduceOption((t1, t2) => q"$t1.orElse($t2)")
      .getOrElse(q"None")

    val transformed =
      q"""$maybeTransformedTree.getOrElse($mapping.PbFailure("Value is required."))"""

    traceCompiled(c)(q"""
          new $mapping.Reader[$protobufType, $modelType] {
            def read(protobuf: $protobufType) = $transformed
          }""")
  }

  private[mapping] def warnBackwardCompatible(c: whitebox.Context)(
      protobufType: c.universe.Type,
      modelType: c.universe.Type,
      info: String): Unit = {
    c.warning(
      c.enclosingPosition,
      s"`$protobufType` is just backward compatible to $modelType ($info): Use `ProtocolBuffers.backwardReader[$protobufType, $modelType]` instead!"
    )
  }
}
