package com.vynatix.holdfast.crypto

import com.vynatix.holdfast.Transformer

/**
 * [Transformer] that encrypts on write and decrypts on read. The stored
 * `currentValue` is ciphertext; readers see plaintext (post `transformer.get`).
 *
 * What is encrypted is the STORED value: `mutate` stages ciphertext (`set`),
 * `MutableState.currentValue` holds it, and so do what is built from the raw
 * value — `snapshot()` captures, `StoreSnapshot.encode()` text, bridge
 * publishes (a `KvBridge` persists ciphertext). Everything that reads the
 * state gets plaintext: `value`, observers and `effect`, `derived` states,
 * `snapshot[state]`, and a middleware or test that reads `value` — the
 * plaintext is no more transient than any other value a reader holds.
 * Encryption is therefore not redaction: to keep a value out of encoded
 * snapshots, `render()`/`toString()`, `:holdfast-testing` timelines and
 * middleware output, tag the state `StateTag.Secret`
 * (`state(transformer = EncryptingTransformer(cipher), tags = setOf(StateTag.Secret)) { "" }`);
 * a Secret state is encoded as `null`, not as its ciphertext.
 *
 * An initializer's result is stored as it is, without `set`, like every
 * initial value, so it is read through `get` as if it were ciphertext: start
 * from a value your cipher decrypts to itself (the empty string, for
 * [XorCipher]) or from ciphertext.
 *
 * Asymmetric-transformer rollback is handled correctly by the library: pending
 * writes record post-`set` ciphertext; rollback restores raw ciphertext;
 * `transformer.set` is never re-applied during rollback.
 *
 * Example:
 * ```
 * class CredentialsVault : Store<CredentialsVault>() {
 *     val token by state(EncryptingTransformer(SystemAesCipher())) { "" }
 * }
 * store action { token mutate "secret-jwt" }
 * store.token.value          // "secret-jwt" (decrypted)
 * store.properties["token"]?.value // "secret-jwt" (read goes through get)
 * // The MutableState's currentValue holds ciphertext; only get() returns plaintext.
 * ```
 *
 * Combine with [com.vynatix.holdfast.bridge.KvBridge] for at-rest encryption: the
 * persisted bytes are ciphertext.
 */
class EncryptingTransformer(
    private val cipher: Cipher,
) : Transformer<String> {
    override fun set(value: String): String = cipher.encrypt(value)

    override fun get(value: String): String = cipher.decrypt(value)
}
