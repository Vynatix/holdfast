package com.vynatix.holdfast.bridge

import com.vynatix.holdfast.Store
import com.vynatix.holdfast.TransactionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * F12 — `KvBridge` decode-failure hook + save-failure contract.
 *
 * By default a payload that fails to decode on attach is silently dropped (state
 * stays at its initializer) and the next commit overwrites it. The optional
 * `onDecodeError` hook observes the raw payload + cause at the moment of the
 * drop without changing that behavior. A save failure (`put` throw) surfaces the
 * transaction as [TransactionResult.Error] after the in-memory commit already
 * applied — not a rollback.
 */
private class KvBridgeVault : Store<KvBridgeVault>() {
    val name by state { "init" }
}

/** Codec whose [decode] always throws [boom]; [encode] is identity. */
private class ThrowingDecodeCodec(
    private val boom: Throwable,
) : Codec<String> {
    override fun encode(value: String): String = value

    override fun decode(string: String): String = throw boom
}

/** [KvStore] whose [put] always throws [boom]. */
private class ThrowingPutKvStore(
    private val boom: Throwable,
) : KvStore {
    override fun get(key: String): String? = null

    override fun put(
        key: String,
        value: String,
    ): Unit = throw boom

    override fun remove(key: String) = Unit

    override fun snapshot(): Map<String, String> = emptyMap()
}

class KvBridgeTest {
    @Test
    fun decodeFailureInvokesHookWithRawPayloadAndCauseAndKeepsInitializer() {
        val boom = IllegalStateException("corrupt")
        val kv = InMemoryKvStore().apply { put("k", "corrupt-bytes") }
        val seen = mutableListOf<Pair<String, Throwable>>()
        val bridge =
            KvBridge(kv, "k", ThrowingDecodeCodec(boom)) { encoded, cause ->
                seen += encoded to cause
            }
        val v = KvBridgeVault()

        v { name bridge bridge }

        // Hook saw the EXACT raw payload and the causing throwable.
        assertEquals(1, seen.size, "hook fires once on the failed decode")
        assertEquals("corrupt-bytes", seen.single().first)
        assertSame(boom, seen.single().second)
        // The decode-drop leaves the state at its initializer.
        assertEquals("init", v.name.value)
    }

    @Test
    fun decodeFailureWithoutHookSilentlySkips() {
        val kv = InMemoryKvStore().apply { put("k", "corrupt-bytes") }
        val bridge = KvBridge(kv, "k", ThrowingDecodeCodec(IllegalStateException("x")))
        val v = KvBridgeVault()

        // Pins the default behavior: no throw, no hook, state stays at initializer.
        v { name bridge bridge }

        assertEquals("init", v.name.value)
    }

    @Test
    fun goodPayloadHydratesState() {
        val kv = InMemoryKvStore().apply { put("k", "loaded") }
        val bridge = KvBridge(kv, "k", StringCodec)
        val v = KvBridgeVault()

        v { name bridge bridge }

        assertEquals("loaded", v.name.value)
    }

    @Test
    fun publishThrowSurfacesAsErrorNotRollback() {
        val boom = RuntimeException("disk full")
        val bridge = KvBridge(ThrowingPutKvStore(boom), "k", StringCodec)
        val v = KvBridgeVault()
        v { name bridge bridge }

        val r = v action { name mutate "written" }

        assertIs<TransactionResult.Error>(r)
        // The publish throw surfaces the commit failure; boom is its cause.
        assertSame(boom, r.exception.cause)
        // Not a rollback: the in-memory commit applied before publish threw.
        assertEquals("written", v.name.value)
    }
}
