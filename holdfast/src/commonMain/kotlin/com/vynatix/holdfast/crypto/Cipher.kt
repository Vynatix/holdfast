package com.vynatix.holdfast.crypto

/**
 * Bidirectional symmetric cipher contract used by [EncryptingTransformer].
 * Encryption produces a String (typically base64-encoded ciphertext);
 * decryption recovers the plaintext.
 *
 * Implementations must round-trip: `decrypt(encrypt(x)) == x` for all valid `x`.
 *
 * The library does NOT ship a production-grade [StoreCipher]. [XorCipher] is
 * provided as an educational stand-in. Production users should plug their own
 * cipher backed by `javax.crypto` (JVM) or `CryptoKit` (iOS) — typically AES-GCM
 * with a per-state IV embedded in the encoded output.
 *
 * Named `StoreCipher` (not `Cipher`) to avoid colliding with `javax.crypto.Cipher`
 * when both are imported on the JVM. The old `Cipher` name survives as a
 * deprecated typealias for one minor.
 *
 * ### Non-deterministic ciphers make `distinct = true` inert
 *
 * A secure cipher is non-deterministic: the recommended AES-GCM-with-per-value-IV
 * produces different ciphertext every time it encrypts the same plaintext (that
 * randomized output is exactly what makes it secure). But a state's
 * `distinct = true` dedup compares the **post-`Transformer.set` raw value** —
 * i.e. the ciphertext — so two commits of the same *logical* plaintext encrypt
 * to different ciphertexts, never compare equal, and never dedup. Observer
 * fanout and bridge publish fire on every commit regardless of `distinct`.
 * `distinct = true` on an [EncryptingTransformer]-wrapped state backed by such a
 * cipher is therefore a no-op. This is intentional: dedup deliberately does not
 * decrypt to compare logical values, because running `transformer.get` per
 * commit would break the asymmetric-transformer / no-double-decrypt invariants.
 * If you need logical dedup, dedup upstream before mutating the state.
 */
interface StoreCipher {
    fun encrypt(plaintext: String): String

    fun decrypt(ciphertext: String): String
}

/**
 * Deprecated alias for [StoreCipher]. The interface was renamed to avoid
 * colliding with `javax.crypto.Cipher` on the JVM. Kept for one minor so
 * existing `Cipher` references keep compiling; migrate to [StoreCipher].
 */
@Deprecated(
    message = "Renamed to StoreCipher to avoid colliding with javax.crypto.Cipher.",
    replaceWith = ReplaceWith("StoreCipher"),
    level = DeprecationLevel.WARNING,
)
typealias Cipher = StoreCipher
