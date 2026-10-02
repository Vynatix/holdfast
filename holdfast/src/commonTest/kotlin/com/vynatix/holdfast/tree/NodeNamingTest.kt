@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.EncodedSnapshotView
import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.SchemaVersioned
import com.vynatix.holdfast.StateTag
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreAttachment
import com.vynatix.holdfast.StoreAttachmentKey
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.bridge.StringCodec
import com.vynatix.holdfast.internalAttachIfAbsent
import com.vynatix.holdfast.keyedState
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class NnAuthoredStore : Store<NnAuthoredStore>() {
    var initializerRuns = 0
    val draft by state(tags = setOf(StateTag.UserAuthored)) {
        initializerRuns++
        ""
    }
}

private class NnAuthoredFamilyStore : Store<NnAuthoredFamilyStore>() {
    val notes by keyedState<String, String>(codec = StringCodec, keyCodec = StringCodec, tags = setOf(StateTag.UserAuthored)) { "" }
}

private class NnVersionedStore :
    Store<NnVersionedStore>(),
    SchemaVersioned {
    val n by state { 0 }
    override val schemaVersion: Int get() = 2

    override fun migrate(
        from: Int,
        view: EncodedSnapshotView,
    ) = Unit
}

private class NnPlainStore : Store<NnPlainStore>() {
    val n by state { 0 }
}

private class NnOverlayAttachment : StoreAttachment {
    override val persistenceKeys: Set<String> get() = setOf("overlay:key")
}

private val nnOverlayKey = StoreAttachmentKey<NnOverlayAttachment>("naming-test-overlay")

private class NnKeyedStore(
    key: Int,
) : Store<NnKeyedStore>() {
    val n by state { key }
}

/**
 * A child that persists nothing: its group leaf may keep its class name, and
 * its keyed branch has no key codec (Int keys get no default).
 */
private class NnHarmlessStore : Store<NnHarmlessStore>() {
    val plain = NnPlainStore()
    val members by stores { listOf(plain) }
    val byInt by stores<Int, NnKeyedStore> { NnKeyedStore(it) }
}

private class NnApp : Store<NnApp>() {
    val authored = NnAuthoredStore()
    val family = NnAuthoredFamilyStore()
    val versioned = NnVersionedStore()
    val overlaid = NnPlainStore().also { it.internalAttachIfAbsent(nnOverlayKey) { NnOverlayAttachment() } }
    val unpinned by stores { listOf(authored, family, versioned, overlaid) }
    val harmless by store { NnHarmlessStore() }
    val byString by stores<String, NnKeyedStore2> { NnKeyedStore2() }
}

private class NnKeyedStore2 : Store<NnKeyedStore2>() {
    val n by state { 0 }
}

private class NnPinnedApp : Store<NnPinnedApp>() {
    val authored = NnAuthoredStore()
    val versioned = NnVersionedStore()
    val pinned by stores(
        names = mapOf(NnAuthoredStore::class to "authored", NnVersionedStore::class to "versioned"),
    ) { listOf(authored, versioned) }
    val byInt by stores<Int, NnPinnedKeyedStore>(keyCodec = NnIntCodec) { NnPinnedKeyedStore(it) }
}

private object NnIntCodec : com.vynatix.holdfast.StateCodec<Int> {
    override fun encode(value: Int): String = value.toString()

    override fun decode(string: String): Int = string.toInt()
}

private class NnPinnedKeyedStore(
    key: Int,
) : Store<NnPinnedKeyedStore>() {
    val n by state { key }
}

/** T6: the persisted-name self-check. */
class NodeNamingTest {
    @Test
    fun reportsEveryUnpinnedPersistedStoreAndEveryCodecLessKeyedBranch() {
        val root = NnApp()
        val tree = root.tree
        val issues = tree.verifyPersistedNames()
        val byNode = issues.associateBy { it.node }
        val byInt = root.harmless.byInt
        assertEquals(
            setOf(
                tree.nodeOf(root.authored)!!,
                tree.nodeOf(root.family)!!,
                tree.nodeOf(root.versioned)!!,
                tree.nodeOf(root.overlaid)!!,
                byInt,
            ),
            byNode.keys,
        )
        for (store in listOf(root.authored, root.family, root.versioned, root.overlaid)) {
            val issue = byNode.getValue(tree.nodeOf(store)!!)
            assertEquals(NamingIssue.Kind.ClassDerivedNameOnPersistedStore, issue.kind)
            assertContains(issue.message, "named by its store's class")
            assertContains(issue.message, "names =")
        }
        val keyed = byNode.getValue(byInt)
        assertEquals(NamingIssue.Kind.KeyedBranchNotEncodable, keyed.kind)
        assertContains(keyed.message, "keyCodec")
        assertEquals(emptyMap<Int, NnKeyedStore>(), byInt.entries, "reported even when empty")
        assertTrue(issues.none { it.node == tree.nodeOf(root.harmless.plain) }, "a plain store may keep its class name")
        assertTrue(issues.none { it.node == tree.nodeOf(root.harmless) }, "a property-named child is no issue")
        assertTrue(issues.none { it.node == root.byString }, "String keys have a default codec")
        assertTrue(issues.none { it.node == tree.node }, "the receiver's own name is never written")
        assertEquals(0, root.authored.initializerRuns, "the check reads declarations, never values")
    }

    @Test
    fun isCleanWhenPersistedLeavesArePinnedAndKeyedBranchesHaveCodecs() {
        val root = NnPinnedApp()
        root.byInt.create(1)
        assertEquals(emptyList<NamingIssue>(), root.tree.verifyPersistedNames())
        assertEquals(0, root.authored.initializerRuns)
    }

    @Test
    fun scopesToTheGivenSubtree() {
        val root = NnApp()
        val tree = root.tree
        val byInt = root.harmless.byInt
        assertEquals(listOf<StoreNode>(byInt), tree.verifyPersistedNames(tree.nodeOf(root.harmless)!!).map { it.node })
        assertEquals(4, tree.verifyPersistedNames(root.unpinned).size)
        assertEquals(emptyList<NamingIssue>(), tree.verifyPersistedNames(root.byString))
        assertEquals(listOf(NamingIssue.Kind.KeyedBranchNotEncodable), tree.verifyPersistedNames(byInt).map { it.kind })
    }

    @Test
    fun aDisposedLeafIsNotReportedAndTheIssueReadsWell() {
        val root = NnApp()
        // Materialize the group first: a group lambda that lists a disposed store is refused.
        val unpinned = root.unpinned
        root.authored.dispose()
        val issues = root.tree.verifyPersistedNames(unpinned)
        assertEquals(3, issues.size)
        val text = issues.first().toString()
        assertContains(text, "ClassDerivedNameOnPersistedStore")
        assertContains(text, "NnAuthoredFamily")
    }
}
