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
 * class CredentialsStore : Store<CredentialsStore>() {
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
 *
 * **`distinct = true` does not work with a non-deterministic cipher.** State
 * dedup compares post-`set` raw values — here, ciphertext. A secure cipher
 * (AES-GCM with a per-value IV) encrypts the same plaintext to different
 * ciphertext each time, so equal logical values never compare equal and dedup
 * never fires; observers and bridges publish on every commit. This is by design
 * (dedup must not run [get]/decrypt per commit — that would break the
 * asymmetric-transformer / no-double-decrypt invariants). See [StoreCipher] for
 * the full rationale; dedup upstream if you need logical-value dedup.
 */
class EncryptingTransformer(
    private val cipher: StoreCipher,
) : Transformer<String> {
    override fun set(value: String): String = cipher.encrypt(value)

    override fun get(value: String): String = cipher.decrypt(value)
}
