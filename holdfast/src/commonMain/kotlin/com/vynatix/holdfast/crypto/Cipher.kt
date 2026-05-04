package com.vynatix.vault.crypto

/**
 * Bidirectional symmetric cipher contract used by [EncryptingTransformer].
 * Encryption produces a String (typically base64-encoded ciphertext);
 * decryption recovers the plaintext.
 *
 * Implementations must round-trip: `decrypt(encrypt(x)) == x` for all valid `x`.
 *
 * The library does NOT ship a production-grade [Cipher]. [XorCipher] is provided
 * as an educational stand-in. Production users should plug their own cipher
 * backed by `javax.crypto` (JVM) or `CryptoKit` (iOS) — typically AES-GCM with
 * a per-state IV embedded in the encoded output.
 */
interface Cipher {
    fun encrypt(plaintext: String): String
    fun decrypt(ciphertext: String): String
}
