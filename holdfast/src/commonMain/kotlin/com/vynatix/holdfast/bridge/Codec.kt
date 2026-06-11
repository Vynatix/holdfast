package com.vynatix.holdfast.bridge

/**
 * Bidirectional serialization for a value of type [T] to/from a string.
 * Used by [KvBridge] to persist arbitrary state types via a [KvStore].
 *
 * Implementations should round-trip: `decode(encode(x)) == x` for all valid `x`.
 * For types whose String representation is the natural encoding (Int, Long,
 * String), the trivial codecs in this package can be used directly.
 */
interface Codec<T : Any> {
    fun encode(value: T): String

    fun decode(string: String): T
}

/** Identity codec for `String`. */
object StringCodec : Codec<String> {
    override fun encode(value: String): String = value

    override fun decode(string: String): String = string
}

/** Long codec via `toString` / `toLong`. Throws on non-numeric input. */
object LongCodec : Codec<Long> {
    override fun encode(value: Long): String = value.toString()

    override fun decode(string: String): Long = string.toLong()
}

/** Int codec via `toString` / `toInt`. Throws on non-numeric input. */
object IntCodec : Codec<Int> {
    override fun encode(value: Int): String = value.toString()

    override fun decode(string: String): Int = string.toInt()
}

/** Boolean codec via `toString` / `toBooleanStrict`. */
object BooleanCodec : Codec<Boolean> {
    override fun encode(value: Boolean): String = value.toString()

    override fun decode(string: String): Boolean = string.toBooleanStrict()
}
