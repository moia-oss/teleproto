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

import io.moia.protos.teleproto.FormatImpl._

import scala.collection.compat._
import scala.reflect.macros.blackbox

@SuppressWarnings(Array("all"))
object MigrationImpl {

  def migration_impl[P: c.WeakTypeTag, Q: c.WeakTypeTag](c: blackbox.Context)(args: c.Expr[P => Any]*): c.Expr[Migration[P, Q]] = {
    import c.universe._
    val sourceType = weakTypeTag[P].tpe
    val targetType = weakTypeTag[Q].tpe
    c.Expr[Migration[P, Q]](traceCompiled(c)(compile(c)(sourceType, targetType, args.map(_.tree).toList)))
  }

  private def compile(
      c: blackbox.Context
  )(sourceType: c.universe.Type, targetType: c.universe.Type, args: List[c.universe.Tree]): c.universe.Tree =
    if (isProtobuf(c)(sourceType) && isProtobuf(c)(targetType))
      compileClassMigration(c)(sourceType, targetType, args)
    else if (isScalaPBEnumeration(c)(sourceType) && isScalaPBEnumeration(c)(targetType))
      compileEnumerationMigration(c)(sourceType, targetType, args)
    else
      c.abort(
        c.enclosingPosition,
        s"Cannot create a migration from `$sourceType` to `$targetType`. Just migrations between a) case classes b) sealed traits from enums are possible."
      )

  /**
    * Checks if source and target type are compatible in a way that the macro can assume a migration would make sense:
    * a) both are case classes (protobuf messages)
    * b) both are sealed traits from ScalaPB enums
    */
  private def isExpected(
      c: blackbox.Context
  )(sourceType: c.universe.Type, targetType: c.universe.Type): Boolean = {
    def classMigration = isProtobuf(c)(sourceType) && isProtobuf(c)(targetType)
    def enumMigration  = isScalaPBEnumeration(c)(sourceType) && isScalaPBEnumeration(c)(targetType)
    classMigration || enumMigration
  }

  /**
    * Checks if a migration from source to target type can be compiled without additional code.
    */
  private def isTrivial(
      c: blackbox.Context
  )(sourceType: c.universe.Type, targetType: c.universe.Type): Boolean =
    if (isProtobuf(c)(sourceType) && isProtobuf(c)(targetType))
      // case class migration is trivial if all parameters can be migrated automatically
      compareClassParameters(c)(sourceType, targetType).forall(_.isInstanceOf[Automatically[_, _]])
    else if (checkEnumerationTypes(c)(sourceType, targetType))
      // enum migration is trivial if there are no unmatched options from the source
      compareEnumerationOptions(c)(sourceType, targetType).isEmpty
    else
      false

  /**
    * Returns an expression that is a migration from source to target type.
    * Should be used for type pairs that fulfill the `isExpected` predicate.
    *
    * Check for an implicit migration from source to target type in the scope.
    * If not exists, try to generate a mapping (possible if types fulfill the `isTrivial` predicate)
    * Otherwise expect it anyway and let the Scala compiler complain about it.
    * That allows to generate as much as possible from the hierarchy and just complain about the missing parts.
    */
  private def implicitMigration(c: blackbox.Context)(sourceType: c.universe.Type, targetType: c.universe.Type): c.universe.Tree = {

    import c.universe._

    // look for an implicit migration
    val migrationType = appliedType(c.weakTypeTag[Migration[_, _]].tpe, sourceType, targetType)

    val existingMigration = c.inferImplicitValue(migrationType)

    if (existingMigration == EmptyTree && isTrivial(c)(sourceType, targetType))
      // compile the nested migration
      compile(c)(sourceType, targetType, Nil)
    else
      // "ask" for the implicit migration
      q"implicitly[$migrationType]"
  }

  private def compileClassMigration(
      c: blackbox.Context
  )(sourceType: c.universe.Type, targetType: c.universe.Type, args: List[c.universe.Tree]): c.universe.Tree = {

    import c.universe._

    // Analyze the source and target
    val paramMigrations = compareClassParameters(c)(sourceType, targetType)

    // For each required values the macro expects a function parameter as part of the var. args.

    val requiredMigrations = paramMigrations.collect { case required: Required[Type] => required }

    val signatureLengthInfo =
      if (requiredMigrations.size == 1)
        "A single migration function is required:"
      else
        s"${requiredMigrations.size} migration functions are required:"

    val signatureInfo =
      (signatureLengthInfo :: requiredMigrations.map(
        required => s"- ${required.name}: `$sourceType => ${required.typeSignature}` (${required.explanation})"
      )).mkString("\n")

    // Validate the signature of the function application

    if (requiredMigrations.size < args.length) {
      c.abort(args(requiredMigrations.size).pos, s"Too many migration functions! $signatureInfo")
    } else if (requiredMigrations.size > args.length) {
      val pos = args.lastOption.map(_.pos).getOrElse(c.enclosingPosition)
      c.abort(pos, s"Missing migration! $signatureInfo")
    } else {
      var hadError = false
      for ((required, index) <- requiredMigrations.zipWithIndex) {
        assert(required.argIndex == index, s"Software error in teleproto: ${required.argIndex} != $index!")

        // the corresponding argument must be a migration function
        val migrationFunction = c.typecheck(args(index))

        val migrationFunctionType = appliedType(c.weakTypeTag[_ => _].tpe, sourceType, required.typeSignature)

        if (!(migrationFunction.tpe <:< migrationFunctionType)) {
          hadError = true
          c.error(
            migrationFunction.pos,
            s"`${migrationFunction.tpe}` is not a valid migration function for `${required.name}` (`$sourceType => ${required.typeSignature}` is expected)."
          )
        }
      }
      if (hadError) {
        c.error(c.enclosingPosition, s"Invalid migration! $signatureInfo")
      }
    }

    // Construct the result

    val mapping = q"io.moia.protos.teleproto"

    // collect the expressions for the constructor of the target proto
    val passedExpressions =
      paramMigrations.map {
        case Automatically(tree: Tree, _) => tree
        case Required(_, _, index, _)     => q"""${args(index)}(pb)"""
      }

    // TargetProto(...)
    val cons = q"""${targetType.typeSymbol.companion.asTerm}.apply(..$passedExpressions)"""

    // Migration[SourceProto, TargetProto](pb: SourceProto => $cons)
    val result = q"""$mapping.Migration[$sourceType, $targetType]((pb: $sourceType) => $cons)"""

    // if @trace is placed, explain the migration in detail
    if (hasTraceAnnotation(c)) {
      val migrationInfo =
        paramMigrations.map {
          case Automatically(_, explanation) =>
            explanation
          case Required(param, typeSignature, idx, explanation) =>
            val migrationFunctionType = appliedType(c.weakTypeTag[_ => _].tpe, sourceType, typeSignature)
            s"$explanation => argument no. ${idx + 1} of type `$sourceType => $typeSignature`"
        }

      c.info(c.enclosingPosition, migrationInfo.mkString("\n"), force = true)
    }

    result
  }

  sealed trait ParamMigration[+TYPE, +TREE]

  // models a field in Q that can be automatically filled
  final case class Automatically[TYPE, TREE](expression: TREE, explanation: String) extends ParamMigration[TYPE, Nothing]

  // models a field in Q that requires a migration function
  final case class Required[TYPE](name: String, typeSignature: TYPE, argIndex: Int, explanation: String)
      extends ParamMigration[TYPE, Nothing]

  /**
    * Compares given source and target Protocol Buffers class types.
    * Returns the migration strategies for each param.
    *
    * If all returned parameter migrations are `Automatically` the whole migration is trivial.
    * That might include generating nested trivial migration for nested Protocol Buffers classes.
    */
  private def compareClassParameters(
      c: blackbox.Context
  )(sourceType: c.universe.Type, targetType: c.universe.Type): List[ParamMigration[c.universe.Type, c.universe.Tree]] = {
    import c.universe._

    val sourceCons = sourceType.member(termNames.CONSTRUCTOR).asMethod
    val targetCons = targetType.member(termNames.CONSTRUCTOR).asMethod

    // from the fields in P (source) create a map by name to the term symbol to select it in `pb.$field` where `pb` is the source
    val sourceParamsMap: Map[String, TermSymbol] =
      sourceCons.paramLists.headOption
        .getOrElse(sys.error("Scapegoat..."))
        .map(_.asTerm)
        .groupBy(_.name.decodedName.toString)
        .view
        .mapValues(_.headOption.getOrElse(sys.error("Scapegoat...")))
        .toMap

    // select the fields in Q as terms
    val targetParamsList = targetCons.paramLists.headOption.getOrElse(sys.error("Scapegoat...")).map(_.asTerm)

    // walks through all fields of Q and tries to match with fields from P.
    def compareParams(targetParams: List[TermSymbol], idx: Int): List[ParamMigration[Type, Tree]] =
      targetParams match {
        case Nil => Nil

        case targetParam :: rest =>
          val name = targetParam.name.decodedName.toString
          val to   = targetParam.typeSignature

          sourceParamsMap.get(name).map(_.typeSignature) match {

            // field in Q is new or renamed
            case None =>
              Required(name, to, idx, s"`$name: $to` is missing in `$sourceType` and must be specified.") ::
                compareParams(rest, idx + 1)

            // field exists in P and Q and the type in Q is equal or wider than the type in P
            case Some(from) if from <:< to =>
              val typeInfo = if (from =:= to) s"matching types `$from`" else s"$from matches $to"
              Automatically(q"pb.${targetParam.name}", s"`$targetParam` can be copied ($typeInfo).") ::
                compareParams(rest, idx)

            // field exists in P and Q and the type in Q has been made optional
            case Some(from) if to <:< weakTypeOf[Option[_]] && from <:< innerType(c)(to) =>
              Automatically(q"scala.Some(pb.${targetParam.name})", s"`$targetParam` can be copied wrapped with (`Some(...)`).") ::
                compareParams(rest, idx)

            // field exists and migrations between both types are generally possible
            case Some(from) if isExpected(c)(from, to) =>
              val migrationExpr = implicitMigration(c)(from, to)

              Automatically(q"$migrationExpr.migrate(pb.${targetParam.name})",
                            s"`$targetParam` can be copied with an implicit `Migration[$from, $to]`.") ::
                compareParams(rest, idx)

            // field exists and both are option/collection values for matching collection types and migrations between both inner types are generally possible
            case Some(from) if matchingContainers(c)(from, to) =>
              // migrate the inner types of both collections
              val migrationExpr = implicitMigration(c)(innerType(c)(from), innerType(c)(to))

              // just migrate a value if it's present
              Automatically(
                q"pb.${targetParam.name}.map(pbInner => $migrationExpr.migrate(pbInner))",
                s"`$targetParam` can be copied optionally with an implicit `Migration[$from, $to]`."
              ) ::
                compareParams(rest, idx)

            // field exists but types are not compatible
            case Some(from) =>
              // look for an implicit conversion
              val conversion = c.inferImplicitView(q"pb.${targetParam.name}", from, to)
              if (conversion.nonEmpty)
                Automatically(q"$conversion(pb.${targetParam.name})",
                              s"`$targetParam` can be copied with conversion from `$from` to `$to`.") ::
                  compareParams(rest, idx)
              else
                Required(name, to, idx, s"For`$targetParam` the type `$from` must be converted to `$to`.") ::
                  compareParams(rest, idx + 1)
          }
      }

    compareParams(targetParamsList, 0)
  }

  private def compileEnumerationMigration(
      c: blackbox.Context
  )(sourceType: c.universe.Type, targetType: c.universe.Type, args: List[c.universe.Tree]): c.universe.Tree = {

    import c.universe._

    val mapping = q"io.moia.protos.teleproto"

    // Enum migration is just possible if target has same or more options than the source type.
    // Then each value from the source type can be mapped to the target.

    val unmatchedSourceOptions = compareEnumerationOptions(c)(sourceType, targetType)

    if (unmatchedSourceOptions.isEmpty) {

      val sourceCompanion = sourceType.typeSymbol.companion
      val targetCompanion = targetType.typeSymbol.companion

      def options(tpe: c.universe.Type) = symbolsByName(c)(tpe.typeSymbol.asClass.knownDirectSubclasses.filter(_.isModuleClass))

      val sourceOptions = options(sourceType)
      val targetOptions = options(targetType)

      val cases =
        for {
          (optionName, sourceOption) <- sourceOptions.toList
          targetOption               <- targetOptions.get(optionName)
        } yield {
          (sourceOption.asClass.selfType.termSymbol, targetOption.asClass.selfType.termSymbol) // expected value to right hand side value
        }

      // construct a de-sugared pattern matching as a cascade of if elses
      def ifElses(cs: List[(Symbol, Symbol)]): Tree =
        cs match {
          case (expected, rhs) :: rest =>
            q"""if(pb == $expected) $rhs else ${ifElses(rest)}"""

          case Nil =>
            q"""
              pb match {
                case ${sourceCompanion.asTerm}.Unrecognized(other) =>
                  ${targetCompanion.asTerm}.Unrecognized(other)
                case _ =>
                  throw new IllegalStateException("teleproto contains a software bug compiling enums migrations: " + pb + " is not a matched value.")
              }
             """
        }

      q"""$mapping.Migration[$sourceType, $targetType]((pb: $sourceType) => ${ifElses(cases)})"""

    } else
      c.abort(
        c.enclosingPosition,
        s"A migration from `$sourceType` to `$targetType` is not possible: ${unmatchedSourceOptions.mkString("`", "`, `", "`")} from `$sourceType` not matched in `$targetType`."
      )
  }

  /**
    * Checks if both types are collections/options, target collection can be assigned from source collection and if
    * the inner types are expected to be migrated.
    *
    * If so a migration for the inner types could be expected and source value can be mapped using that migration.
    *
    * `sourceValue.map(innerValue => migration.migrate(innerValue))` would be a valid target value.
    */
  private def matchingContainers(c: blackbox.Context)(sourceType: c.universe.Type, targetType: c.universe.Type): Boolean = {
    import c.universe._

    def bothOptions         = sourceType <:< weakTypeOf[Option[_]] && targetType <:< weakTypeOf[Option[_]]
    def bothCollections     = sourceType <:< weakTypeOf[IterableOnce[_]] && targetType <:< weakTypeOf[IterableOnce[_]]
    def matchingCollections = sourceType.erasure <:< targetType.erasure
    def matchingInnerTypes  = isExpected(c)(innerType(c)(sourceType), innerType(c)(targetType))

    (bothOptions || (bothCollections && matchingCollections)) && matchingInnerTypes
  }

  /**
    * Returns the options in the source (enum sealed trait) type that are not matched in the target type.
    */
  private def compareEnumerationOptions(
      c: blackbox.Context
  )(sourceType: c.universe.Type, targetType: c.universe.Type): Set[c.universe.Name] = {

    def optionNames(tpe: c.universe.Type) = tpe.typeSymbol.asClass.knownDirectSubclasses.filter(_.isModuleClass).map(_.name.decodedName)

    val sourceOptions = optionNames(sourceType)
    val targetOptions = optionNames(targetType)

    sourceOptions -- targetOptions
  }
}
