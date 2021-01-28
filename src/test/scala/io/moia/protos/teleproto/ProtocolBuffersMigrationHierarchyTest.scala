package io.moia.protos.teleproto

import scala.annotation.nowarn

object ProtocolBuffersMigrationHierarchyTest {

  // V2

  // Uses the same field names like V1 ...
  case class ProtoV2(matchingSubProto: Option[MatchingSubProtoV2], unmatchingSubProto: UnmatchingSubProtoV2, passengers: List[PassengerV2])

  // ... where this nested message has similar format
  case class MatchingSubProtoV2(same: String)

  // ... where this nested message has changed partially but the nested 3rd level class inside is still matching
  case class UnmatchingSubProtoV2(baz: String, inner: ThirdLevelV2)
  case class ThirdLevelV2(bar: String)

  // ... where this nested message was created from the `Int` in V1
  case class PassengerV2(adult: Boolean)

  // V1

  case class MatchingSubProtoV1(same: String)

  case class UnmatchingSubProtoV1(foo: Int, inner: ThirdLevelV1)
  case class ThirdLevelV1(bar: String)

  case class ProtoV1(matchingSubProto: Option[MatchingSubProtoV1], unmatchingSubProto: UnmatchingSubProtoV1, passengers: Int)

  // Migration

  implicit val unmatchingSubProtoV1toV2: Migration[UnmatchingSubProtoV1, UnmatchingSubProtoV2] =
    ProtocolBuffers.migration[UnmatchingSubProtoV1, UnmatchingSubProtoV2](_.foo.toString)

  implicit val protoV1toV2: Migration[ProtoV1, ProtoV2] =
    ProtocolBuffers.migration[ProtoV1, ProtoV2](pb => List.fill(pb.passengers)(PassengerV2(true)))
}

class ProtocolBuffersMigrationHierarchyTest extends UnitTest {

  import ProtocolBuffersMigrationHierarchyTest._

  "ProtocolBuffers (hierarchy migration)" should {

    "construct a migration from generated and manual nested migrations" in {

      protoV1toV2.migrate(ProtoV1(Some(MatchingSubProtoV1("same")), UnmatchingSubProtoV1(42, ThirdLevelV1("ok")), 1)) shouldBe
        ProtoV2(Some(MatchingSubProtoV2("same")), UnmatchingSubProtoV2("42", ThirdLevelV2("ok")), List(PassengerV2(true)))

      protoV1toV2.migrate(ProtoV1(Some(MatchingSubProtoV1("same")), UnmatchingSubProtoV1(42, ThirdLevelV1("ok")), 2)) shouldBe
        ProtoV2(Some(MatchingSubProtoV2("same")),
                UnmatchingSubProtoV2("42", ThirdLevelV2("ok")),
                List(PassengerV2(true), PassengerV2(true)))

      protoV1toV2.migrate(ProtoV1(None, UnmatchingSubProtoV1(42, ThirdLevelV1("ok")), 0)) shouldBe
        ProtoV2(None, UnmatchingSubProtoV2("42", ThirdLevelV2("ok")), Nil)
    }

    "prefer a custom nested migration over a generated" in {

      @nowarn // unused
      implicit val upperCasingMatchingSubProtoV1toV2: Migration[MatchingSubProtoV1, MatchingSubProtoV2] =
        Migration[MatchingSubProtoV1, MatchingSubProtoV2](src => MatchingSubProtoV2(src.same.toUpperCase))

      val customProtoV1toV2: Migration[ProtoV1, ProtoV2] =
        ProtocolBuffers.migration[ProtoV1, ProtoV2](pb => List.fill(pb.passengers)(PassengerV2(true)))

      customProtoV1toV2.migrate(ProtoV1(Some(MatchingSubProtoV1("same")), UnmatchingSubProtoV1(42, ThirdLevelV1("ok")), 0)) shouldBe
        ProtoV2(Some(MatchingSubProtoV2("SAME")), UnmatchingSubProtoV2("42", ThirdLevelV2("ok")), Nil)
    }
  }
}
