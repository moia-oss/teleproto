package io.moia.protos.teleproto

import scala.concurrent.duration._

class DeadlineFormatTest extends UnitTest {

  "Writer/Reader for deadlines" should {

    "read exact written value for deadlines as fixed point in time" in {

      val writer = Writer.FixedPointDeadlineWriter
      val reader = Reader.FixedPointDeadlineReader

      val testCases =
        Seq(
          5.seconds,
          123.nanosecond,
          -1.day
        )

      testCases.foreach { timeLeft =>
        withClue(timeLeft) {

          val deadline = timeLeft.fromNow

          assert(timeLeft - reader.read(writer.write(deadline)).get.timeLeft < 250.millis,
                 "Either your test system is too slow or the reader is wrong!")
        }
      }
    }

    "read the written time left for deadlines as time left" in {

      val writer = Writer.TimeLeftDeadlineWriter
      val reader = Reader.TimeLeftDeadlineReader

      val deadline = Deadline.now + 1.second + 1.nanosecond
      val timeLeft = deadline.timeLeft

      assert(timeLeft - reader.read(writer.write(deadline)).get.timeLeft < 250.millis,
             "Either your test system is too slow or the reader is wrong!")
    }
  }
}
