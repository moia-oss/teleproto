### Protocol Buffers Mapping

This model provides an approach to reduce the mismatch between versioned Protocol Buffers and their generated Scala types and corresponding *business model* types.

This module declares a basic DSL to ***read*** instances of Protocol Buffers generated types into business model types and ***write*** the other way around.
It provides readers and writers for primitive conversions.
It also provides macros to generate readers and writers for case classes if similar to [Play's JSON API](https://www.playframework.com/documentation/2.6.x/ScalaJsonAutomated).

The macros **do not** create a Protocol Buffers definition for Scala classes. They just evaluate a generic mapping between them.

#### Basics

The DSL defines `Reader[P, M]` and `Writer[M, P]` for Protobuf type `P` and business model type `M`.  

Since Protocol Buffers types are much less restrictive than Scala classes a `Writer` directly produces a `P` for an `M`.
On the other side a `Reader` may fail reading an `M` from a `P` and therefore produces a `PbResult[M]`
(either a `PbSuccess` with an `M` or a `PbError`).

Example:

```
// generated Protocol Buffers class
case class ProtobufModel(distance: String, time: Option[Timestamp])

case class BusinessModel(distance: BigDecimal, time: Instant)
```

The reading of the price (transferred as `String`) into a `BigDecimal` could fail with an `PbFailure("/price", "Value must be a decimal number.")`

The reading of the time (transferred as `Option[Timestamp]` through ScalaPB) into an `Instant` could fail if the option is empty with an `PbFailure("/time", "Value is required.")`

#### Defined Reader for message to case classes

Let us assume we want to create a `Reader[ProtobufModel, BusinessModel]` for these given generated Protocol Buffers message and business model classes:

```
// generated Protocol Buffers class
case class ProtobufModel(id: Option[String], price: Option[String], time: Option[Timestamp], pickupId: Option[String], prices: Seq[String])

case class BusinessModel(id: String, price: BigDecimal, time: Instant, pickupId: Option[String], prices: List[BigDecimal])
```

The reader must address every field from the target type `BusinessModel` using helpers from the `Reader` companion.

```
new Reader[ProtobufModel, BusinessModel] {
  def read(protobuf: BusinessModel): PbResult[BusinessModel] =
    for {
      id       <- Reader.required[String, String](protobuf.id, "/id")                   // <- Protobuf is Option[String], Model is String
      price    <- Reader.required[String, BigDecimal](protobuf.price, "/price")             // <- Protobuf is Option[String] Model is BigDecimal
      time     <- Reader.required[Timestamp, Instant](protobuf.time, "/time")           // <- Protobuf is Option[Timestamp], Model is Instant
      pickupId <- Reader.optional[String, String](protobuf.pickupId, "/pickupId")       // <- Protobuf is Option[String], Model is Option[String]
      prices   <- Reader.sequence[List, String, BigDecimal](protobuf.prices, "/prices") // <- Protobuf is Seq[String], Model is List[BigDecimal] (having a CanBuildFrom)
    } yield {
      BusinessModel(id, price, time, pickupId, prices)
    }
}
```

Each helper `required[PV, MV]`, `optional[PV, MV]`, `sequence[SV, PV, MV]` returns a `PbResult[T]`. **They require that a `Reader[PV, MV]` is available implicitly.**

If one entry is a `PbFailure[T]` the whole for loop evaluates to that failure.
If all are a `PbSuccess[T]` all values are used in the `yield` to return `PbSuccess[BusinessModel]`.  

#### Generated Reader for message to case classes

If type-based transformation of values within the message's case class to the model case class is provided via implicits the `Reader` definition is just boilerplate.
The `Reader` for a message can usually be generated if the case classes on both sides are compatible regarding field names.

Compares the case accessors of both classes based on the exact name, e.g. `v1.Price(amount: String)` matches `Price(amount: BigDecimal)`.

If fields of the business model `M` are missing in Protocol Buffers type `P` they must be optional or have a default value.
Given that the reader is backward compatible (getting constant values `None` or the default values respectively).

If fields are surplus in Protocol Buffers type `P` the reader is backward compatible by ignoring those fields.

Backward compatible readers cause a warning. One should use `backwardReader` instead.

For all case accessors in both classes delegates to the combinators in `Reader` are compiled:
 - `Reader.required[PV, MV](protobuf.name, "/name")` if `name` has type `Option[PV]` in `P` and just `MV` in `M`.
 - `Reader.optional[PV, MV](protobuf.name, "/name")` if `name` has type `Option[PV]` in `P` and `Option[MV]` in `M`.
 - `Reader.sequence[CV, PV, MV](protobuf.name, "/name")` if `name` has type `Seq[PV]` in `P` and `CV[MV]` in `M` where `CV` is a collection type with `CanBuildFrom`.
 - `Reader.transform[PV, MV](protobuf.name, "/name")` for all other situations.

The delegates are combined in a de-sugared version of a `for` loop (de-sugared as a cascade of `flatMap`) for construction of the business model:

```
for {
  id       <- required[String, String](protobuf.id, "/id")                   // <- Protobuf is Option[String], Model is String
  price    <- required[String, BigDecimal](protobuf.price, "/price")         // <- Protobuf is Option[String] Model is BigDecimal
  time     <- required[Timestamp, Instant](protobuf.time, "/time")           // <- Protobuf is Option[Timestamp], Model is Instant
  pickupId <- optional[String, String](protobuf.pickupId, "/pickupId")       // <- Protobuf is Option[String], Model is Option[String]
  prices   <- sequence[List, String, BigDecimal](protobuf.prices, "/prices") // <- Protobuf is Seq[String], Model is List[BigDecimal] (having a CanBuildFrom)
} yield {
  Model(id = id, price = price, time = time, pickupId = pickupId, prices = prices) // <- pass as named arguments to support default values
}
```

#### Defined Writer for case classes to messages

Possible and used, but not yet documented...

#### Generated Writer for case classes to messages

Possible and used, but not yet documented...

#### Mapping sealed traits and oneofs

Possible and used, but not yet documented...

#### Possible Improvements

##### Specific Types

Some special types of mismatch between 
********
* `enum` to `Enumeration` (already in use)
* classes generated from `oneof` to `Either[A,B]` (already in use)
* `map<PK, PK>` to  (already in use)

All of them should validate against the *no value* type on Protocol Buffers side if type is not `Option` on *business model* side.

MVP would be to not support the `Option` in the *business model* and always fail for a *no value* in Protocol Buffers.

##### Support Migrations

Currently just readers from a larger Protocol Buffers model or to a larger business model with optional/default value difference can be generated.
If fields are missing and are not optional or have no default value in the business model the reader must be defined manually.
It might be possible to generate some kind of `migratingReader` that takes a function per field `field: FT` that is missing in the Protocol Buffers file:
`field: ProtoType => FT`. That would reduce the effort of migration to the actually incompatible parts.

Example:

```
// generated Protocol Buffers class in previous version
case class ProtobufModel(distance: String, passengerCount: Int)

sealed trait Passenger
case object Adult extends Passenger
case object Child extends Passenger

case class BusinessModel(distance: BigDecimal, passengers: List[Passenger])
```

The field `distance` can be mapped directly. A function for `passengers` is required:

```
ProtocolBuffers.migratingReader[ProtobufModel, BusinessModel](
  // the function providing a value for `passengers`
  protobuf: ProtobufModel => List.fill(passengerCount)(Adult)
)
```

The macro would have to explain the required functions.
That would be a so called *blackbox macro* where the exact type is unknown until compilation.

Similar approach for writing...