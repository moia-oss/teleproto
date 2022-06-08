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

import scala.util.{Failure, Success, Try}

/** Models the attempt to read a Protocol Buffers case class into business model type `T`.
  */
sealed trait PbResult[+T] {

  val isSuccess: Boolean
  val isError: Boolean

  def get: T

  def getOrElse[U >: T](t: => U): U

  def map[B](f: T => B): PbResult[B]

  def flatMap[B](f: T => PbResult[B]): PbResult[B]

  def orElse[U >: T](that: => PbResult[U]): PbResult[U]

  def withPathPrefix(prefix: String): PbResult[T]

  def toTry: Try[T]

  def toOption: Option[T]

  def toEither: Either[Seq[(String, String)], T]
}

object PbResult {

  def fromOption[A](option: Option[A])(ifNone: => PbFailure): PbResult[A] =
    option.fold[PbResult[A]](ifNone)(PbSuccess.apply)

  def fromEither[E, A](either: Either[E, A])(onLeft: E => PbFailure): PbResult[A] =
    either.fold(onLeft, PbSuccess.apply)

  def fromEitherString[A](either: Either[String, A]): PbResult[A] =
    either.fold(PbFailure.apply, PbSuccess.apply)

  def fromEitherThrowable[A](either: Either[Throwable, A]): PbResult[A] =
    either.fold(PbFailure.fromThrowable, PbSuccess.apply)

  def fromTry[A](tryA: Try[A]): PbResult[A] =
    tryA.fold(PbFailure.fromThrowable, PbSuccess.apply)
}

/** Models the success to read a Protocol Buffers case class into business model type `T`.
  */
final case class PbSuccess[T](value: T) extends PbResult[T] {

  val isSuccess = true
  val isError   = false

  override def get: T = value

  override def getOrElse[U >: T](t: => U): U = value

  override def map[B](f: T => B): PbResult[B] = PbSuccess(f(value))

  override def flatMap[B](f: T => PbResult[B]): PbResult[B] = f(value)

  override def orElse[U >: T](that: => PbResult[U]): PbResult[U] = this

  override def withPathPrefix(prefix: String): PbSuccess[T] = this

  override def toTry: Try[T] = Success(get)

  override def toOption: Option[T] = Some(get)

  override def toEither: Right[Seq[(String, String)], T] = Right(value)
}

/** Models the failure to read a Protocol Buffers case class into business model type `T`. Provides error messages for one or more paths,
  * e.g. the path messages could be:
  *   - /price Value must be a decimal number. <- Simple field at top-level
  *   - /tripRequest/time Value is required. <- Nested field
  *   - /prices(1) Value must be a decimal number. <- Simple array
  *   - /tripRequests(1)/time Value is required. <- Nested field in second array entry
  */
@SuppressWarnings(Array("PointlessTypeBounds", "asInstanceOf"))
final case class PbFailure(errors: Seq[(String, String)]) extends PbResult[Nothing] {

  val isSuccess = false
  val isError   = true

  override def get: Nothing = throw new NoSuchElementException(toString)

  override def getOrElse[U >: Nothing](t: => U): U = t

  override def map[B](f: Nothing => B): PbResult[B] = this.asInstanceOf[PbResult[B]]

  override def flatMap[B](f: Nothing => PbResult[B]): PbResult[B] = this.asInstanceOf[PbResult[B]]

  override def orElse[U](that: => PbResult[U]): PbResult[U] = that

  override def withPathPrefix(prefix: String): PbFailure =
    PbFailure(for ((path, message) <- errors) yield (prefix + path, message))

  override def toString: String =
    errors.map(e => s"${e._1} ${e._2}".trim).mkString(" ")

  override def toTry: Try[Nothing] = Failure(new Exception(toString))

  override def toOption: Option[Nothing] = None

  override def toEither: Left[Seq[(String, String)], Nothing] = Left(errors)
}

object PbFailure {

  def apply(path: String, message: String): PbFailure =
    new PbFailure(Seq(path -> message))

  def apply(message: String): PbFailure =
    apply("", message)

  def fromThrowable(error: Throwable): PbFailure =
    apply(error.getMessage)

  /** Collects and combines all the errors of all failures in the given results. Please note: This method ignores all successes and collects
    * just error messages from failures. It's intended to create an overall failure when one of the results is a failure. It doesn't make
    * sense if all are successes.
    */
  def combine(results: PbResult[_]*): PbFailure =
    PbFailure(results.flatMap {
      case PbSuccess(_)      => Seq.empty
      case PbFailure(errors) => errors
    })
}
