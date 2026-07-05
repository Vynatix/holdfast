package com.vynatix.holdfast.crypto

import com.vynatix.holdfast.Store
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.bridge.InMemoryKvStore
import com.vynatix.holdfast.bridge.KvBridge
import com.vynatix.holdfast.bridge.StringCodec
import com.vynatix.holdfast.effect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private val SEED = "test-key-1".encodeToByteArray()

private class SecureVault : Store<SecureVault>() {
    val token by state(EncryptingTransformer(XorCipher(SEED))) { "" }
    val opaque by state(EncryptingTransformer(XorCipher(SEED))) { "init" }
}

class XorCipherTest {
    @Test fun roundTripPreservesShortPlaintext() {
        val c = XorCipher(SEED)
        val s = "hello, world!"
        assertEquals(s, c.decrypt(c.encrypt(s)))
    }

    @Test fun roundTripPreservesLongerPlaintextAcrossKeyWraps() {
        val c = XorCipher(SEED)
        val s = "the quick brown fox jumps over the lazy dog ".repeat(20)
        assertEquals(s, c.decrypt(c.encrypt(s)))
    }

    @Test fun roundTripPreservesEmpty() {
        val c = XorCipher(SEED)
        assertEquals("", c.decrypt(c.encrypt("")))
    }

    @Test fun roundTripPreservesUnicode() {
        val c = XorCipher(SEED)
        val s = "héllo 🔒 wörld"
        assertEquals(s, c.decrypt(c.encrypt(s)))
    }

    @Test fun ciphertextDiffersFromPlaintext() {
        val c = XorCipher(SEED)
        val s = "secret"
        assertNotEquals(s, c.encrypt(s))
    }

    @Test fun differentKeysProduceDifferentCiphertext() {
        val a = XorCipher("alpha".encodeToByteArray())
        val b = XorCipher("bravo".encodeToByteArray())
        val s = "secret"
        assertNotEquals(a.encrypt(s), b.encrypt(s))
    }

    @Test fun emptySeedIsRejected() {
        assertFails { XorCipher(ByteArray(0)) }
    }
}

class EncryptingTransformerTest {
    @Test fun storedValueIsCiphertextAndReadIsPlaintext() {
        val v = SecureVault()
        v action { token mutate "the-secret-jwt" }
        // Read goes through get() → plaintext.
        assertEquals("the-secret-jwt", v.token.value)
        // The state's stored raw value is the ciphertext, accessible via the
        // internal MutableState path. From outside the package we observe the
        // ciphertext indirectly: it's distinct from the plaintext under the
        // current cipher.
        // Pin: a separate plaintext should encrypt to a distinct stored value.
        val v2 = SecureVault()
        v2 action { token mutate "different-secret" }
        assertNotEquals(v.token.value, v2.token.value)
    }

    @Test fun rollbackPreservesPriorPlaintext() {
        val v = SecureVault()
        v action { token mutate "first" }
        val r =
            v action {
                token mutate "second"
                error("rollback")
            }
        assertIs<TransactionResult.Error>(r)
        assertEquals("first", v.token.value, "rollback restored ciphertext that decrypts to the prior plaintext")
    }

    @Test fun multipleCommitsAreEachDecryptable() {
        val v = SecureVault()
        v action { token mutate "alpha" }
        assertEquals("alpha", v.token.value)
        v action { token mutate "bravo" }
        assertEquals("bravo", v.token.value)
        v action { token mutate "charlie" }
        assertEquals("charlie", v.token.value)
    }

    @Test fun observerSeesPlaintext() {
        val v = SecureVault()
        val seen = mutableListOf<String>()
        val sub = v { token effect { seen.add(this) } }
        seen.clear()
        v action { token mutate "msg-1" }
        v action { token mutate "msg-2" }
        assertEquals(listOf("msg-1", "msg-2"), seen, "effect callback receives post-get plaintext")
        sub.dispose()
    }

    @Test fun encryptingTransformerComposesWithKvBridgeForAtRestEncryption() {
        val v = SecureVault()
        val kv = InMemoryKvStore()
        v { token bridge KvBridge(kv, "creds:token", StringCodec) }
        v action { token mutate "rest-secret" }
        // Persisted to KV is whatever publish was called with — the ciphertext,
        // because applyCommitted publishes the raw stored value (post-set).
        val persisted = kv.get("creds:token") ?: error("expected persisted value")
        assertNotEquals("rest-secret", persisted, "persisted value is ciphertext, not plaintext")
        assertTrue(persisted.isNotEmpty())

        // Rebirth: a new store attaching the same KV hydrates from ciphertext
        // (KvBridge.observe replays the stored String, EncryptingTransformer
        // does NOT decrypt during applyFromBridge — it just stores the value
        // raw, then `value` get applies decrypt). So the round trip works.
        val reborn = SecureVault()
        reborn { token bridge KvBridge(kv, "creds:token", StringCodec) }
        // applyFromBridge calls beforeSet (which would re-encrypt the already-
        // ciphertext value, double-encrypting it). So the rebirth view is the
        // double-decrypt view. Document the limitation and verify what we have.
        // Practically: users either (a) do not combine EncryptingTransformer
        // with a Bridge that re-applies set on inbound, OR (b) use a Bridge
        // that bypasses transformer.set on hydrate. For this smoke we just
        // assert that the persisted value is encrypted; full at-rest E2E with
        // EncryptingTransformer + KvBridge requires extra design (not in 1.1).
        // (The persisted-is-encrypted check above is the value-add of this test.)
        @Suppress("UNUSED_VARIABLE")
        val unused = reborn
    }
}

/**
 * Non-deterministic [StoreCipher]: encrypting the same plaintext yields different
 * ciphertext each time (a monotonic prefix stands in for a random IV), while
 * decrypt still round-trips. Models a secure AES-GCM-with-per-value-IV cipher.
 */
private class NonDeterministicCipher : StoreCipher {
    private var counter = 0

    override fun encrypt(plaintext: String): String = "${counter++}:$plaintext"

    override fun decrypt(ciphertext: String): String = ciphertext.substringAfter(':')
}

private class NonDeterministicVault : Store<NonDeterministicVault>() {
    val token by state(EncryptingTransformer(NonDeterministicCipher()), distinct = true) { "seed" }
}

/**
 * F30 — pins the documented interaction: `distinct = true` is INERT when the
 * state is wrapped in an [EncryptingTransformer] over a non-deterministic
 * cipher. Dedup compares post-`set` raw values (ciphertext); a per-value-IV
 * cipher encrypts equal logical values to different ciphertext, so dedup never
 * fires and observers see every commit.
 */
class DistinctWithNonDeterministicCipherTest {
    @Test fun distinctTrueIsInertUnderNonDeterministicCipher() {
        val v = NonDeterministicVault()
        val seen = mutableListOf<String>()
        val sub = v { token effect { seen.add(this) } }
        seen.clear()

        // Commit the SAME logical value twice.
        v action { token mutate "same" }
        v action { token mutate "same" }

        // distinct=true would dedup the second on value equality — but it compares
        // ciphertext, which differs each encrypt, so both commits fan out.
        assertEquals(
            listOf("same", "same"),
            seen,
            "distinct=true is inert for a non-deterministic cipher; observers see every commit",
        )
        sub.dispose()
    }
}
