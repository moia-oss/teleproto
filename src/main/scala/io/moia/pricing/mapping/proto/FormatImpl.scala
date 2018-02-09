package io.moia.pricing.mapping.proto

import scala.reflect.macros.whitebox

/**
  * Compiler functions shared between both, reader and writer macros
  */
object FormatImpl {

  /**
    * A `sealed trait` is mapped to proto via a message that contains a `oneof` with name `value` (`ValueDefinition`).
    * The corresponding generated companion for the `oneof` is `Value` (`ValueModule`).
    */
  val ValueModule = "Value"
  val ValueDefinition = "value"

  /**
    * From type `S[T]` extracts `T`.
    */
  private[proto] def innerType(c: whitebox.Context)(
      from: c.universe.Type): c.universe.Type =
    from.typeArgs.headOption.getOrElse(sys.error("Scapegoat..."))

  /**
    * Fails if types are not a Protobuf case class and case class pair.
    */
  private[proto] def ensureValidTypes(
      c: whitebox.Context)(protobufType: c.Type, modelType: c.Type): Unit = {
    if (!checkClassTypes(c)(protobufType, modelType)) {
      c.abort(
        c.enclosingPosition,
        s"`$protobufType` and `$modelType` have to be case classes with a single parameter list!")
    }
  }

  private[proto] def checkClassTypes(
      c: whitebox.Context)(protobufType: c.Type, modelType: c.Type): Boolean =
    isProtobuf(c)(protobufType) && isSimpleCaseClass(c)(modelType)

  private[proto] def checkTraitTypes(
      c: whitebox.Context)(protobufType: c.Type, modelType: c.Type): Boolean =
    isSealedTrait(c)(protobufType) && isSealedTrait(c)(modelType)

  private[proto] def checkHierarchyTypes(
      c: whitebox.Context)(protobufType: c.Type, modelType: c.Type): Boolean = {
    import c.universe._
    isSealedTrait(c)(modelType) && {
      val protoClass = protobufType.typeSymbol.asClass
      protoClass.isCaseClass && {
        // a case class with a single field named `value`
        val cons = protobufType.member(termNames.CONSTRUCTOR).asMethod
        val params = cons.paramLists.flatten
        params.lengthCompare(1) == 0 && params.headOption
          .getOrElse(sys.error("Scapegoat..."))
          .name
          .decodedName
          .toString == ValueDefinition
      }
    }
  }

  private[proto] def isProtobuf(c: whitebox.Context)(tpe: c.Type): Boolean =
    isSimpleCaseClass(c)(tpe) // && tpe <:< typeOf[GeneratedMessage]

  private[proto] def isSimpleCaseClass(c: whitebox.Context)(
      tpe: c.Type): Boolean = {
    import c.universe._
    tpe.typeSymbol match {
      case cs: ClassSymbol =>
        cs.isCaseClass && {
          val cons = tpe.member(termNames.CONSTRUCTOR).asMethod
          cons.paramLists.lengthCompare(1) == 0 // has a single parameter list
        }
      case _ => false
    }
  }

  /**
    * If the enclosing owner (the `def` or `val` that invokes the macro) got the annotation `@trace` then send the given
    * (compiled) tree as info message to the compiler shell.
    */
  private[proto] def traceCompiled(c: whitebox.Context)(
      tree: c.universe.Tree): c.universe.Tree = {
    import c.universe._
    if (c.internal.enclosingOwner.annotations
          .exists(_.tree.tpe.typeSymbol == typeOf[trace].typeSymbol)) {
      c.info(c.enclosingPosition, tree.toString, force = true)
    }
    tree
  }

  private[proto] def symbolsByName(c: whitebox.Context)(
      symbols: Iterable[c.universe.Symbol]) =
    symbols
      .groupBy(_.name.decodedName)
      .mapValues(_.headOption.getOrElse(sys.error("Scapegoat...")))

  private[proto] def isSealedTrait(c: whitebox.Context)(
      tpe: c.Type): Boolean = {
    import c.universe._
    tpe.typeSymbol match {
      case cs: ClassSymbol => cs.isSealed && cs.isAbstract
      case _               => false
    }
  }

  /**
    * To map Scala's sealed traits to Protocol Buffers we use a message object with the name of the sealed trait.
    * It contains a single oneof named `value` that maps to the case classes of the sealed trait.
    *
    * This method collects all to Protocol Buffers case classes declared in `$traitName.Value`.
    */
  private[proto] def protoHierarchyCaseClasses(c: whitebox.Context)(
      tpe: c.universe.Type): Iterable[c.universe.ClassSymbol] = {
    import c.universe._

    val sym = tpe.typeSymbol.asClass // e.g. case class Condition (generated proto)
    val companion = sym.companion // e.g. object Condition (generated proto)

    val valueModules = companion
      .typeSignatureIn(tpe)
      .decls
      .filter(sym =>
        sym.isModule && sym.name.decodedName.toString == ValueModule)

    val valueModule = // e.g. object Condition { object Value }
      valueModules.headOption.getOrElse(c.abort(
        c.enclosingPosition,
        s"Could not find `object Value` in `$tpe`: ${companion.typeSignatureIn(tpe).decls}"))

    valueModule.typeSignatureIn(tpe).decls.collect {
      case cs: ClassSymbol if cs.isCaseClass => cs
      // e.g. e.g. object Condition { object Value { case class TimeOfDayWindow } }
    }
  }

  private[proto] def implicitAvailable(c: whitebox.Context)(
      genericType: c.universe.Type,
      from: c.universe.Type,
      to: c.universe.Type): Boolean = {
    import c.universe._
    val parametrizedType = appliedType(genericType, List(from, to))
    val implicitValue = c.inferImplicitValue(parametrizedType)
    implicitValue != EmptyTree
  }
}
