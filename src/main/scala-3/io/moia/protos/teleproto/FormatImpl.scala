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
import scalapb.{GeneratedEnum, GeneratedOneof}
import scala.quoted.*

/** Compiler functions shared between both, reader and writer macros */
trait FormatImpl {
  /** A `oneof` proto definition is mapped to a `sealed trait` in Scala. Each variant of the `oneof` definition is mapped to a `case class`
    * with exactly one field `value` that contains a reference to the `case class` mapping of the corresponding `message` proto definition.
    */
  val ValueMethod: TermName = TermName("value")

  /** An enum case with this name can remain unmapped in [[Reader]]. It is assumed that this is the default case. */
  val InvalidEnum = "invalid"

  /** OneOf variant that encodes an empty field. */
  val EmptyOneOf: TypeName = TypeName("Empty")

  // Standard result is a tree expression and a compatibility analysis
  type Compiled    = (Tree, Compatibility)
  type CompatIssue = (Type, String)

  /** Within a compiled hierarchy collects backward/forward compatibility issues. */
  case class Compatibility(
      surplusParameters: Iterable[CompatIssue],
      defaultParameters: Iterable[CompatIssue],
      surplusClasses: Iterable[CompatIssue]
  ) {

    // scalaPB 0.10 introduces a field called unknownFields for every proto by default.
    // See: https://github.com/scalapb/ScalaPB/issues/778 for alternatives.
    // The application can either choose to map or ignore this property.
    // A simple workaround for the moment is to ignore this property completely.
    private def unknownField(issue: CompatIssue): Boolean             = issue._2 == "unknownFields"
    private def unknownFields(issues: Iterable[CompatIssue]): Boolean = issues.forall(unknownField)

    def hasIssues: Boolean =
      !(unknownFields(surplusParameters) && unknownFields(defaultParameters) && unknownFields(surplusClasses))

    def merge(that: Compatibility): Compatibility =
      Compatibility(
        this.surplusParameters ++ that.surplusParameters,
        this.defaultParameters ++ that.defaultParameters,
        this.surplusClasses ++ that.surplusClasses
      )
  }

  object Compatibility {
    val full: Compatibility = Compatibility(Nil, Nil, Nil)
  }

  /** From type `S[T]` extracts `T`. */
  private[teleproto] def innerType[T](from: Type[T]): Type[T] =
    from.typeArgs.headOption.getOrElse(abort(s"Type $from does not have type arguments"))

  /** Fails if types are not a Protobuf case class and case class pair. */
  private[teleproto] def ensureValidTypes[P, M](protobufType: Type[P], modelType: Type[M])(using Quotes): Unit =
    if (!checkClassTypes(protobufType, modelType)) {
      import scala.compiletime.error
      error(s"`$protobufType` and `$modelType` have to be case classes with a single parameter list!")
    }

  private[teleproto] def checkClassTypes[P, M](protobufType: Type[P], modelType: Type[M])(using Quotes): Boolean =
    isProtobuf(protobufType) && isSimpleCaseClass(modelType)

  private[teleproto] def checkTraitTypes[P: Type, M: Type](using Quotes): Boolean =
    isSealedTrait[P] && isSealedTrait[M]

  /** A sealed trait with that is a subtype of [[GeneratedOneof]]. */
  private[teleproto] def checkHierarchyTypes[P: Type, M: Type](using Quotes): Boolean =
    import quotes.reflect.*

    isSealedTrait[M] && isSealedTrait[P] && TypeRepr.of[P] <:< TypeRepr.of[GeneratedOneof]

  /** A ScalaPB enumeration can be mapped to a detached sealed trait with corresponding case objects and vice versa. */
  private[teleproto] def checkEnumerationTypes[P: Type, M: Type](using Quotes): Boolean =
    isScalaPBEnumeration[P] && isSealedTrait[M]

  private[teleproto] def isProtobuf[P](tpe: Type[P])(using Quotes): Boolean =
    isSimpleCaseClass(tpe) // && tpe <:< typeOf[GeneratedMessage]

  private[teleproto] def isSimpleCaseClass[T](tpe: Type[T])(using Quotes): Boolean = {
    import quotes.reflect.*

    val tpeSym = TypeTree.of[T].symbol
    tpeSym.flags.is(Flags.Case) && {
      val cons = tpe.member(termNames.CONSTRUCTOR).asMethod
      cons.paramLists.lengthCompare(1) == 0 // has a single parameter list
    }
  }

  private[teleproto] def hasTraceAnnotation: Boolean =
    c.internal.enclosingOwner.annotations.exists(_.tree.tpe.typeSymbol == symbolOf[trace])

  /** Uses lower case names without underscores (assuming clashes are already handled by ScalaPB). */
  private[teleproto] def symbolsByTolerantName(symbols: Iterable[Symbol]): Map[String, Symbol] =
    for ((name, symbol) <- symbolsByName(symbols)) yield name.toString.toLowerCase.replace("_", "") -> symbol

  /** Uses lower case names without underscores (assuming clashes are already handled by ScalaPB). Strips the parent name from the the
    * beginning of each symbol name.
    */
  private[teleproto] def symbolsByTolerantName(symbols: Iterable[Symbol], parent: Symbol): Map[String, Symbol] = {
    val prefix = parent.name.toLowerCase
    for ((name, symbol) <- symbolsByTolerantName(symbols)) yield name.stripPrefix(prefix) -> symbol
  }

  private[teleproto] def showNames(symbols: Iterable[Name]): String =
    symbols.iterator.map(name => s"`$name`").mkString(", ")

  private[teleproto] def isSealedTrait[T: Type](using Quotes): Boolean = {
    import quotes.reflect.*

    TypeTree.of[T].symbol.flags.is(Flags.Trait & Flags.Sealed)
  }

  private[teleproto] def isScalaPBEnumeration[P: Type](using Quotes): Boolean = {
    import quotes.reflect.*

    isSealedTrait[P] && TypeRepr.of[P] <:< TypeRepr.of[GeneratedEnum]
  }

  private[teleproto] def classTypeOf[T](classSymbol: Symbol): Type[T] =
    classSymbol.asClass.selfType

  private[teleproto] def objectReferenceTo(objectClass: Symbol): Symbol =
    classTypeOf(objectClass).termSymbol

  private[teleproto] def implicitAvailable[T, A, B](genericType: Type[T], from: Type[A], to: Type[B]): Boolean = {
    val parametrizedType = appliedType(genericType, List(from, to))
    val implicitValue    = c.inferImplicitValue(parametrizedType)
    implicitValue != EmptyTree
  }

  private[teleproto] def compatibilityInfo(compatibility: Compatibility): String = {
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

  /** Always renders the same hash for a similar incompatibility. */
  private[teleproto] def compatibilitySignature(compatibility: Compatibility): String =
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
        .map(0xff & _)
        .map { "%02x".format(_) }
        .take(3) // <- 6 characters
        .mkString
    } else ""

  /** Extracts literal signature value of @backward("signature") or @forward("signature"). */
  private[teleproto] def compatibilityAnnotation[T](tpe: Type[T]): Option[String] =
    c.internal.enclosingOwner.annotations.find(_.tree.tpe.typeSymbol == tpe.typeSymbol).flatMap { annotation =>
      annotation.tree match {
        case Apply(_, List(Literal(Constant(signature: String)))) => Some(signature)
        case _                                                    => None
      }
    }
}
