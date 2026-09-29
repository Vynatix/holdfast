@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.StateCodec
import com.vynatix.holdfast.Store
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Refuses negative keys, quoting the key in its message the way a real codec might. */
private object LnRefusingKeyCodec : StateCodec<Int> {
    override fun encode(value: Int): String {
        require(value >= 0) { "cannot encode the confidential key $value" }
        return value.toString()
    }

    override fun decode(string: String): Int = string.toInt()
}

private class LnCountingKeyCodec : StateCodec<Int> {
    var encodes = 0

    override fun encode(value: Int): String {
        encodes++
        return value.toString()
    }

    override fun decode(string: String): Int = string.toInt()
}

/** A key of a codec-less branch, named by `toString()` — which refuses. */
private class LnOpaqueKey(
    val id: Int,
) {
    override fun toString(): String = error("hidden identity $id")
}

private class LnIntStore(
    id: Int,
    root: LnRoot,
) : Store<LnIntStore>(root.byInt.at(id))

private class LnOpaqueStore(
    key: LnOpaqueKey,
    root: LnRoot,
) : Store<LnOpaqueStore>(root.opaque.at(key))

private class LnCountedStore(
    id: Int,
    root: LnRoot,
) : Store<LnCountedStore>(root.counted.at(id))

private class LnRoot(
    counting: LnCountingKeyCodec,
) : Root("ln") {
    val byInt by keyed<Int, LnIntStore>(keyCodec = LnRefusingKeyCodec)
    val opaque by keyed<LnOpaqueKey, LnOpaqueStore>()
    val counted by keyed<Int, LnCountedStore>(keyCodec = counting)
}

/**
 * `create`/`getOrCreate` name the key once, up front, and a key the codec
 * refuses fails with the branch context every other tree error carries —
 * naming the root and the branch, never the key or the codec's own message.
 */
class KeyedLeafNameTest {
    private fun assertNamesTheBranchOnly(
        error: IllegalStateException,
        branch: String,
    ) {
        val message = assertNotNull(error.message)
        assertTrue("root 'ln'" in message, message)
        assertTrue(branch in message, message)
        assertFalse("confidential" in message, "the codec's message is not quoted: $message")
        assertFalse("hidden" in message, "toString()'s message is not quoted: $message")
        assertFalse("-7" in message, "the key is not quoted: $message")
        assertNull(error.cause, "the codec's exception is not attached: its message may quote the key")
    }

    @Test
    fun createRefusedByTheKeyCodecNamesTheBranchAndReservesNothing() {
        val root = LnRoot(LnCountingKeyCodec())
        val error = assertFailsWith<IllegalStateException> { root.byInt.create(-7) { LnIntStore(it, root) } }
        assertNamesTheBranchOnly(error, "byInt")
        assertNull(root[root.byInt, -7])
        assertTrue(root.entries(root.byInt).isEmpty(), "nothing was reserved")
        val later = root.byInt.create(7) { LnIntStore(it, root) }
        assertSame(later, root[root.byInt, 7], "the branch still works for keys the codec accepts")
    }

    @Test
    fun getOrCreateRefusedByTheKeyCodecNamesTheBranchAndReservesNothing() {
        val root = LnRoot(LnCountingKeyCodec())
        val error = assertFailsWith<IllegalStateException> { root.byInt.getOrCreate(-7) { LnIntStore(it, root) } }
        assertNamesTheBranchOnly(error, "byInt")
        assertNull(root[root.byInt, -7])
        assertTrue(root.entries(root.byInt).isEmpty())
    }

    @Test
    fun aCodecLessBranchWrapsAThrowingToStringTheSameWay() {
        val root = LnRoot(LnCountingKeyCodec())
        val key = LnOpaqueKey(1)
        val error = assertFailsWith<IllegalStateException> { root.opaque.create(key) { LnOpaqueStore(it, root) } }
        assertNamesTheBranchOnly(error, "opaque")
        assertTrue(root.entries(root.opaque).isEmpty())
    }

    @Test
    fun createAndGetOrCreateNameTheKeyOncePerCall() {
        val counting = LnCountingKeyCodec()
        val root = LnRoot(counting)
        val created = root.counted.create(1) { LnCountedStore(it, root) }
        assertEquals(1, counting.encodes)
        repeat(3) { assertSame(created, root.counted.getOrCreate(1) { LnCountedStore(it, root) }) }
        assertEquals(4, counting.encodes, "a hit names the key once, before the registry is consulted")
        root.counted.getOrCreate(2) { LnCountedStore(it, root) }
        assertEquals(5, counting.encodes, "a miss names the key once for its reservation")
    }
}
