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
object ReaderImpl {

  def reader_impl[P: c.WeakTypeTag, M: c.WeakTypeTag](c: blackbox.Context): c.Expr[Reader[P, M]] =
    compile[P, M](c)

  import FormatImpl._

  private def compile[P: c.WeakTypeTag, M: c.WeakTypeTag](c: blackbox.Context): c.Expr[Reader[P, M]] = {

    import c.universe._

    val protobufType = weakTypeTag[P].tpe
    val modelType    = weakTypeTag[M].tpe

    if (checkClassTypes(c)(protobufType, modelType)) {
      val (result, compatibility) = compileClassMapping(c)(protobufType, modelType)
      warnBackwardCompatible(c)(protobufType, modelType, compatibility)
      c.Expr[Reader[P, M]](traceCompiled(c)(result))
    } else if (checkEnumerationTypes(c)(protobufType, modelType)) {
      val (result, compatibility) = compileEnumerationMapping(c)(protobufType, modelType)
      warnBackwardCompatible(c)(protobufType, modelType, compatibility)
      c.Expr[Reader[P, M]](traceCompiled(c)(result))
    } else if (checkHierarchyTypes(c)(protobufType, modelType)) {
      val (result, compatibility) = compileTraitMapping(c)(protobufType, modelType)
      warnBackwardCompatible(c)(protobufType, modelType, compatibility)
      c.Expr[Reader[P, M]](traceCompiled(c)(result))
    } else {
      c.abort(
        c.enclosingPosition,
        s"Cannot create a reader from `$protobufType` to `$modelType`. Just mappings between a) case classes b) hierarchies + sealed traits c) sealed traits from enums are possible."
      )
    }
  }

  /**
    * Passes a tree to `f` that is of type `Reader[$protobufType, $modelType]`.
    *
    * If such a type is not implicitly available checks if a reader can be generated, then generates and returns it.
    * If not "asks" for it implicitly and let the compiler explain the problem if it does not exist.
    *
    * If the reader is generated, that might cause a compatibility issue.
    *
    * The result is `f` applied to the reader expression with the (possible) compatibility issues of reader generation
    * (if happened).
    */
  private def withImplicitReader(c: blackbox.Context)(protobufType: c.universe.Type, modelType: c.universe.Type)(
      compileInner: c.universe.Tree => c.universe.Tree
  ): Compiled[c.universe.Type, c.universe.Tree] = {
    import FormatImpl._
    import c.universe._

    val mapping = q"io.moia.protos.teleproto"

    // look for an implicit reader
    val readerType = appliedType(c.weakTypeTag[Reader[_, _]].tpe, protobufType, modelType)

    val existingReader = c.inferImplicitValue(readerType)

    // "ask" for the implicit reader or use the found one
    def ask: Compiled[c.universe.Type, c.universe.Tree] =
      (compileInner(q"implicitly[$readerType]"), Compatibility.full)

    if (existingReader == EmptyTree)
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
    * Iterate through the parameters and compile values for them:
    *
    * - If name is missing in model, ignore (backward compatible)
    * - If name is missing in protobuf and has default value in model, do not pass to the constructor to set to the default value (backward compatible)
    * - If name is missing in protobuf and optional in model, set to `None` (backward compatible)
    * - If name is missing in protobuf and collection in model, set to empty collection (backward compatible)
    * - If name is missing in protobuf otherwise, fail (not compatible)
    * - If name with type in protobuf PV matches name with type in model MV
    *   - If PV and MV are compatible,                 compile `val name = PbSuccess(protobuf.name)`
    *   - If PV is Option[PV'] and model Option[MV'],  compile `val name = optional[PV', MV'](protobuf.name, "/name")`
    *   - If PV is Option[PV'] and model MV',          compile `val name = required[PV', MV'](protobuf.name, "/name")`
    *   - If PV is Seq[PV'] and model Collection[MV'], compile `val name = sequence[Collection, PV', MV'](protobuf.name, "/name")`
    *   - Otherwise                                    compile `val name = transform[PV, MV](protobuf.name, "/name")` <- requires implicit reader for exact case
    *
    * Loop (de-sugared using flatMap) over all values and combine the result using the detached model constructor.
    *
    * Return the result expression and whether the matching was just backward compatible.
    */
  private def compileClassMapping(c: blackbox.Context)(
      protobufType: c.universe.Type,
      modelType: c.universe.Type
  ): Compiled[c.universe.Type, c.universe.Tree] = {
    import FormatImpl._
    import c.universe._

    val modelCompanion = modelType.typeSymbol.companion

    val protobufCons = protobufType.member(termNames.CONSTRUCTOR).asMethod
    val modelCons    = modelType.member(termNames.CONSTRUCTOR).asMethod

    val protobufParams = protobufCons.paramLists.headOption.getOrElse(sys.error("Scapegoat...")).map(_.asTerm)
    val modelParams    = modelCons.paramLists.headOption.getOrElse(sys.error("Scapegoat...")).map(_.asTerm)

    val mapping = q"io.moia.protos.teleproto"

    /**
      * For each parameter creates a value assignment with the name of the parameter, e.g.
      * `val targetParameter = transformationExpression`
      */
    def valueDefinitions(parameters: List[(TermSymbol, MatchingParam[Type, Tree])]): List[Compiled[Type, Tree]] = {
      parameters.flatMap {
        case (termSymbol, matchingParam) =>
          val path = q""""/"+${termSymbol.name.decodedName.toString}"""

          def assign(compiled: Compiled[Type, Tree]): Compiled[Type, Tree] =
            (q"val ${termSymbol.name} = ${compiled._1}", compiled._2)

          matchingParam match {
            case TransformParam(from, to) if from <:< to =>
              Some(q"val ${termSymbol.name} = protobuf.${termSymbol.name}" -> Compatibility.full[Type])
            case TransformParam(from, to) if from <:< weakTypeOf[Option[_]] && !(to <:< weakTypeOf[Option[_]]) =>
              val innerFrom = innerType(c)(from)
              Some(assign(withImplicitReader(c)(innerFrom, to) { readerExpr =>
                q"""$mapping.Reader.required[$innerFrom, $to](protobuf.${termSymbol.name}, $path)($readerExpr)"""
              }))
            case TransformParam(from, to) if from <:< weakTypeOf[Option[_]] && (to <:< weakTypeOf[Option[_]]) =>
              val innerFrom = innerType(c)(from)
              val innerTo = innerType(c)(to)
              Some(assign(withImplicitReader(c)(innerFrom, innerTo) { readerExpr =>
                q"""$mapping.Reader.optional[$innerFrom, $innerTo](protobuf.${termSymbol.name}, $path)($readerExpr)"""
              }))

            case TransformParam(from, to) if from <:< weakTypeOf[Seq[_]] && to <:< weakTypeOf[Iterable[_]] =>
              val innerFrom = innerType(c)(from)
              val innerTo = innerType(c)(to)
              Some(assign(withImplicitReader(c)(innerFrom, innerTo) { readerExpr =>
                // sequence also needs an implicit collection generator which must be looked up since the implicit for the value reader is passed explicitly
                val canBuildFrom = q"""implicitly[scala.collection.Factory[$innerTo, $to]]"""
                q"""$mapping.Reader.sequence[${to.typeConstructor}, $innerFrom, $innerTo](protobuf.${termSymbol.name}, $path)($canBuildFrom, $readerExpr)"""
              }))
            case TransformParam(from, to) =>
              Some(assign(withImplicitReader(c)(from, to) { readerExpr =>
                q"""$mapping.Reader.transform[$from, $to](protobuf.${termSymbol.name}, $path)($readerExpr)"""
              }))
            case ExplicitDefaultParam(expr) =>
              Some(q"val ${termSymbol.name} = $expr" -> Compatibility.full[Type])
            case SkippedDefaultParam(_) =>
              Option.empty[(Tree, Compatibility[Type])]
          }
      }
    }

    /**
      * Constructs an expression `PbFailure.combine(convertedParameters..)` for all transformed parameters (could fail).
      */
    def forLoop(parameters: List[(TermSymbol, MatchingParam[Type, Tree])], cons: Tree): Tree =
      parameters match {
        case Nil =>
          cons
        case (termSymbol, matchingParam) :: rest =>
          val restTransformed = forLoop(rest, cons)
          matchingParam match {
            case TransformParam(from, to) if from <:< to =>
              restTransformed
            case ExplicitDefaultParam(_) =>
              restTransformed
            case SkippedDefaultParam(_) =>
              restTransformed
            case _ =>
              q"""${termSymbol.name}.flatMap { case ${termSymbol.name} => $restTransformed }"""
          }
      }

    /**
      * Constructs an expression `PbFailure(...)` that contains all errors for all failed parameter transformations.
      */
    def combineErrors(parameters: List[(TermSymbol, MatchingParam[Type, Tree])]): Tree = {
      val convertedValues =
        parameters.flatMap {
          case (_, TransformParam(from, to)) if from <:< to =>
            None
          case (_, ExplicitDefaultParam(_)) =>
            None
          case (_, SkippedDefaultParam(_)) =>
            None
          case (termSymbol, _) =>
            Some(termSymbol.name)
        }

      q"$mapping.PbFailure.combine(..$convertedValues)"
    }

    def transformation(parameters: Seq[MatchingParam[Type, Tree]], ownCompatibility: Compatibility[Type]): Compiled[Type, Tree] = {

      val matchedParameters = modelParams.zip(parameters)

      val passedArgumentNames =
        modelParams
          .zip(parameters)
          .flatMap(_ match {
            // unmatched parameters with default values are not passed: they get their defaults
            case (_, SkippedDefaultParam(_)) => None
            case (param, _)                  => Some(q"${param.name} = ${param.name}")
          })

      val (valDefs, parameterCompatibilities) = valueDefinitions(matchedParameters).unzip

      // expression that constructs the successful result: `PbSuccess(ModelClass(transformedParameter..))`
      val cons = q"""$mapping.PbSuccess[$modelType](${modelCompanion.asTerm}.apply(..$passedArgumentNames))"""

      val errorsHandled = q"""val result = ${forLoop(matchedParameters, cons)}; result.orElse(${combineErrors(matchedParameters)})"""

      val transformed = valDefs.foldRight(errorsHandled)((t1, t2) => q"$t1; $t2")

      val parameterCompatibility = parameterCompatibilities.fold(Compatibility.full)(_ merge _)

      val result =
        q"""
          new $mapping.Reader[$protobufType, $modelType] {
            def read(protobuf: $protobufType) = $transformed
          }"""

      (result, ownCompatibility.merge(parameterCompatibility))
    }

    compareCaseAccessors(c)(modelType, protobufParams, modelParams) match {
      case Incompatible(missingParameters) =>
        c.abort(
          c.enclosingPosition,
          s"`$protobufType` and $modelType are not compatible (${missingParameters.map(name => s"`$name`").mkString(", ")} missing in `$protobufType`)!"
        )

      case Compatible(parameters) =>
        transformation(parameters, Compatibility.full)

      case BackwardCompatible(surplusParameters, defaultParameters, parameters) =>
        transformation(parameters, Compatibility(surplusParameters.map(protobufType -> _), defaultParameters.map(modelType -> _), Nil))
    }
  }

  private sealed trait Matching[+TYPE, +TREE]

  /* Same arity */
  private final case class Compatible[TYPE, TREE](parameters: Seq[MatchingParam[TYPE, TREE]]) extends Matching[TYPE, TREE]

  /* Missing names on Model side and no missing names Protobuf side that are not optional or without default on model side */
  private final case class BackwardCompatible[TYPE, TREE](surplusParameters: Iterable[String],
                                                          defaultParameters: Iterable[String],
                                                          parameters: Seq[MatchingParam[TYPE, TREE]])
      extends Matching[TYPE, TREE]

  /* All remaining */
  private final case class Incompatible[TYPE, TREE](missingParameters: Iterable[String]) extends Matching[TYPE, TREE]

  private sealed trait MatchingParam[TYPE, TREE]

  private final case class TransformParam[TYPE, TREE](from: TYPE, to: TYPE) extends MatchingParam[TYPE, TREE]

  private final case class ExplicitDefaultParam[TYPE, TREE](value: TREE) extends MatchingParam[TYPE, TREE]

  private final case class SkippedDefaultParam[TYPE, TREE](unit: Unit) extends MatchingParam[TYPE, TREE]

  private def compareCaseAccessors(c: blackbox.Context)(
      modelType: c.universe.Type,
      protobufParams: List[c.universe.TermSymbol],
      modelParams: List[c.universe.TermSymbol]
  ): Matching[c.universe.Type, c.universe.Tree] = {

    import c.universe._

    val protobufByName = protobufParams.groupBy(_.name).view.mapValues(_.headOption.getOrElse(sys.error("Scapegoat..."))).toMap
    val modelByName    = modelParams.groupBy(_.name).view.mapValues(_.headOption.getOrElse(sys.error("Scapegoat..."))).toMap

    val protobufNames = protobufByName.keySet

    val surplusProtobufNames = protobufNames -- modelByName.keySet

    val matchedParams: List[Option[MatchingParam[Type, Tree]]] =
      for ((modelParam, index) <- modelParams.zipWithIndex; number = index + 1) yield {

        // resolve type parameters to their actual bindings
        val targetType = modelParam.typeSignature.asSeenFrom(modelType, modelType.typeSymbol)

        protobufByName.get(modelParam.name) match {
          case Some(protobufParam) =>
            Some(TransformParam[Type, Tree](protobufParam.typeSignature, targetType))

          case None if modelParam.isParamWithDefault =>
            Some(SkippedDefaultParam[Type, Tree](()))

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
      BackwardCompatible[Type, Tree](surplusProtobufNames.map(_.decodedName.toString),
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
  private def compileTraitMapping(
      c: blackbox.Context
  )(protobufType: c.universe.Type, modelType: c.universe.Type): Compiled[c.universe.Type, c.universe.Tree] = {
    import FormatImpl._
    import c.universe._

    val mapping = q"io.moia.protos.teleproto"

    val protobufSubClasses = symbolsByName(c)(protoHierarchyCaseClasses(c)(protobufType))
    val modelSubClasses    = symbolsByName(c)(modelType.typeSymbol.asClass.knownDirectSubclasses)

    if (protobufSubClasses.isEmpty)
      c.error(
        c.enclosingPosition,
        s"No case classes were found in object `Value` in `${protobufType.typeSymbol.fullName}`. Your protofile is most likely not as it should!"
      )

    val unmatchedProtobufClasses = protobufSubClasses.keySet -- modelSubClasses.keySet

    if (unmatchedProtobufClasses.nonEmpty)
      c.error(
        c.enclosingPosition,
        s"`${modelType.typeSymbol.name}` does not match ${unmatchedProtobufClasses.map(c => s"`$c`").mkString(", ")} of object `Value` in `${protobufType.typeSymbol.name}`."
      )

    val surplusModelClasses = modelSubClasses.keySet -- protobufSubClasses.keySet
    val ownCompatibility    = Compatibility(Nil, Nil, surplusModelClasses.map(name => (modelType, name.toString)))

    val maybeTransformedTrees =
      for {
        (className, protobufClass) <- protobufSubClasses.toSeq
        modelClass                 <- modelSubClasses.get(className)
      } yield {

        val classNameDecoded = className.decodedName.toString
        val methodName       = TermName(classNameDecoded.take(1).toLowerCase + classNameDecoded.drop(1))

        val method = protobufClass.typeSignature.decl(methodName)

        val protobufWrappedType = innerType(c)(method.asMethod.returnType)

        val specificModelType = modelClass.asClass.selfType

        withImplicitReader(c)(protobufWrappedType, specificModelType) { readerExpr =>
          q"""protobuf.value.$methodName.map(value => $mapping.Reader.transform[$protobufWrappedType, $specificModelType](value, "/" + ${methodName.toString})($readerExpr))"""
        }
      }

    // combine the `Option[...]` trees with `orElse` and merge their compatibility
    val (maybeTransformedTree, subClassesCompatibility) =
      maybeTransformedTrees
        .reduceOption { (fst, snd) =>
          val (t1, c1) = fst
          val (t2, c2) = snd
          (q"$t1.orElse($t2)", c1.merge(c2))
        }
        .getOrElse((q"None", Compatibility.full))

    val transformed = q"""$maybeTransformedTree.getOrElse($mapping.PbFailure("Value is required."))"""

    val result =
      q"""
          new $mapping.Reader[$protobufType, $modelType] {
            def read(protobuf: $protobufType) = $transformed
          }"""
    // a type cast is needed due to type inferencer limitations
    (result, ownCompatibility.merge(subClassesCompatibility.asInstanceOf[Compatibility[Type]]))
  }

  /**
    * The protobuf and model type have to be sealed traits.
    * Iterate through the known subclasses of the ScalaPB side and match the model side.
    *
    * If there are more options on the model side, the mapping is backward compatible.
    * If there are more options on the protobuf side, the mapping is not possible.
    *
    * Unrecognized enum values cause a runtime failure.
    *
    * (protobuf: ProtoEnum) => p match {
    *   case ProtoEnum.OPTION_1  => PbSuccess(ModelEnum.OPTION_1)
    *   ...
    *   case ProtoEnum.OPTION_N  => PbSuccess(ModelEnum.OPTION_N)
    *   case Unrecognized(other) => PbFailure(s"Enumeration value $other is unrecognized!")
    * }
    */
  private def compileEnumerationMapping(
      c: blackbox.Context
  )(protobufType: c.universe.Type, modelType: c.universe.Type): Compiled[c.universe.Type, c.universe.Tree] = {
    import FormatImpl._
    import c.universe._

    val mapping = q"io.moia.protos.teleproto"

    val protobufCompanion = protobufType.typeSymbol.companion

    val protobufOptions = symbolsByTolerantName(c)(protobufType.typeSymbol.asClass.knownDirectSubclasses.filter(_.isModuleClass))
    val modelOptions    = symbolsByTolerantName(c)(modelType.typeSymbol.asClass.knownDirectSubclasses.filter(_.isModuleClass))

    val unmatchedProtobufOptions = protobufOptions.toList.collect { case (name, v) if !modelOptions.contains(name) => v.name.decodedName }

    if (unmatchedProtobufOptions.nonEmpty) {
      c.error(
        c.enclosingPosition,
        s"The options in `${modelType.typeSymbol.fullName}` do not match ${unmatchedProtobufOptions.map(c => s"`$c`").mkString(", ")} in `${protobufType.typeSymbol.fullName}`."
      )
    }

    val surplusModelOptions = modelOptions.toList.collect { case (name, v) if !protobufOptions.contains(name) => v.name.decodedName }
    val compatibility       = Compatibility(Nil, Nil, surplusModelOptions.map(name => (modelType, name.toString)))

    val cases =
      for {
        (optionName, modelOption) <- modelOptions.toList
        protobufOption            <- protobufOptions.get(optionName)
      } yield {
        (protobufOption.asClass.selfType.termSymbol, modelOption.asClass.selfType.termSymbol) // expected value to right hand side value
      }

    // construct a de-sugared pattern matching as a cascade of if elses
    def ifElses(cs: List[(Symbol, Symbol)]): Tree =
      cs match {
        case (expected, rhs) :: rest =>
          q"""if(protobuf == $expected) $mapping.PbSuccess($rhs) else ${ifElses(rest)}"""

        case Nil =>
          q"""
              protobuf match {
                case ${protobufCompanion.asTerm}.Unrecognized(other) =>
                  $mapping.PbFailure("Enumeration value " + other + " is unrecognized!")
                case _ =>
                  throw new IllegalStateException("teleproto contains a software bug compiling enums readers: " + protobuf + " is not a matched value.")
              }
             """
      }

    val result = q"""
          new $mapping.Reader[$protobufType, $modelType] {
            def read(protobuf: $protobufType) = ${ifElses(cases)}
          }"""

    (result, compatibility)
  }

  private[teleproto] def warnBackwardCompatible(
      c: blackbox.Context
  )(protobufType: c.universe.Type, modelType: c.universe.Type, compatibility: Compatibility[c.universe.Type]): Unit = {

    def info           = compatibilityInfo(c)(compatibility)
    lazy val signature = compatibilitySignature(c)(compatibility)

    compatibilityAnnotation(c)(c.typeOf[backward]) match {
      case None if compatibility.hasIssues =>
        c.warning(
          c.enclosingPosition,
          s"""`$protobufType` is just backward compatible to `$modelType`:\n$info\nAnnotate `@backward("$signature")` to remove this warning!"""
        )

      case Some(_) if !compatibility.hasIssues =>
        c.error(
          c.enclosingPosition,
          s"""`$protobufType` is compatible to `$modelType`.\nAnnotation `@backward` should be removed!"""
        )

      case Some(actual) if actual != signature =>
        c.error(
          c.enclosingPosition,
          s"""Backward compatibility of `$protobufType` to `$modelType` changed!\n$info\nValidate the compatibility and annotate `@backward("$signature")` to remove this error!"""
        )

      case _ =>
      // everything as expected
    }
  }
}
