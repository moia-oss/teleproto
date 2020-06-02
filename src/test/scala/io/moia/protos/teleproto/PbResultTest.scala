package io.moia.protos.teleproto

import scala.util.{Failure, Success, Try}

class PbResultTest extends UnitTest {

  "PbResult" should {
    "have constructor from Option" in {
      val failure = PbFailure("user not found")
      PbResult.fromOption(Some("user"))(failure) shouldBe PbSuccess("user")
      PbResult.fromOption(None)(failure) shouldBe failure
    }

    "have constructor from Either" in {
      val left: Either[Int, String]  = Left(404)
      val right: Either[Int, String] = Right("Welcome")
      def fromErrorCode(code: Int)   = PbFailure(s"Error code: $code")
      PbResult.fromEither(right)(fromErrorCode) shouldBe PbSuccess("Welcome")
      PbResult.fromEither(left)(fromErrorCode) shouldBe fromErrorCode(404)
    }

    "have constructor from Either of String" in {
      val left: Either[String, Double]  = Left("Could not calculate average rating")
      val right: Either[String, Double] = Right(4.5)
      PbResult.fromEitherString(right) shouldBe PbSuccess(4.5)
      PbResult.fromEitherString(left) shouldBe PbFailure("Could not calculate average rating")
    }

    "have constructor from Either of Throwable" in {
      val left: Either[Throwable, String]  = Left(new Exception("Title was empty"))
      val right: Either[Throwable, String] = Right("Protocol Buffers Explained")
      PbResult.fromEitherThrowable(right) shouldBe PbSuccess("Protocol Buffers Explained")
      PbResult.fromEitherThrowable(left) shouldBe PbFailure("Title was empty")
    }

    "have constructor from Try" in {
      val failure: Try[Int] = Failure(new Exception("Still computing..."))
      val success: Try[Int] = Success(42)
      PbResult.fromTry(success) shouldBe PbSuccess(42)
      PbResult.fromTry(failure) shouldBe PbFailure("Still computing...")
    }
  }

  "PbFailure" should {
    "have constructor from Throwable" in {
      val message = "Malformed payload"
      PbFailure.fromThrowable(new Exception(message)) shouldBe PbFailure(message)
    }
  }
}
