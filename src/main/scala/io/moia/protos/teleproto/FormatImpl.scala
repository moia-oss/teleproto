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

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

import scala.collection.compat._
import scala.reflect.macros.blackbox

/**
  * Compiler functions shared between both, reader and writer macros
  */
@SuppressWarnings(Array("all"))
object FormatImpl {

  /**
    * A `sealed trait` is mapped to proto via a message that contains a `oneof` with name `value` (`ValueDefinition`).
    * The corresponding generated companion for the `oneof` is `Value` (`ValueModule`).
    */
  val ValueModule     = "Value"
  val ValueDefinition = "value"

  // Standard result is a tree expression and a compatibility analysis
  type Compiled[TYPE, TREE] = (TREE, Compatibility[TYPE])

  /**
    * Within a compiled hierarchy collects backward/forward compatibility issues.
    */
  case class Compatibility[TYPE](surplusParameters: Iterable[(TYPE, String)],
                                 defaultParameters: Iterable[(TYPE, String)],
                                 surplusClasses: Iterable[(TYPE, String)]) {

    def hasIssues: Boolean = surplusParameters.nonEmpty || defaultParameters.nonEmpty || surplusClasses.nonEmpty

    def merge(that: Compatibility[TYPE]): Compatibility[TYPE] =
      Compatibility(
        this.surplusParameters ++ that.surplusParameters,
        this.defaultParameters ++ that.defaultParameters,
        this.surplusClasses ++ that.surplusClasses
      )
  }

  object Compatibility {

    def full[TYPE]: Compatibility[TYPE] = Compatibility[TYPE](Nil, Nil, Nil)
  }

  /**
    * From type `S[T]` extracts `T`.
    */
  private[teleproto] def innerType(c: blackbox.Context)(from: c.universe.Type): c.universe.Type =
    from.typeArgs.headOption.getOrElse(sys.error("Scapegoat..."))

  /**
    * Fails if types are not a Protobuf case class and case class pair.
    */
  private[teleproto] def ensureValidTypes(c: blackbox.Context)(protobufType: c.Type, modelType: c.Type): Unit =
    if (!checkClassTypes(c)(protobufType, modelType)) {
      c.abort(c.enclosingPosition, s"`$protobufType` and `$modelType` have to be case classes with a single parameter list!")
    }

  private[teleproto] def checkClassTypes(c: blackbox.Context)(protobufType: c.Type, modelType: c.Type): Boolean =
    isProtobuf(c)(protobufType) && isSimpleCaseClass(c)(modelType)

  private[teleproto] def checkTraitTypes(c: blackbox.Context)(protobufType: c.Type, modelType: c.Type): Boolean =
    isSealedTrait(c)(protobufType) && isSealedTrait(c)(modelType)

  private[teleproto] def checkHierarchyTypes(c: blackbox.Context)(protobufType: c.Type, modelType: c.Type): Boolean = {
    import c.universe._
    isSealedTrait(c)(modelType) && {
      val protoClass = protobufType.typeSymbol.asClass
      protoClass.isCaseClass && {
        // a case class with a single field named `value`
        val cons   = protobufType.member(termNames.CONSTRUCTOR).asMethod
        val params = cons.paramLists.flatten
        params.lengthCompare(1) == 0 && params.headOption.getOrElse(sys.error("Scapegoat...")).name.decodedName.toString == ValueDefinition
      }
    }
  }

  /**
    * A ScalaPB enumeration can be mapped to a detached sealed trait with corresponding case objects and vice versa.
    */
  private[teleproto] def checkEnumerationTypes(c: blackbox.Context)(protobufType: c.Type, modelType: c.Type): Boolean =
    isScalaPBEnumeration(c)(protobufType) && isSealedTrait(c)(modelType)

  private[teleproto] def isProtobuf(c: blackbox.Context)(tpe: c.Type): Boolean =
    isSimpleCaseClass(c)(tpe) // && tpe <:< typeOf[GeneratedMessage]

  private[teleproto] def isSimpleCaseClass(c: blackbox.Context)(tpe: c.Type): Boolean = {
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

  private[teleproto] def hasTraceAnnotation(c: blackbox.Context): Boolean = {
    import c.universe._
    c.internal.enclosingOwner.annotations.exists(_.tree.tpe.typeSymbol == typeOf[trace].typeSymbol)
  }

  /**
    * If the enclosing owner (the `def` or `val` that invokes the macro) got the annotation `@trace` then send the given
    * (compiled) tree as info message to the compiler shell.
    */
  private[teleproto] def traceCompiled(c: blackbox.Context)(tree: c.universe.Tree): c.universe.Tree = {
    if (hasTraceAnnotation(c)) {
      c.info(c.enclosingPosition, tree.toString, force = true)
    }
    tree
  }

  private[teleproto] def symbolsByName(c: blackbox.Context)(symbols: Iterable[c.universe.Symbol]): Map[c.universe.Name, c.universe.Symbol] =
    symbols.groupBy(_.name.decodedName).view.mapValues(_.headOption.getOrElse(sys.error("Scapegoat..."))).toMap

  /**
    * Uses lower case names without underscores (assuming clashes are already handled by ScalaPB)
    */
  private[teleproto] def symbolsByTolerantName(c: blackbox.Context)(symbols: Iterable[c.universe.Symbol]): Map[String, c.universe.Symbol] =
    for ((name, symbol) <- symbolsByName(c)(symbols))
      yield (name.toString.toLowerCase.replace("_", ""), symbol)

  private[teleproto] def isSealedTrait(c: blackbox.Context)(tpe: c.Type): Boolean = {
    import c.universe._
    tpe.typeSymbol match {
      case cs: ClassSymbol => cs.isSealed && cs.isAbstract
      case _               => false
    }
  }

  private[teleproto] def isScalaPBEnumeration(c: blackbox.Context)(tpe: c.Type): Boolean = {
    import c.universe._
    isSealedTrait(c)(tpe) && tpe.member(TypeName("EnumType")) != NoSymbol
  }

  /**
    * To map Scala's sealed traits to Protocol Buffers we use a message object with the name of the sealed trait.
    * It contains a single oneof named `value` that maps to the case classes of the sealed trait.
    *
    * This method collects all to Protocol Buffers case classes declared in `$traitName.Value`.
    */
  private[teleproto] def protoHierarchyCaseClasses(c: blackbox.Context)(tpe: c.universe.Type): Iterable[c.universe.ClassSymbol] = {
    import c.universe._

    val sym       = tpe.typeSymbol.asClass // e.g. case class Condition (generated proto)
    val companion = sym.companion          // e.g. object Condition (generated proto)

    val valueModules = companion.typeSignatureIn(tpe).decls.filter(sym => sym.isModule && sym.name.decodedName.toString == ValueModule)

    val valueModule = // e.g. object Condition { object Value }
      valueModules.headOption.getOrElse(
        c.abort(c.enclosingPosition, s"Could not find `object Value` in `$tpe`: ${companion.typeSignatureIn(tpe).decls}")
      )

    valueModule.typeSignatureIn(tpe).decls.collect {
      case cs: ClassSymbol if cs.isCaseClass => cs
      // e.g. e.g. object Condition { object Value { case class TimeOfDayWindow } }
    }
  }

  private[teleproto] def implicitAvailable(
      c: blackbox.Context
  )(genericType: c.universe.Type, from: c.universe.Type, to: c.universe.Type): Boolean = {
    import c.universe._
    val parametrizedType = appliedType(genericType, List(from, to))
    val implicitValue    = c.inferImplicitValue(parametrizedType)
    implicitValue != EmptyTree
  }

  private[teleproto] def compatibilityInfo(c: blackbox.Context)(compatibility: Compatibility[_]): String = {
    val surplusInfo =
      for {
        (tpe, tpeAndNames) <- compatibility.surplusParameters.groupBy(_._1)
      } yield s"${tpeAndNames.map(e => s"`${e._2}`").mkString(", ")} from `$tpe` will not be used."

    val surplusValues =
      for {
        (tpe, tpeAndNames) <- compatibility.surplusClasses.groupBy(_._1)
      } yield s"${tpeAndNames.map(e => s"`${e._2}`").mkString(", ")} in `$tpe` will never be matched."

    val defaultInfo =
      for {
        (tpe, tpeAndNames) <- compatibility.defaultParameters.groupBy(_._1)
      } yield s"${tpeAndNames.map(e => s"`${e._2}`").mkString(", ")} will get the default in `$tpe`."

    val info = List(surplusInfo, surplusValues, defaultInfo).flatten.mkString("\n")
    info
  }

  /**
    * Always renders the same hash for a similar incompatibility.
    */
  private[teleproto] def compatibilitySignature(c: blackbox.Context)(compatibility: Compatibility[_]): String =
    if (compatibility.hasIssues) {
      val baos         = new ByteArrayOutputStream()
      val incompatible = compatibility.surplusParameters ++ compatibility.defaultParameters ++ compatibility.surplusClasses
      for ((tpe, name) <- incompatible.toSeq.sortBy(_._2).sortBy(_._1.toString)) {
        baos.write(tpe.toString.getBytes(StandardCharsets.UTF_8))
        baos.write(name.getBytes(StandardCharsets.UTF_8))
      }
      MessageDigest
        .getInstance("MD5")
        .digest(baos.toByteArray)
        .map(0xFF & _)
        .map { "%02x".format(_) }
        .take(3) // <- 6 characters
        .mkString
    } else {
      ""
    }

  /**
    * Extracts literal signature value of @backward("signature") or @forward("signature").
    */
  private[teleproto] def compatibilityAnnotation(c: blackbox.Context)(tpe: c.universe.Type): Option[String] = {
    import c.universe._
    val maybeAnnotation = c.internal.enclosingOwner.annotations.find(_.tree.tpe.typeSymbol == tpe.typeSymbol)
    maybeAnnotation.flatMap(
      annotation =>
        annotation.tree match {
          case Apply(_, List(Literal(Constant(signature: String)))) =>
            Some(signature)
          case _ =>
            None
      }
    )
  }
}
