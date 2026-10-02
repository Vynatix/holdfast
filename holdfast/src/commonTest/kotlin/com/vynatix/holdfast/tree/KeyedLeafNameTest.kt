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

/**
 * A lookup-table codec: `Map.getValue` throws a `NoSuchElementException`
 * quoting the key ("Key 42 is missing in the map.") for one it lacks — none
 * of the "expected" codec exception types.
 */
private object LnLookupKeyCodec : StateCodec<Int> {
    private val names = mapOf(1 to "one", 2 to "two")

    override fun encode(value: Int): String = names.getValue(value)

    override fun decode(string: String): Int = names.entries.first { it.value == string }.key
}

/** Fails with a `ClassCastException` quoting the key: none of the kinds a narrow catch would expect. */
private object LnCastingKeyCodec : StateCodec<Int> {
    override fun encode(value: Int): String = throw ClassCastException("confidential key $value")

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

/** A key of a codec-less branch whose `toString()` fails with a `NullPointerException` quoting it. */
private class LnNullingKey(
    val id: Int,
) {
    override fun toString(): String = throw NullPointerException("hidden identity $id")
}

private class LnIntStore(
    id: Int,
    root: LnRoot,
) : Store<LnIntStore>(root.byInt.at(id))

private class LnLookupStore(
    id: Int,
    root: LnRoot,
) : Store<LnLookupStore>(root.lookup.at(id))

private class LnCastStore(
    id: Int,
    root: LnRoot,
) : Store<LnCastStore>(root.cast.at(id))

private class LnOpaqueStore(
    key: LnOpaqueKey,
    root: LnRoot,
) : Store<LnOpaqueStore>(root.opaque.at(key))

private class LnNullingStore(
    key: LnNullingKey,
    root: LnRoot,
) : Store<LnNullingStore>(root.nulling.at(key))

private class LnCountedStore(
    id: Int,
    root: LnRoot,
) : Store<LnCountedStore>(root.counted.at(id))

private class LnRoot(
    counting: LnCountingKeyCodec,
) : Root("ln") {
    val byInt by keyed<Int, LnIntStore>(keyCodec = LnRefusingKeyCodec)
    val lookup by keyed<Int, LnLookupStore>(keyCodec = LnLookupKeyCodec)
    val cast by keyed<Int, LnCastStore>(keyCodec = LnCastingKeyCodec)
    val opaque by keyed<LnOpaqueKey, LnOpaqueStore>()
    val nulling by keyed<LnNullingKey, LnNullingStore>()
    val counted by keyed<Int, LnCountedStore>(keyCodec = counting)
}

/**
 * `create`/`getOrCreate` name the key once, up front, and a key the codec
 * refuses — with whatever exception it throws — fails with the branch
 * context every other tree error carries: naming the root and the branch,
 * never the key or the codec's own message.
 */
class KeyedLeafNameTest {
    /** [withheld]: fragments of the key or of the codec's message that must not appear. */
    private fun assertNamesTheBranchOnly(
        error: IllegalStateException,
        branch: String,
        vararg withheld: String,
    ) {
        val message = assertNotNull(error.message)
        assertTrue("root 'ln'" in message, message)
        assertTrue(branch in message, message)
        for (fragment in withheld) assertFalse(fragment in message, "'$fragment' is withheld: $message")
        assertNull(error.cause, "the codec's exception is not attached: its message may quote the key")
    }

    @Test
    fun createRefusedByTheKeyCodecNamesTheBranchAndReservesNothing() {
        val root = LnRoot(LnCountingKeyCodec())
        val error = assertFailsWith<IllegalStateException> { root.byInt.create(-7) { LnIntStore(it, root) } }
        assertNamesTheBranchOnly(error, "byInt", "confidential", "-7")
        assertNull(root[root.byInt, -7])
        assertTrue(root.entries(root.byInt).isEmpty(), "nothing was reserved")
        val later = root.byInt.create(7) { LnIntStore(it, root) }
        assertSame(later, root[root.byInt, 7], "the branch still works for keys the codec accepts")
    }

    @Test
    fun getOrCreateRefusedByTheKeyCodecNamesTheBranchAndReservesNothing() {
        val root = LnRoot(LnCountingKeyCodec())
        val error = assertFailsWith<IllegalStateException> { root.byInt.getOrCreate(-7) { LnIntStore(it, root) } }
        assertNamesTheBranchOnly(error, "byInt", "confidential", "-7")
        assertNull(root[root.byInt, -7])
        assertTrue(root.entries(root.byInt).isEmpty())
    }

    @Test
    fun createRefusedByALookupTableCodecNamesTheBranchAndReservesNothing() {
        val root = LnRoot(LnCountingKeyCodec())
        val error = assertFailsWith<IllegalStateException> { root.lookup.create(42) { LnLookupStore(it, root) } }
        assertNamesTheBranchOnly(error, "lookup", "42", "missing")
        assertNull(root[root.lookup, 42])
        assertTrue(root.entries(root.lookup).isEmpty(), "nothing was reserved")
        val later = root.lookup.create(1) { LnLookupStore(it, root) }
        assertSame(later, root[root.lookup, 1], "the branch still works for keys the table has")
    }

    @Test
    fun getOrCreateRefusedByALookupTableCodecNamesTheBranchAndReservesNothing() {
        val root = LnRoot(LnCountingKeyCodec())
        val error = assertFailsWith<IllegalStateException> { root.lookup.getOrCreate(42) { LnLookupStore(it, root) } }
        assertNamesTheBranchOnly(error, "lookup", "42", "missing")
        assertNull(root[root.lookup, 42])
        assertTrue(root.entries(root.lookup).isEmpty())
    }

    @Test
    fun aCodecFailingWithAClassCastIsWrappedTheSameWay() {
        val root = LnRoot(LnCountingKeyCodec())
        val error = assertFailsWith<IllegalStateException> { root.cast.create(9) { LnCastStore(it, root) } }
        assertNamesTheBranchOnly(error, "cast", "confidential", "9")
        assertTrue(root.entries(root.cast).isEmpty())
    }

    @Test
    fun aCodecLessBranchWrapsAThrowingToStringTheSameWay() {
        val root = LnRoot(LnCountingKeyCodec())
        val key = LnOpaqueKey(1)
        val error = assertFailsWith<IllegalStateException> { root.opaque.create(key) { LnOpaqueStore(it, root) } }
        assertNamesTheBranchOnly(error, "opaque", "hidden", "identity 1")
        assertTrue(root.entries(root.opaque).isEmpty())
    }

    @Test
    fun aCodecLessBranchWrapsAToStringFailingWithANullPointerTheSameWay() {
        val root = LnRoot(LnCountingKeyCodec())
        val key = LnNullingKey(3)
        val error =
            assertFailsWith<IllegalStateException> { root.nulling.getOrCreate(key) { LnNullingStore(it, root) } }
        assertNamesTheBranchOnly(error, "nulling", "hidden", "identity 3")
        assertTrue(root.entries(root.nulling).isEmpty())
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
