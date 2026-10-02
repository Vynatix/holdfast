@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.SnapshotFormatException
import com.vynatix.holdfast.StateCodec
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.bridge.IntCodec
import com.vynatix.holdfast.snapshot
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

private const val LEAKY_KEY = "leaked-key-text"

/** Decodes by slicing past the end: throws an index exception, not one of the "expected" codec exception types. */
private object KfSlicingCodec : StateCodec<String> {
    override fun encode(value: String): String = value

    override fun decode(string: String): String = string.substring(0, 100)
}

/** A non-canonical key codec: `"1"` and `"01"` both decode to `1`. */
private object KfIntKeyCodec : StateCodec<Int> {
    override fun encode(value: Int): String = value.toString()

    override fun decode(string: String): Int = string.toInt()
}

private class KfSlicedStore : Store<KfSlicedStore>() {
    val n by state(codec = IntCodec) { 0 }
}

private class KfIntStore(
    id: Int,
) : Store<KfIntStore>() {
    val n by state(codec = IntCodec) { id }
}

private class KfApp : Store<KfApp>() {
    val bySlice by stores<String, KfSlicedStore>(keyCodec = KfSlicingCodec) { KfSlicedStore() }
    val byInt by stores<Int, KfIntStore>(keyCodec = KfIntKeyCodec) { KfIntStore(it) }
}

/** A key codec's failure on decode is a `SnapshotFormatException` naming the branch, whatever the codec threw. */
class KeyCodecFailureTest {
    @Test
    fun anyExceptionFromTheKeyCodecIsAFormatErrorNamingTheBranchNotTheKey() {
        val root = KfApp()
        val store = root.bySlice.create(LEAKY_KEY)
        val text = root.tree.snapshot().encode()
        assertContains(text, """"entries":{"$LEAKY_KEY":""")

        val failure = assertFailsWith<SnapshotFormatException> { root.tree.decode(text) }
        assertContains(failure.message!!, "bySlice")
        assertContains(failure.message!!, "threw")
        assertFalse(LEAKY_KEY in failure.message!!, "the key text is never quoted")
        assertNull(failure.cause, "the codec's exception is not chained: its message may quote the key")

        // The same codec decodes a keyed segment of `path`: a failure there is the same format error.
        val leafText = root.tree.snapshot(root.tree.nodeOf(store)!!).encode()
        assertContains(leafText, """"path":["bySlice","$LEAKY_KEY"]""")
        val pathFailure = assertFailsWith<SnapshotFormatException> { root.tree.decode(leafText) }
        assertContains(pathFailure.message!!, "bySlice")
        assertFalse(LEAKY_KEY in pathFailure.message!!)
    }

    @Test
    fun twoEntriesDecodingToOneKeyAreAFormatErrorNamingTheBranchNotTheKeys() {
        val root = KfApp()
        val store = root.byInt.create(1)
        val body = store.snapshot().encode()
        val entry = """{"kind":"leaf","store":$body}"""
        val text = root.tree.snapshot(root.byInt).encode()
        assertContains(text, """"entries":{"1":$entry}""", message = "a keyed entry is a node object")

        val doubled = text.replace(""""entries":{"1":$entry}""", """"entries":{"01":$entry,"1":$entry}""")
        val failure = assertFailsWith<SnapshotFormatException> { root.tree.decode(doubled) }
        assertContains(failure.message!!, "byInt")
        assertContains(failure.message!!, "one key")
        assertFalse("01" in failure.message!!, "the key text is never quoted")
        assertNull(failure.cause)

        // Distinct keys still decode, and the text this receiver wrote still round-trips.
        val decoded = root.tree.decode(text)
        assertEquals(emptyList<List<String>>(), decoded.unresolvedPaths)
        assertEquals(1, decoded[store.n])
    }
}
