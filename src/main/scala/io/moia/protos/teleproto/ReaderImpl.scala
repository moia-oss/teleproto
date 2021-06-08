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
class ReaderImpl(val c: blackbox.Context) extends FormatImpl {
  import c.universe._

  def reader_impl[P: WeakTypeTag, M: WeakTypeTag]: Expr[Reader[P, M]] =
    c.Expr(compile[P, M])

  private def compile[P: WeakTypeTag, M: WeakTypeTag]: Tree = {
    val protobufType = weakTypeTag[P].tpe
    val modelType    = weakTypeTag[M].tpe

    if (checkClassTypes(protobufType, modelType)) {
      val (result, compatibility) = compileClassMapping(protobufType, modelType)
      warnBackwardCompatible(protobufType, modelType, compatibility)
      traceCompiled(result)
    } else if (checkEnumerationTypes(protobufType, modelType)) {
      val (result, compatibility) = compileEnumerationMapping(protobufType, modelType)
      warnBackwardCompatible(protobufType, modelType, compatibility)
      traceCompiled(result)
    } else if (checkHierarchyTypes(protobufType, modelType)) {
      val (result, compatibility) = compileTraitMapping(protobufType, modelType)
      warnBackwardCompatible(protobufType, modelType, compatibility)
      traceCompiled(result)
    } else {
      abort(
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
  private def withImplicitReader(protobufType: Type, modelType: Type)(compileInner: Tree => Tree): Compiled = {
    // look for an implicit reader
    val readerType     = appliedType(c.weakTypeTag[Reader[_, _]].tpe, protobufType, modelType)
    val existingReader = c.inferImplicitValue(readerType)

    // "ask" for the implicit reader or use the found one
    def ask: Compiled = (compileInner(q"implicitly[$readerType]"), Compatibility.full)

    if (existingReader == EmptyTree)
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
  private def compileClassMapping(protobufType: Type, modelType: Type): Compiled = {
    val modelCompanion = modelType.typeSymbol.companion

    val protobufCons = protobufType.member(termNames.CONSTRUCTOR).asMethod
    val modelCons    = modelType.member(termNames.CONSTRUCTOR).asMethod

    val protobufParams = protobufCons.paramLists.headOption.getOrElse(Nil).map(_.asTerm)
    val modelParams    = modelCons.paramLists.headOption.getOrElse(Nil).map(_.asTerm)

    val mapping = q"io.moia.protos.teleproto"

    /*
     * For each parameter creates a value assignment with the name of the parameter, e.g.
     * `val targetParameter = transformationExpression`
     */
    def valueDefinitions(parameters: List[(TermSymbol, MatchingParam)]): List[Compiled] = {
      parameters.flatMap {
        case (termSymbol, matchingParam) =>
          val path = q""""/"+${termSymbol.name.decodedName.toString}"""

          def assign(compiled: Compiled): Compiled =
            (q"val ${termSymbol.name} = ${compiled._1}", compiled._2)

          matchingParam match {
            case TransformParam(from, to) if from <:< to =>
              Some(q"val ${termSymbol.name} = protobuf.${termSymbol.name}" -> Compatibility.full)
            case TransformParam(from, to) if from <:< weakTypeOf[Option[_]] && !(to <:< weakTypeOf[Option[_]]) =>
              val innerFrom = innerType(from)
              Some(assign(withImplicitReader(innerFrom, to) { readerExpr =>
                q"""$mapping.Reader.required[$innerFrom, $to](protobuf.${termSymbol.name}, $path)($readerExpr)"""
              }))
            case TransformParam(from, to) if from <:< weakTypeOf[Option[_]] && (to <:< weakTypeOf[Option[_]]) =>
              val innerFrom = innerType(from)
              val innerTo   = innerType(to)
              Some(assign(withImplicitReader(innerFrom, innerTo) { readerExpr =>
                q"""$mapping.Reader.optional[$innerFrom, $innerTo](protobuf.${termSymbol.name}, $path)($readerExpr)"""
              }))

            case TransformParam(from, to) if from <:< weakTypeOf[Seq[_]] && to <:< weakTypeOf[Iterable[_]] =>
              val innerFrom = innerType(from)
              val innerTo   = innerType(to)
              Some(assign(withImplicitReader(innerFrom, innerTo) { readerExpr =>
                // sequence also needs an implicit collection generator which must be looked up since the implicit for the value reader is passed explicitly
                val canBuildFrom = VersionSpecific.lookupFactory(c)(innerTo, to)
                q"""$mapping.Reader.sequence[${to.typeConstructor}, $innerFrom, $innerTo](protobuf.${termSymbol.name}, $path)($canBuildFrom, $readerExpr)"""
              }))
            case TransformParam(from, to) =>
              Some(assign(withImplicitReader(from, to) { readerExpr =>
                q"""$mapping.Reader.transform[$from, $to](protobuf.${termSymbol.name}, $path)($readerExpr)"""
              }))
            case ExplicitDefaultParam(expr) =>
              Some(q"val ${termSymbol.name} = $expr" -> Compatibility.full)
            case SkippedDefaultParam =>
              Option.empty[Compiled]
          }
      }
    }

    /*
     * Constructs an expression `PbFailure.combine(convertedParameters..)` for all transformed parameters (could fail).
     */
    def forLoop(parameters: List[(TermSymbol, MatchingParam)], cons: Tree): Tree =
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
            case SkippedDefaultParam =>
              restTransformed
            case _ =>
              q"""${termSymbol.name}.flatMap { case ${termSymbol.name} => $restTransformed }"""
          }
      }

    /*
     * Constructs an expression `PbFailure(...)` that contains all errors for all failed parameter transformations.
     */
    def combineErrors(parameters: List[(TermSymbol, MatchingParam)]): Tree = {
      val convertedValues =
        parameters.flatMap {
          case (_, TransformParam(from, to)) if from <:< to =>
            None
          case (_, ExplicitDefaultParam(_)) =>
            None
          case (_, SkippedDefaultParam) =>
            None
          case (termSymbol, _) =>
            Some(termSymbol.name)
        }

      q"$mapping.PbFailure.combine(..$convertedValues)"
    }

    def transformation(parameters: Seq[MatchingParam], ownCompatibility: Compatibility): Compiled = {

      val matchedParameters = modelParams.zip(parameters)

      val passedArgumentNames =
        modelParams
          .zip(parameters)
          .flatMap(_ match {
            // unmatched parameters with default values are not passed: they get their defaults
            case (_, SkippedDefaultParam) => None
            case (param, _)               => Some(q"${param.name} = ${param.name}")
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

    compareCaseAccessors(modelType, protobufParams, modelParams) match {
      case Incompatible(missingParameters) =>
        abort(
          s"`$protobufType` and $modelType are not compatible (${missingParameters.map(name => s"`$name`").mkString(", ")} missing in `$protobufType`)!"
        )

      case Compatible(parameters) =>
        transformation(parameters, Compatibility.full)

      case BackwardCompatible(surplusParameters, defaultParameters, parameters) =>
        transformation(parameters, Compatibility(surplusParameters.map(protobufType -> _), defaultParameters.map(modelType -> _), Nil))
    }
  }

  private sealed trait Matching

  /* Same arity */
  private case class Compatible(parameters: Seq[MatchingParam]) extends Matching

  /* Missing names on Model side and no missing names Protobuf side that are not optional or without default on model side */
  private case class BackwardCompatible(
      surplusParameters: Iterable[String],
      defaultParameters: Iterable[String],
      parameters: Seq[MatchingParam]
  ) extends Matching

  /* All remaining */
  private case class Incompatible(missingParameters: Iterable[String]) extends Matching

  private sealed trait MatchingParam
  private case class TransformParam(from: Type, to: Type) extends MatchingParam
  private case class ExplicitDefaultParam(value: Tree)    extends MatchingParam
  private case object SkippedDefaultParam                 extends MatchingParam

  private def compareCaseAccessors(
      modelType: Type,
      protobufParams: List[TermSymbol],
      modelParams: List[TermSymbol]
  ): Matching = {
    val protobufByName       = symbolsByName(protobufParams)
    val modelByName          = symbolsByName(modelParams)
    val surplusProtobufNames = protobufByName.keySet diff modelByName.keySet

    val matchedParams: List[Option[MatchingParam]] =
      for (modelParam <- modelParams) yield {
        // resolve type parameters to their actual bindings
        val targetType = modelParam.typeSignature.asSeenFrom(modelType, modelType.typeSymbol)
        protobufByName.get(modelParam.name) match {
          case Some(protobufParam) =>
            Some(TransformParam(protobufParam.typeSignature, targetType))
          case None if modelParam.isParamWithDefault =>
            Some(SkippedDefaultParam)
          case None if targetType.typeSymbol == definitions.OptionClass =>
            Some(ExplicitDefaultParam(reify(None).tree))
          case None =>
            None
        }
      }

    val namedMatchedParams = modelParams.map(_.name).zip(matchedParams)

    val missingModelParamNames = namedMatchedParams.collect {
      case (name, None) => name
    }

    val matchingModelParams = namedMatchedParams.collect {
      case (name, Some(matchingParam)) => (name, matchingParam)
    }

    val backwardCompatibleModelParamNames = matchingModelParams.collect {
      case (name, SkippedDefaultParam)     => name
      case (name, ExplicitDefaultParam(_)) => name
    }

    if (missingModelParamNames.nonEmpty) {
      Incompatible(missingModelParamNames.map(_.decodedName.toString))
    } else if (surplusProtobufNames.nonEmpty || backwardCompatibleModelParamNames.nonEmpty) {
      BackwardCompatible(
        surplusProtobufNames.map(_.decodedName.toString),
        backwardCompatibleModelParamNames.map(_.decodedName.toString),
        matchingModelParams.map(_._2)
      )
    } else {
      Compatible(matchingModelParams.map(_._2))
    }
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
  private def compileTraitMapping(protobufType: Type, modelType: Type): Compiled = {
    val protobufClass      = protobufType.typeSymbol.asClass
    val modelClass         = modelType.typeSymbol.asClass
    val protobufSubClasses = symbolsByName(protobufClass.knownDirectSubclasses)
    val modelSubclasses    = symbolsByName(modelClass.knownDirectSubclasses)

    if (protobufSubClasses.isEmpty)
      error(s"No subclasses of sealed trait `${protobufClass.fullName}` found.")

    val unmatchedProtobufClasses = protobufSubClasses.keySet - EmptyOneOf diff modelSubclasses.keySet

    if (unmatchedProtobufClasses.nonEmpty)
      error(s"`${modelClass.name}` does not match ${showNames(unmatchedProtobufClasses)} subclasses of `${protobufClass.name}`.")

    val surplusModelClasses = modelSubclasses.keySet diff protobufSubClasses.keySet
    val ownCompatibility    = Compatibility(Nil, Nil, surplusModelClasses.map(name => (modelType, name.toString)))
    val valueMethod         = protobufType.member(ValueMethod).asMethod

    val subTypes = for {
      (className, protobufSubclass) <- protobufSubClasses.toSeq
      modelSubclass                 <- modelSubclasses.get(className)
    } yield {
      val name = className.toString
      val path = s"/${name.head.toLower}${name.tail}"
      withImplicitReader(valueMethod.infoIn(classTypeOf(protobufSubclass)), classTypeOf(modelSubclass)) { reader =>
        val value = c.freshName(ValueMethod)
        cq"${protobufSubclass.companion}($value) => $reader.read($value).withPathPrefix($path)"
      }
    }

    val (cases, compatibility) = subTypes.unzip
    val emptyCase = for (protobufClass <- protobufSubClasses.get(EmptyOneOf) if !modelSubclasses.contains(EmptyOneOf))
      yield cq"""_: ${objectReferenceTo(protobufClass)}.type => $mapping.PbFailure("Oneof field is empty!")"""

    val reader = c.freshName(TermName("reader"))
    val result = q"""{
      val $reader: $mapping.Reader[$protobufType, $modelType] = {
        case ..$cases
        case ..${emptyCase.toList}
      }
      $reader
    }"""

    (result, compatibility.fold(ownCompatibility)(_ merge _))
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
  private def compileEnumerationMapping(protobufType: Type, modelType: Type): Compiled = {
    val protobufClass     = protobufType.typeSymbol.asClass
    val protobufCompanion = protobufClass.companion
    val modelClass        = modelType.typeSymbol.asClass
    val protobufOptions   = symbolsByTolerantName(protobufClass.knownDirectSubclasses.filter(_.isModuleClass), protobufClass)
    val modelOptions      = symbolsByTolerantName(modelClass.knownDirectSubclasses.filter(_.isModuleClass), modelClass)

    val unmatchedProtobufOptions = protobufOptions.toList.collect {
      case (name, symbol) if !modelOptions.contains(name) && name != InvalidEnum => symbol.name.decodedName
    }

    if (unmatchedProtobufOptions.nonEmpty)
      error(s"The options in `${modelClass.fullName}` do not match ${showNames(unmatchedProtobufOptions)} in `${protobufClass.fullName}`.")

    val surplusModelOptions = modelOptions.toList.collect {
      case (name, symbol) if !protobufOptions.contains(name) => symbol.name.decodedName
    }

    val compatibility = Compatibility(Nil, Nil, surplusModelOptions.map(name => (modelType, name.toString)))

    val cases = for {
      (optionName, modelOption) <- modelOptions.toList
      protobufOption            <- protobufOptions.get(optionName)
    } yield cq"_: ${objectReferenceTo(protobufOption)}.type => $mapping.PbSuccess(${objectReferenceTo(modelOption)})"

    val invalidCase = for (protobufOption <- protobufOptions.get(InvalidEnum) if !modelOptions.contains(InvalidEnum)) yield {
      val reference = objectReferenceTo(protobufOption)
      cq"""_: $reference.type => $mapping.PbFailure(s"Enumeration value $${$reference} is invalid!")"""
    }

    val reader = c.freshName(TermName("reader"))
    val other  = c.freshName(TermName("other"))
    val result = q"""{
      val $reader: $mapping.Reader[$protobufType, $modelType] = {
        case ..$cases
        case ..${invalidCase.toList}
        case ${protobufCompanion.asTerm}.Unrecognized($other) =>
          $mapping.PbFailure(s"Enumeration value $${$other} is unrecognized!")
      }
      $reader
    }"""

    (result, compatibility)
  }

  private[teleproto] def warnBackwardCompatible(protobufType: Type, modelType: Type, compatibility: Compatibility): Unit = {
    def info           = compatibilityInfo(compatibility)
    lazy val signature = compatibilitySignature(compatibility)

    compatibilityAnnotation(typeOf[backward]) match {
      case None if compatibility.hasIssues =>
        warn(
          s"""`$protobufType` is just backward compatible to `$modelType`:\n$info\nAnnotate `@backward("$signature")` to remove this warning!"""
        )

      case Some(_) if !compatibility.hasIssues =>
        error(s"""`$protobufType` is compatible to `$modelType`.\nAnnotation `@backward` should be removed!""")

      case Some(actual) if actual != signature =>
        error(
          s"""Backward compatibility of `$protobufType` to `$modelType` changed!\n$info\nValidate the compatibility and annotate `@backward("$signature")` to remove this error!"""
        )

      case _ =>
      // everything as expected
    }
  }
}
