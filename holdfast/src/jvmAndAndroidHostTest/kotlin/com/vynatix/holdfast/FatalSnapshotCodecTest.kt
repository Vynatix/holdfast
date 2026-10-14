@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast

import com.vynatix.holdfast.bridge.IntCodec
import com.vynatix.holdfast.tree.tree
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// Issue #61: a codec's fatal error (a VirtualMachineError) propagates out of
// encode() and out of a decoded snapshot's reads as it is — never a
// SnapshotFormatException or a "Cannot encode" IllegalStateException, never
// reported.

/** An Int codec that throws [encodeThrows]/[decodeThrows] while set. */
private class FscIntCodec : StateCodec<Int> {
    @Volatile var encodeThrows: Throwable? = null

    @Volatile var decodeThrows: Throwable? = null

    override fun encode(value: Int): String {
        encodeThrows?.let { throw it }
        return value.toString()
    }

    override fun decode(string: String): Int {
        decodeThrows?.let { throw it }
        return string.toInt()
    }
}

/** A String key codec that throws [encodeThrows]/[decodeThrows] while set. */
private class FscKeyCodec : StateCodec<String> {
    @Volatile var encodeThrows: Throwable? = null

    @Volatile var decodeThrows: Throwable? = null

    override fun encode(value: String): String {
        encodeThrows?.let { throw it }
        return value
    }

    override fun decode(string: String): String {
        decodeThrows?.let { throw it }
        return string
    }
}

private class FscStore : Store<FscStore>() {
    val codec = FscIntCodec()
    val keyCodec = FscKeyCodec()
    val valueCodec = FscIntCodec()

    val n by state(codec = codec) { 0 }
    val other by state(codec = IntCodec) { 0 }
    val docs by keyedState<String, Int>(codec = valueCodec, keyCodec = keyCodec) { 0 }
}

private fun Store<*>.recordUncaught(): List<Throwable> {
    val seen = mutableListOf<Throwable>()
    uncaughtObserverHandler = { seen += it }
    return seen
}

/** The store still works: no transaction left installed, a new action commits. */
private fun FscStore.assertNotWedged() {
    assertNull(activeTransaction)
    assertNull(SettleScopes.current(), "no settle scope left open on this thread")
    assertNull(FrameMarkers.current(), "no frame marker left on this thread")
    val before = other.value
    action { other mutate before + 1 }.getOrThrow()
    assertEquals(before + 1, other.value)
}

class FatalSnapshotCodecTest {
    @Test
    fun aDecodedReadThrowsTheCodecsFatalErrorAsItIs() {
        val store = FscStore()
        val seen = store.recordUncaught()
        store action { n mutate 5 }
        val decoded = StoreSnapshot.decode(store.snapshot().encode())
        val fatal = StackOverflowError()
        store.codec.decodeThrows = fatal

        assertSame(fatal, assertFailsWith<StackOverflowError> { decoded[store.n] })
        assertSame(fatal, assertFailsWith<StackOverflowError> { decoded.entry(store.n) })

        // Control: a non-fatal decode failure is still a format error.
        store.codec.decodeThrows = IllegalArgumentException("unreadable")
        assertFailsWith<SnapshotFormatException> { decoded[store.n] }
        store.codec.decodeThrows = null
        assertEquals(5, decoded[store.n])
        assertEquals(5, store.n.value)
        assertEquals(emptyList(), seen)
        store.assertNotWedged()
    }

    @Test
    fun encodeThrowsAStateCodecsFatalErrorAsItIsOnTheStoreAndTreeEnvelopes() {
        val store = FscStore()
        val seen = store.recordUncaught()
        store action { n mutate 5 }
        val captured = store.snapshot()
        val fatal = StackOverflowError()
        store.codec.encodeThrows = fatal

        assertSame(fatal, assertFailsWith<StackOverflowError> { captured.encode() })
        assertSame(fatal, assertFailsWith<StackOverflowError> { store.snapshot().encode() })
        assertSame(fatal, assertFailsWith<StackOverflowError> { store.tree.snapshot().encode() })

        // Control: a non-fatal encode failure is still the "Cannot encode" refusal.
        store.codec.encodeThrows = IllegalArgumentException("unwritable")
        val refused = assertFailsWith<IllegalStateException> { captured.encode() }
        assertTrue("Cannot encode state 'n'" in refused.message.orEmpty(), refused.message)
        store.codec.encodeThrows = null
        assertEquals(5, StoreSnapshot.decode(captured.encode())[store.n])
        assertEquals(5, store.n.value)
        assertEquals(emptyList(), seen)
        store.assertNotWedged()
    }

    @Test
    fun keyedEncodeThrowsAKeyOrValueCodecsFatalErrorAsItIs() {
        val store = FscStore()
        val seen = store.recordUncaught()
        store action { docs["a"] mutate 1 }
        val decoded = StoreSnapshot.decode(store.snapshot().encode())

        val keyFatal = StackOverflowError()
        store.keyCodec.encodeThrows = keyFatal
        assertSame(keyFatal, assertFailsWith<StackOverflowError> { store.snapshot().encode() })
        // A decoded keyed entry is found by its encoded key.
        assertSame(keyFatal, assertFailsWith<StackOverflowError> { decoded[store.docs["a"]] })
        store.keyCodec.encodeThrows = null

        val valueFatal = OutOfMemoryError()
        store.valueCodec.encodeThrows = valueFatal
        assertSame(valueFatal, assertFailsWith<OutOfMemoryError> { store.snapshot().encode() })

        // Control: a non-fatal failure is still the "Cannot encode" refusal.
        store.valueCodec.encodeThrows = IllegalArgumentException("unwritable")
        val refused = assertFailsWith<IllegalStateException> { store.snapshot().encode() }
        assertTrue("Cannot encode" in refused.message.orEmpty(), refused.message)
        store.valueCodec.encodeThrows = null
        assertEquals(1, decoded[store.docs["a"]])
        assertEquals(1, store.docs["a"].value)
        assertEquals(emptyList(), seen)
        store.assertNotWedged()
    }

    @Test
    fun keysOfADecodedSnapshotThrowsTheKeyCodecsFatalErrorAsItIs() {
        val store = FscStore()
        val seen = store.recordUncaught()
        store action { docs["a"] mutate 1 }
        val decoded = StoreSnapshot.decode(store.snapshot().encode())
        val fatal = StackOverflowError()
        store.keyCodec.decodeThrows = fatal

        assertSame(fatal, assertFailsWith<StackOverflowError> { decoded.keysOf(store.docs) })

        store.keyCodec.decodeThrows = IllegalArgumentException("unreadable key")
        assertFailsWith<SnapshotFormatException> { decoded.keysOf(store.docs) }
        store.keyCodec.decodeThrows = null
        assertEquals(setOf("a"), decoded.keysOf(store.docs))
        assertEquals(emptyList(), seen)
        store.assertNotWedged()
    }
}
