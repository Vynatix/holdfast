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

private class CmKeyedStore(
    id: String,
    root: CmRoot,
) : Store<CmKeyedStore>(root.keyed.at(id)) {
    val n by state { 0 }
}

/** Never a member of any root. */
private class CmOutsideStore : Store<CmOutsideStore>() {
    val o by state { 9 }
}

private class CmRoot : Root("cm") {
    val sa = CmAStore()
    val sb = CmBStore()
    val left by branch(sa)
    val right by branch(sb)
    val keyed by keyed<String, CmKeyedStore>()
}

/**
 * A capture decides membership when it is taken: a store that was a member
 * of the root then reads `Absent` outside the captured subtree even after
 * it disposed — for a capture taken directly of a subtree as for one cut
 * out of a whole capture, and after the root disposed — while a store that
 * never belonged still throws.
 */
class CaptureTimeMembershipTest {
    @Test
    fun aDirectSubtreeCaptureAnswersAbsentForASiblingMemberDisposedSince() {
        val root = CmRoot()
        val k = root.keyed.create("k") { CmKeyedStore(it, root) }
        val n = k.n
        val a = root.sa.a
        val right = root.snapshot(root.right)
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
        val root = CmRoot()
        val k1 = root.keyed.create("k1") { CmKeyedStore(it, root) }
        val k2 = root.keyed.create("k2") { CmKeyedStore(it, root) }
        val n2 = k2.n
        val leaf = root.snapshot(root.nodeOf(k1)!!)
        k2.dispose()
        assertEquals(SnapshotEntry.Absent, leaf.entry(n2))
        assertEquals(0, leaf[k1.n])
    }

    @Test
    fun aStoreThatNeverBelongedStillThrows() {
        val root = CmRoot()
        val outside = CmOutsideStore()
        val o = outside.o
        val right = root.snapshot(root.right)
        val refused = assertFailsWith<IllegalArgumentException> { right.entry(o) }
        assertContains(refused.message!!, "not a member of root 'cm'")
        outside.dispose()
        assertFailsWith<IllegalArgumentException> { right.entry(o) }
    }

    @Test
    fun aStoreThatJoinedSinceTheCaptureReadsAbsentWhileItIsAMember() {
        val root = CmRoot()
        val right = root.snapshot(root.right)
        val later = root.keyed.create("later") { CmKeyedStore(it, root) }
        assertEquals(SnapshotEntry.Absent, right.entry(later.n), "not recorded at capture time: the registry answers")
    }

    @Test
    fun readsThroughADirectSubtreeCaptureStillAnswerAbsentAfterTheRootDisposes() {
        val root = CmRoot()
        val k = root.keyed.create("k") { CmKeyedStore(it, root) }
        val n = k.n
        val a = root.sa.a
        val b = root.sb.b
        val right = root.snapshot(root.right)
        k.dispose()
        root.dispose()
        assertEquals(SnapshotEntry.Absent, right.entry(n))
        assertEquals(SnapshotEntry.Absent, right.entry(a))
        assertEquals(2, right[b])
        assertEquals(SnapshotEntry.Absent, right.entry(CmOutsideStore().o), "a closed registry can no longer tell")
    }

    @Test
    fun aDecodedSubtreeDecidesMembershipAtDecodeTime() {
        val root = CmRoot()
        val k = root.keyed.create("k") { CmKeyedStore(it, root) }
        val n = k.n
        val decoded = root.decode(root.snapshot(root.right).encode())
        k.dispose()
        assertEquals(SnapshotEntry.Absent, decoded.entry(n), "a member at decode time, outside the decoded subtree")
        assertEquals(2, decoded[root.sb.b])
        assertFailsWith<IllegalArgumentException> { decoded.entry(CmOutsideStore().o) }
    }
}
