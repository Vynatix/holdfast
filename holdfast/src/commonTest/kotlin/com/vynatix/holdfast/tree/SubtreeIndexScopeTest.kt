@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.SnapshotEntry
import com.vynatix.holdfast.Store
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

private class SiAStore : Store<SiAStore>() {
    val a by state { 1 }
}

private class SiBStore : Store<SiBStore>() {
    val b by state { 2 }
}

private class SiApp : Store<SiApp>() {
    val sa = SiAStore()
    val sb = SiBStore()
    val left by group { listOf(sa) }
    val right by group { listOf(sb) }
}

/** A subtree taken out of a whole capture with `tree[node]` reads only what lies under that node. */
class SubtreeIndexScopeTest {
    @Test
    fun aSubtreeObtainedFromAWholeCaptureReadsOnlyItsOwnLeaves() {
        val root = SiApp()
        val whole = root.tree.snapshot()
        val right = whole[root.right]!!

        assertEquals(2, right[root.sb.b])
        assertNotNull(right[root.tree.nodeOf(root.sb)!!])
        assertSame(right, right[root.right])

        assertNull(right[root.sa.a], "a state of a store outside the subtree reads null")
        assertEquals(SnapshotEntry.Absent, right.entry(root.sa.a))
        assertNull(right[root.tree.nodeOf(root.sa)!!], "a node outside the subtree is not inside this capture")
        assertNull(right[root.left])
        assertNull(right[root.tree.node], "nor is the receiver's own node")

        assertEquals(1, whole[root.sa.a], "the whole capture still answers everything")
        assertNotNull(whole[root.tree.nodeOf(root.sa)!!])
    }

    @Test
    fun aLeafSubtreeObtainedFromAWholeCaptureIsScopedToo() {
        val root = SiApp()
        val whole = root.tree.snapshot()
        val leaf = whole[root.tree.nodeOf(root.sb)!!]!!
        assertEquals(2, leaf[root.sb.b])
        assertNull(leaf[root.sa.a])
        assertNull(leaf[root.right])
        assertNull(leaf[root.tree.nodeOf(root.sa)!!])
    }

    @Test
    fun aSubtreeFromAWholeCaptureEqualsADirectCaptureOfThatNode() {
        val root = SiApp()
        val fromWhole = root.tree.snapshot()[root.right]!!
        val direct = root.tree.snapshot(root.right)
        assertEquals(direct, fromWhole)
        assertEquals(fromWhole, direct)
        assertEquals(direct.hashCode(), fromWhole.hashCode())
        assertEquals(direct.encode(), fromWhole.encode())
    }

    @Test
    fun aSubtreeStillAnswersAbsentForAnOutsideStateAfterThatStoreOrTheReceiverDisposes() {
        val root = SiApp()
        val a = root.sa.a
        val b = root.sb.b
        val right = root.tree.snapshot()[root.right]!!
        root.sa.dispose()
        assertEquals(SnapshotEntry.Absent, right.entry(a))
        root.dispose()
        assertEquals(SnapshotEntry.Absent, right.entry(a))
        assertEquals(2, right[b], "the subtree's own leaf keeps reading after dispose")
    }
}
