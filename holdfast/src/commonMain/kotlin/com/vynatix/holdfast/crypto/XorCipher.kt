package com.vynatix.holdfast.crypto

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * KMP-pure XOR cipher. **Educational only — NOT production-grade.**
 *
 * Provided so the [com.vynatix.holdfast.crypto] package is runnable without
 * pulling in a platform crypto dependency. For real use cases:
 *  - JVM: implement [Cipher] using `javax.crypto` (AES-GCM with a random IV).
 *  - iOS: implement [Cipher] using `CryptoKit` via cinterop.
 *
 * Limitations of XOR with a fixed key:
 *  - No integrity (no MAC): an attacker can flip bits without detection.
 *  - No semantic security: identical plaintexts encrypt to identical ciphertexts.
 *  - Trivially broken if the key is shorter than the longest plaintext.
 *
 * This implementation deliberately uses base64 over the raw XOR'd bytes so the
 * ciphertext is a printable String (round-tripping through any [Cipher] consumer).
 */
@OptIn(ExperimentalEncodingApi::class)
class XorCipher(seed: ByteArray) : Cipher {
    init {
        require(seed.isNotEmpty()) { "XorCipher seed must be non-empty" }
    }
    private val key: ByteArray = seed.copyOf()

    override fun encrypt(plaintext: String): String {
        val plain = plaintext.encodeToByteArray()
        val cipher = ByteArray(plain.size) { i -> (plain[i].toInt() xor key[i % key.size].toInt()).toByte() }
        return Base64.encode(cipher)
    }

    override fun decrypt(ciphertext: String): String {
        val cipher = Base64.decode(ciphertext)
        val plain = ByteArray(cipher.size) { i -> (cipher[i].toInt() xor key[i % key.size].toInt()).toByte() }
        return plain.decodeToString()
    }
}
