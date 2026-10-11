@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.RestorePolicy
import com.vynatix.holdfast.SnapshotEntry
import com.vynatix.holdfast.SnapshotScope
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.bridge.IntCodec
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Nothing tagged: under `SnapshotScope.UserAuthored` this leaf captures nothing. */
private class LsPlainStore : Store<LsPlainStore>() {
    val x by state(codec = IntCodec) { 1 }
}

private class LsKeyedStore : Store<LsKeyedStore>() {
    val n by state { 0 }
}

private class LsApp : Store<LsApp>() {
    val plain = LsPlainStore()
    val left by group { listOf(plain named "plain") }
    val keyed by keyed<String, LsKeyedStore> { LsKeyedStore() }
}

/** `tree.snapshot(leafNode, …)`: the requested node is always returned, empty when its leaf captured nothing. */
class LeafSnapshotTest {
    @Test
    fun aLeafWithNothingCapturedUnderUserAuthoredIsReturnedEmpty() {
        val root = LsApp()
        val node = root.tree.nodeOf(root.plain)!!
        val tree = root.tree.snapshot(node, SnapshotScope.UserAuthored)
        assertSame(node, tree.node)
        assertTrue(tree.isLeaf)
        assertTrue(tree.children.isEmpty())
        assertNull(tree.leaf, "nothing was captured at the leaf")
        assertEquals(SnapshotScope.UserAuthored, tree.scope)
        assertEquals(SnapshotEntry.Absent, tree.entry(root.plain.x))
        assertNull(tree[root.plain.x])
        assertSame(tree, tree[node])
    }

    @Test
    fun aLeafWhoseStoreWasDisposedIsReturnedEmptyInEveryScope() {
        val root = LsApp()
        val k = root.keyed.create("k")
        val n = k.n
        val node = root.tree.nodeOf(k)!!
        k.dispose()
        for (scope in listOf(SnapshotScope.All, SnapshotScope.UserAuthored, SnapshotScope.Raw)) {
            val tree = root.tree.snapshot(node, scope)
            assertSame(node, tree.node, "under $scope")
            assertTrue(tree.isLeaf)
            assertTrue(tree.children.isEmpty())
            assertNull(tree.leaf, "under $scope")
            assertEquals(SnapshotEntry.Absent, tree.entry(n))
        }
    }

    @Test
    fun anEmptyLeafCaptureEncodesDecodesRendersAndCompares() {
        val root = LsApp()
        val node = root.tree.nodeOf(root.plain)!!
        val empty = root.tree.snapshot(node, SnapshotScope.UserAuthored)

        val text = empty.encode()
        assertEquals(
            """{"format":"holdfast.tree","v":1,"receiver":"LsApp","scope":"UserAuthored","path":["left","plain"],""" +
                """"tree":{"kind":"leaf"},"skipped":[]}""",
            text,
        )
        val decoded = root.tree.decode(text)
        assertSame(node, decoded.node)
        assertTrue(decoded.isLeaf)
        assertNull(decoded.leaf)
        assertEquals(emptyList<List<String>>(), decoded.unresolvedPaths)
        assertTrue(decoded.equalsEncodable(empty))
        assertTrue(empty.equalsEncodable(decoded))

        val again = root.tree.snapshot(node, SnapshotScope.UserAuthored)
        assertEquals(empty, again)
        assertEquals(empty.hashCode(), again.hashCode())
        assertContains(empty.render(), "plain (leaf, pinned)")

        val report = root.tree.restore(decoded, RestorePolicy.Strict).getOrThrow()
        assertTrue(report.perNode.isEmpty())
        assertTrue(report.skipped.isEmpty())
    }
}
