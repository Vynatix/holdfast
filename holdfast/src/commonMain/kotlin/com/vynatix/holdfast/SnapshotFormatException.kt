package com.vynatix.holdfast

/**
 * The text given to [StoreSnapshot.decode] is not an encoded store snapshot
 * this version of Holdfast can read (a malformed keyed state family object
 * included), or (thrown by [StoreSnapshot.entry] or [StoreSnapshot.keysOf]) a
 * decoded snapshot's text for a state cannot be decoded by that state's codec,
 * or the snapshot holds a keyed state family, not a single value, under the
 * state's name — or it holds a single value under a keyed state family's
 * name, or the family's `keyCodec` cannot decode a key.
 *
 * The message says what is wrong and where — a character offset, a state
 * name, an exception's class — and never quotes a state's value: an encoded
 * snapshot holds state values, and exception messages end up in logs. For the
 * same reason it never has a cause: a codec's exception may quote the text it
 * failed on.
 *
 * Experimental (issue #20, R1).
 */
@ExperimentalStoreApi
class SnapshotFormatException internal constructor(
    message: String,
) : IllegalArgumentException(message)

/** Throw the [SnapshotFormatException] for [problem] at character offset [at] of the text being decoded. */
@OptIn(ExperimentalStoreApi::class)
internal fun snapshotFormatError(
    problem: String,
    at: Int,
): Nothing = throw SnapshotFormatException("Cannot decode store snapshot: $problem at offset $at.")
