@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.RestorePolicy
import com.vynatix.holdfast.SnapshotScope
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.bridge.StringCodec
import com.vynatix.holdfast.snapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Nothing tagged: under `SnapshotScope.UserAuthored` this keyed leaf captures nothing. */
private class BlThreadStore(
    id: String,
    root: BlRoot,
) : Store<BlThreadStore>(root.threads.at(id)) {
    val title by state(codec = StringCodec) { "thread $id" }
}

private class BlRoot : Root("app") {
    val threads by keyed<String, BlThreadStore>()
}

private const val BL_HEAD = """{"format":"holdfast.tree","v":1,"scope":"All","path":["threads","t1"],"""

/**
 * A keyed leaf text written without a body (`{"kind":"leaf"}`, what a
 * `UserAuthored` capture of an untagged keyed leaf produces) holds nothing
 * to restore: with no live store under its key it decodes as an empty leaf
 * and is NOT a pending key. A body at a keyed path with no live store is.
 */
class BodyLessKeyedLeafDecodeTest {
    @Test
    fun aBodyLessKeyedLeafWithNoLiveStoreDecodesAsAnEmptyLeafThatIsNotPending() {
        val root = BlRoot()
        val t1 = root.threads.create("t1") { BlThreadStore(it, root) }
        val text = root.snapshot(root.nodeOf(t1)!!, SnapshotScope.UserAuthored).encode()
        assertEquals(
            """{"format":"holdfast.tree","v":1,"scope":"UserAuthored","path":["threads","t1"],""" +
                """"tree":{"kind":"leaf"},"skipped":[]}""",
            text,
            "an untagged keyed leaf under UserAuthored is written without a body",
        )
        t1.dispose()

        val decoded = root.decode(text)
        assertEquals(emptyList<List<String>>(), decoded.unresolvedPaths, "the keyed segment resolves via the key codec")
        assertTrue(decoded.isLeaf)
        val leaf = assertIs<LeafNode>(decoded.node)
        assertEquals("t1", leaf.key)
        assertSame(root.threads, leaf.parent)
        assertEquals(NameOrigin.Key, leaf.nameOrigin)
        assertNull(leaf.store, "no store is live under the key")
        assertNull(decoded.leaf, "nothing was written at the leaf")
        assertSame(decoded, decoded[leaf])
        assertEquals(emptySet<String>(), decoded.pendingKeys(root.threads), "a body-less leaf holds nothing to restore")

        // Restoring it is a no-op, under Strict too, and creates no store.
        val report = root.restore(decoded, RestorePolicy.Strict).getOrThrow()
        assertTrue(report.perNode.isEmpty())
        assertTrue(report.skipped.isEmpty())
        assertTrue(report.rebound.isEmpty())
        assertNull(root[root.threads, "t1"])
    }

    @Test
    fun atOneKeyedPathWithNoLiveStoreABodyIsPendingAndNoBodyIsNot() {
        val root = BlRoot()
        val t1 = root.threads.create("t1") { BlThreadStore(it, root) }
        t1 action { title mutate "first" }
        val body = t1.snapshot().encode()
        t1.dispose()
        val withBody = BL_HEAD + """"tree":{"kind":"leaf","store":$body},"skipped":[]}"""
        val bodyLess = BL_HEAD + """"tree":{"kind":"leaf"},"skipped":[]}"""

        val pending = root.decode(withBody)
        assertNotNull(pending.leaf, "the body is retained")
        assertEquals(setOf("t1"), pending.pendingKeys(root.threads), "a body with no live store: create, then restore")

        val empty = root.decode(bodyLess)
        assertNull(empty.leaf)
        assertEquals(emptySet<String>(), empty.pendingKeys(root.threads), "no body: nothing to create a store for")
        assertEquals(emptyList<List<String>>(), empty.unresolvedPaths)
        assertEquals(assertIs<LeafNode>(pending.node).key, assertIs<LeafNode>(empty.node).key, "the same keyed place")
    }

    @Test
    fun aBodyLessKeyedLeafDecodesToTheLiveLeafWhileItsStoreIsLive() {
        val root = BlRoot()
        val t1 = root.threads.create("t1") { BlThreadStore(it, root) }
        val node = root.nodeOf(t1)!!
        val text = root.snapshot(node, SnapshotScope.UserAuthored).encode()

        val decoded = root.decode(text)
        assertSame(node, decoded.node, "the live leaf, not a minted one")
        assertNull(decoded.leaf)
        assertEquals(emptySet<String>(), decoded.pendingKeys(root.threads))

        t1 action { title mutate "kept" }
        val report = root.restore(decoded, RestorePolicy.Strict).getOrThrow()
        assertTrue(report.perNode.isEmpty())
        assertEquals("kept", t1.title.value, "an empty leaf restores nothing")
    }
}
