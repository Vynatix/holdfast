package com.vynatix.vault.validation

import com.vynatix.validation.Boxed
import com.vynatix.validation.Validator
import com.vynatix.vault.bridge.Codec

/**
 * Persists a [Boxed] [O] through any Vault [Codec] pipeline.
 *
 * Encoding strips the wrapper and delegates to [primitiveCodec]; decoding
 * runs the primitive through [validator] to re-wrap. Throws
 * [com.vynatix.validation.ValidationException] on decode if the persisted
 * primitive no longer validates (e.g. constraints tightened across versions).
 *
 * ```kotlin
 * vault {
 *     email bridge KvBridge(
 *         kvStore,
 *         "user.email",
 *         BoxedCodec(StringCodec, EmailValidator),
 *     )
 * }
 * ```
 */
class BoxedCodec<P : Any, O : Boxed<P>>(private val primitiveCodec: Codec<P>, private val validator: Validator<P, O>) : Codec<O> {
    override fun encode(value: O): String = primitiveCodec.encode(value.value)

    override fun decode(string: String): O = validator of primitiveCodec.decode(string)
}
