package com.vynatix.holdfast

/**
 * Turns a state's stored value into text and back, so a [StoreSnapshot] can
 * leave memory: declare a state with one
 * (`val count by state(codec = IntCodec) { 0 }`, an experimental overload of
 * [Store.state]) and [StoreSnapshot.encode] writes the state's value as
 * [encode]'s text, which [StoreSnapshot.decode] and [restore] turn back into
 * a value with [decode].
 *
 * A codec sees the state's RAW value — what the store keeps, after
 * `Transformer.set` — never the `Transformer.get` view: an
 * `EncryptingTransformer` state is encoded as its ciphertext, and restoring
 * the decoded ciphertext does not encrypt it a second time.
 *
 * Implementations must round-trip: `decode(encode(x)) == x` for every value
 * the state can hold. [decode] may throw on text it cannot read; a restore
 * reports that without the exception (whose message may quote the text).
 * `encode`, `restore` and a decoded snapshot's `get`/`entry` take no lock of
 * the store while they call a codec, so at top level a codec runs under no
 * store lock. Called from inside an action or an `atomic(...)` frame, the
 * codec runs under the locks that action or frame holds, so do not block in
 * either method on another thread's store work. Both may run on any thread.
 *
 * Every [com.vynatix.holdfast.bridge.Codec] is a `StateCodec`, so the codecs
 * `KvBridge` uses (`StringCodec`, `IntCodec`, `LongCodec`, `BooleanCodec`, or
 * your own) work here unchanged. For a `kotlinx.serialization` type, wrap its
 * `KSerializer` (the recipe in GUIDE §16.2).
 *
 * Stable from the release that introduces it, although it is part of the
 * issue #20 snapshot work, whose other API is `@ExperimentalStoreApi`: the
 * stable `Codec` extends it, so `Codec`'s contract already fixes its shape
 * (a recorded exception to the roadmap's "new surface needs soak" rule).
 */
interface StateCodec<T : Any> {
    /** The text that stands for [value], a state's raw stored value. */
    fun encode(value: T): String

    /** The raw value [string] stands for; throws if [string] is not something [encode] writes. */
    fun decode(string: String): T
}
