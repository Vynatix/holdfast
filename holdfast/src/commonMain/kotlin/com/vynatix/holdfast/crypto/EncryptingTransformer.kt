package com.vynatix.holdfast.crypto

import com.vynatix.holdfast.Transformer

/**
 * [Transformer] that encrypts on write and decrypts on read. The stored
 * `currentValue` is ciphertext; readers see plaintext (post `transformer.get`).
 *
 * "In-memory encryption" means the plaintext exists only transiently during a
 * read — the buffer-then-commit transaction model writes ciphertext via
 * `set`, and `MutableState.currentValue` therefore stores ciphertext. Audit
 * middleware that snapshots `pendingWrites` sees ciphertext too.
 *
 * Asymmetric-transformer rollback is handled correctly by the library: pending
 * writes record post-`set` ciphertext; rollback restores raw ciphertext;
 * `transformer.set` is never re-applied during rollback.
 *
 * Example:
 * ```
 * class CredentialsVault : Holdfast<CredentialsVault>() {
 *     val token by state(EncryptingTransformer(SystemAesCipher())) { "" }
 * }
 * vault action { token mutate "secret-jwt" }
 * vault.token.value          // "secret-jwt" (decrypted)
 * vault.properties["token"]?.value // "secret-jwt" (read goes through get)
 * // The MutableState's currentValue holds ciphertext; only get() returns plaintext.
 * ```
 *
 * Combine with [com.vynatix.holdfast.bridge.KvBridge] for at-rest encryption: the
 * persisted bytes are ciphertext.
 */
class EncryptingTransformer(private val cipher: Cipher) : Transformer<String> {
    override fun set(value: String): String = cipher.encrypt(value)
    override fun get(value: String): String = cipher.decrypt(value)
}
