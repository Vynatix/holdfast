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
    val id: Int,
) : Store<LnIntStore>()

private class LnLookupStore(
    val id: Int,
) : Store<LnLookupStore>()

private class LnCastStore(
    val id: Int,
) : Store<LnCastStore>()

private class LnOpaqueStore(
    val key: LnOpaqueKey,
) : Store<LnOpaqueStore>()

private class LnNullingStore(
    val key: LnNullingKey,
) : Store<LnNullingStore>()

private class LnCountedStore(
    val id: Int,
) : Store<LnCountedStore>()

private class LnParent(
    counting: LnCountingKeyCodec,
) : Store<LnParent>() {
    val byInt by stores<Int, LnIntStore>(keyCodec = LnRefusingKeyCodec) { LnIntStore(it) }
    val lookup by stores<Int, LnLookupStore>(keyCodec = LnLookupKeyCodec) { LnLookupStore(it) }
    val cast by stores<Int, LnCastStore>(keyCodec = LnCastingKeyCodec) { LnCastStore(it) }
    val opaque by stores<LnOpaqueKey, LnOpaqueStore> { LnOpaqueStore(it) }
    val nulling by stores<LnNullingKey, LnNullingStore> { LnNullingStore(it) }
    val counted by stores<Int, LnCountedStore>(keyCodec = counting) { LnCountedStore(it) }
}

/**
 * `create`/`getOrCreate` name the key once, up front, and a key the codec
 * refuses — with whatever exception it throws — fails with the branch
 * context every other tree error carries: naming the declaring store and
 * the branch, never the key or the codec's own message.
 */
class KeyedLeafNameTest {
    /** [withheld]: fragments of the key or of the codec's message that must not appear. */
    private fun assertNamesTheBranchOnly(
        error: IllegalStateException,
        branch: String,
        vararg withheld: String,
    ) {
        val message = assertNotNull(error.message)
        assertTrue("LnParent: " in message, message)
        assertTrue(branch in message, message)
        for (fragment in withheld) assertFalse(fragment in message, "'$fragment' is withheld: $message")
        assertNull(error.cause, "the codec's exception is not attached: its message may quote the key")
    }

    @Test
    fun createRefusedByTheKeyCodecNamesTheBranchAndReservesNothing() {
        val parent = LnParent(LnCountingKeyCodec())
        val error = assertFailsWith<IllegalStateException> { parent.byInt.create(-7) }
        assertNamesTheBranchOnly(error, "byInt", "confidential", "-7")
        assertNull(parent.byInt[-7])
        assertTrue(parent.byInt.entries.isEmpty(), "nothing was reserved")
        val later = parent.byInt.create(7)
        assertSame(later, parent.byInt[7], "the branch still works for keys the codec accepts")
    }

    @Test
    fun getOrCreateRefusedByTheKeyCodecNamesTheBranchAndReservesNothing() {
        val parent = LnParent(LnCountingKeyCodec())
        val error = assertFailsWith<IllegalStateException> { parent.byInt.getOrCreate(-7) }
        assertNamesTheBranchOnly(error, "byInt", "confidential", "-7")
        assertNull(parent.byInt[-7])
        assertTrue(parent.byInt.entries.isEmpty())
    }

    @Test
    fun createRefusedByALookupTableCodecNamesTheBranchAndReservesNothing() {
        val parent = LnParent(LnCountingKeyCodec())
        val error = assertFailsWith<IllegalStateException> { parent.lookup.create(42) }
        assertNamesTheBranchOnly(error, "lookup", "42", "missing")
        assertNull(parent.lookup[42])
        assertTrue(parent.lookup.entries.isEmpty(), "nothing was reserved")
        val later = parent.lookup.create(1)
        assertSame(later, parent.lookup[1], "the branch still works for keys the table has")
    }

    @Test
    fun getOrCreateRefusedByALookupTableCodecNamesTheBranchAndReservesNothing() {
        val parent = LnParent(LnCountingKeyCodec())
        val error = assertFailsWith<IllegalStateException> { parent.lookup.getOrCreate(42) }
        assertNamesTheBranchOnly(error, "lookup", "42", "missing")
        assertNull(parent.lookup[42])
        assertTrue(parent.lookup.entries.isEmpty())
    }

    @Test
    fun aCodecFailingWithAClassCastIsWrappedTheSameWay() {
        val parent = LnParent(LnCountingKeyCodec())
        val error = assertFailsWith<IllegalStateException> { parent.cast.create(9) }
        assertNamesTheBranchOnly(error, "cast", "confidential", "9")
        assertTrue(parent.cast.entries.isEmpty())
    }

    @Test
    fun aCodecLessBranchWrapsAThrowingToStringTheSameWay() {
        val parent = LnParent(LnCountingKeyCodec())
        val key = LnOpaqueKey(1)
        val error = assertFailsWith<IllegalStateException> { parent.opaque.create(key) }
        assertNamesTheBranchOnly(error, "opaque", "hidden", "identity 1")
        assertTrue(parent.opaque.entries.isEmpty())
    }

    @Test
    fun aCodecLessBranchWrapsAToStringFailingWithANullPointerTheSameWay() {
        val parent = LnParent(LnCountingKeyCodec())
        val key = LnNullingKey(3)
        val error =
            assertFailsWith<IllegalStateException> { parent.nulling.getOrCreate(key) }
        assertNamesTheBranchOnly(error, "nulling", "hidden", "identity 3")
        assertTrue(parent.nulling.entries.isEmpty())
    }

    @Test
    fun createAndGetOrCreateNameTheKeyOncePerCall() {
        val counting = LnCountingKeyCodec()
        val parent = LnParent(counting)
        val created = parent.counted.create(1)
        assertEquals(1, counting.encodes)
        repeat(3) { assertSame(created, parent.counted.getOrCreate(1)) }
        assertEquals(4, counting.encodes, "a hit names the key once, before the registry is consulted")
        parent.counted.getOrCreate(2)
        assertEquals(5, counting.encodes, "a miss names the key once for its reservation")
    }
}
