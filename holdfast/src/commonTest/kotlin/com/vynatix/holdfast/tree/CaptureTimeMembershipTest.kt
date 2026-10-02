@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.SnapshotEntry
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.bridge.IntCodec
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

private class CmAStore : Store<CmAStore>() {
    val a by state { 1 }
}

/** With a codec, so a decoded capture of its leaf reads `b` back. */
private class CmBStore : Store<CmBStore>() {
    val b by state(codec = IntCodec) { 2 }
}

private class CmKeyedStore : Store<CmKeyedStore>() {
    val n by state { 0 }
}

/** Never a member of any tree. */
private class CmOutsideStore : Store<CmOutsideStore>() {
    val o by state { 9 }
}

private class CmApp : Store<CmApp>() {
    val sa = CmAStore()
    val sb = CmBStore()
    val left by stores { listOf(sa) }
    val right by stores { listOf(sb) }
    val keyed by stores<String, CmKeyedStore> { CmKeyedStore() }
}

/**
 * A capture decides membership when it is taken: a store that was under the
 * receiver then reads `Absent` outside the captured subtree even after it
 * disposed — for a capture taken directly of a subtree as for one cut out
 * of a whole capture, and after the receiver disposed (its children are
 * released as subtree roots then, but the capture keeps the structure it
 * was taken from) — while a store that never belonged still throws.
 */
class CaptureTimeMembershipTest {
    @Test
    fun aDirectSubtreeCaptureAnswersAbsentForASiblingMemberDisposedSince() {
        val root = CmApp()
        val k = root.keyed.create("k")
        val n = k.n
        val a = root.sa.a
        val right = root.tree.snapshot(root.right)
        assertEquals(SnapshotEntry.Absent, right.entry(n), "a live member outside the subtree")
        assertEquals(SnapshotEntry.Absent, right.entry(a))

        k.dispose()
        assertEquals(SnapshotEntry.Absent, right.entry(n), "a keyed sibling that left since the capture")
        assertNull(right[n])
        root.sa.dispose()
        assertEquals(SnapshotEntry.Absent, right.entry(a), "a branch sibling that left since the capture")
        assertEquals(2, right[root.sb.b], "the subtree's own leaf keeps reading")
    }

    @Test
    fun aDirectLeafCaptureAnswersAbsentForAKeyedSiblingDisposedSince() {
        val root = CmApp()
        val k1 = root.keyed.create("k1")
        val k2 = root.keyed.create("k2")
        val n2 = k2.n
        val leaf = root.tree.snapshot(root.tree.nodeOf(k1)!!)
        k2.dispose()
        assertEquals(SnapshotEntry.Absent, leaf.entry(n2))
        assertEquals(0, leaf[k1.n])
    }

    @Test
    fun aStoreThatNeverBelongedStillThrows() {
        val root = CmApp()
        val outside = CmOutsideStore()
        val o = outside.o
        val right = root.tree.snapshot(root.right)
        val refused = assertFailsWith<IllegalArgumentException> { right.entry(o) }
        assertContains(refused.message!!, "not under 'CmApp'")
        outside.dispose()
        assertFailsWith<IllegalArgumentException> { right.entry(o) }
    }

    @Test
    fun aStoreThatJoinedSinceTheCaptureReadsAbsentWhileItIsAMember() {
        val root = CmApp()
        val right = root.tree.snapshot(root.right)
        val later = root.keyed.create("later")
        assertEquals(SnapshotEntry.Absent, right.entry(later.n), "not recorded at capture time: the live tree answers")
    }

    @Test
    fun readsThroughADirectSubtreeCaptureStillAnswerAbsentAfterTheRootDisposes() {
        val root = CmApp()
        val k = root.keyed.create("k")
        val n = k.n
        val a = root.sa.a
        val b = root.sb.b
        val right = root.tree.snapshot(root.right)
        val bNode = right.children.single().node as LeafNode
        k.dispose()
        root.dispose()
        assertNull(bNode.parent, "the group member was released as a subtree root on its live links")
        assertEquals("CmB", bNode.name, "and took its class-derived name again")
        assertEquals(SnapshotEntry.Absent, right.entry(n))
        assertEquals(SnapshotEntry.Absent, right.entry(a))
        assertEquals(2, right[b], "the kept capture reads the structure as captured")
        assertEquals(SnapshotEntry.Absent, right.entry(CmOutsideStore().o), "a disposed receiver can no longer tell")
    }

    @Test
    fun aDecodedSubtreeDecidesMembershipAtDecodeTime() {
        val root = CmApp()
        val k = root.keyed.create("k")
        val n = k.n
        val decoded = root.tree.decode(root.tree.snapshot(root.right).encode())
        k.dispose()
        assertEquals(SnapshotEntry.Absent, decoded.entry(n), "a member at decode time, outside the decoded subtree")
        assertEquals(2, decoded[root.sb.b])
        assertFailsWith<IllegalArgumentException> { decoded.entry(CmOutsideStore().o) }
    }
}
