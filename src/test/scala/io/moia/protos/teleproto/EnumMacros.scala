package io.moia.protos.teleproto

import scalapb.GeneratedEnum
import scala.quoted.*
import scala.deriving.Mirror
import io.moia.food.food.Meal.Color.COLOR_BLUE.name

object EnumMacros:

  inline def fromProtobufToEnum[I <: GeneratedEnum, O](using M: Mirror.SumOf[O]): I => O =
    ${ fromProtobufToEnumImpl[I, O] }

  private def fromProtobufToEnumImpl[I <: GeneratedEnum: Type, O: Type](using Quotes): Expr[I => O] =
    import quotes.reflect.*

    def recGetLabels[T: Type]: List[String] =
      Type.of[T] match
        case '[EmptyTuple] => Nil
        case '[label *: labelsTail] =>
          val label = Type
            .valueOfConstant[label]
            .getOrElse {
              report.errorAndAbort(s"Couldn't get the value of the label for type ${TypeRepr.of[label].show}.")
            }
            .asInstanceOf[String]
          label :: recGetLabels[labelsTail]

    val labelsOfEnumMembers =
      Expr.summon[Mirror.SumOf[O]] match
        case None =>
          report.errorAndAbort("Couldn't summon the mirror of the enum.")
        case Some(mirror) =>
          mirror match
            case '{ $m: Mirror.Sum { type MirroredElemLabels = elementLabels } } =>
              recGetLabels[elementLabels]
            case _ => report.errorAndAbort("Couln't get the labels of the enum members.")

    val labelOfInputType = TypeRepr.of[I].show.split('.').last // last element of FQCN
    val inputType        = Expr(labelOfInputType)

    val O   = TypeRepr.of[O]
    val sym = O.typeSymbol

    // this will work *only* for Scala 3 enums
    // look here: https://github.com/scalalandio/chimney/blob/master/chimney-macro-commons/src/main/scala-3/io/scalaland/chimney/internal/compiletime/datatypes/SealedHierarchiesPlatform.scala
    // for a more general solution that works with any sealed hierarchy
    def spawnInstance(label: Expr[String]): Expr[O] =
      Apply(Select.unique(Ref(sym.companionModule), "valueOf"), List(label.asTerm)).asExprOf[O]

    '{ (i: I) =>
      {
        val name: String  = i.name
        val prefix        = $inputType + "_"
        val withoutPrefix = name.toLowerCase.replace(prefix.toLowerCase, "")
        val capitalized   = withoutPrefix.split('_').map(_.capitalize).mkString

        ${ spawnInstance('capitalized) }
      }
    }
