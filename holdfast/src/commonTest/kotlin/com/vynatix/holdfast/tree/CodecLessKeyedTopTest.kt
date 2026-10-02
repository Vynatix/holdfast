@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.RestorePolicy
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.bridge.IntCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A key whose `toString()` must never reach a persisted text. */
private class CkLeakingKey {
    override fun toString(): String = "LEAKED-KEY-TEXT"
}

private class CkOpaqueStore : Store<CkOpaqueStore>() {
    val n by state(codec = IntCodec) { 0 }
}

private class CkApp : Store<CkApp>() {
    val opaque by keyed<Any, CkOpaqueStore> { CkOpaqueStore() }
}

/** A keyed branch without a key codec is never encoded — also when it is the captured node itself. */
class CodecLessKeyedTopTest {
    @Test
    fun aCodecLessKeyedBranchAsTheCapturedNodeEncodesEmptyAndListedAndRoundTrips() {
        val root = CkApp()
        root.opaque.create(CkLeakingKey()) action { n mutate 7 }
        val captured = root.tree.snapshot(root.opaque)
        assertEquals(1, captured.children.size, "the capture itself holds the entry")

        val text = captured.encode()
        assertEquals(
            """{"format":"holdfast.tree","v":1,"receiver":"CkApp","scope":"All","path":["opaque"],""" +
                """"tree":{"kind":"keyed","entries":{}},"skipped":[["opaque"]]}""",
            text,
        )
        assertFalse("LEAKED-KEY-TEXT" in text, "a key's toString() never reaches a persisted text")

        val decoded = root.tree.decode(text)
        assertSame(root.opaque, decoded.node)
        assertTrue(decoded.children.isEmpty())
        assertEquals(emptyList<List<String>>(), decoded.unresolvedPaths, "a branch this receiver skips is not 'unresolved'")
        assertTrue(decoded.equalsEncodable(captured))
        assertTrue(captured.equalsEncodable(decoded))
        val report = root.tree.restore(decoded, RestorePolicy.Strict).getOrThrow()
        assertTrue(report.perNode.isEmpty())
    }

    @Test
    fun equalsEncodableTreatsACodecLessKeyedCapturedNodeAsEmptyLikeEncodeDoes() {
        val root = CkApp()
        val before = root.tree.snapshot(root.opaque)
        root.opaque.create(CkLeakingKey())
        val after = root.tree.snapshot(root.opaque)
        assertNotEquals(before, after, "value equality sees the new entry")
        assertTrue(before.equalsEncodable(after), "the encodable projection of a codec-less keyed node is empty")
        assertTrue(after.equalsEncodable(before))
    }
}
