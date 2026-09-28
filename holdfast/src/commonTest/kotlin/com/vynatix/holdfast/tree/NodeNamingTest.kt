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
    root: NnRoot,
) : Store<NnKeyedStore>(root.byInt.at(key)) {
    val n by state { key }
}

private class NnRoot : Root("nn") {
    val authored = NnAuthoredStore()
    val family = NnAuthoredFamilyStore()
    val versioned = NnVersionedStore()
    val overlaid = NnPlainStore().also { it.internalAttachIfAbsent(nnOverlayKey) { NnOverlayAttachment() } }
    val plain = NnPlainStore()
    val unpinned by branch(authored, family, versioned, overlaid)
    val harmless by branch(plain)
    val byInt by keyed<Int, NnKeyedStore>(under = harmless)
    val byString by keyed<String, NnKeyedStore2>()
}

private class NnKeyedStore2(
    key: String,
    root: NnRoot,
) : Store<NnKeyedStore2>(root.byString.at(key)) {
    val n by state { 0 }
}

private class NnPinnedRoot : Root("nn") {
    val authored = NnAuthoredStore()
    val versioned = NnVersionedStore()
    val pinned by branch(authored, versioned).named(authored, "authored").named(versioned, "versioned")
    val byInt by keyed<Int, NnPinnedKeyedStore>(keyCodec = NnIntCodec)
}

private object NnIntCodec : com.vynatix.holdfast.StateCodec<Int> {
    override fun encode(value: Int): String = value.toString()

    override fun decode(string: String): Int = string.toInt()
}

private class NnPinnedKeyedStore(
    key: Int,
    root: NnPinnedRoot,
) : Store<NnPinnedKeyedStore>(root.byInt.at(key)) {
    val n by state { key }
}

/** T6: the persisted-name self-check. */
class NodeNamingTest {
    @Test
    fun reportsEveryUnpinnedPersistedStoreAndEveryCodecLessKeyedBranch() {
        val root = NnRoot()
        val issues = root.verifyPersistedNames()
        val byNode = issues.associateBy { it.node }
        assertEquals(
            setOf(
                root.nodeOf(root.authored)!!,
                root.nodeOf(root.family)!!,
                root.nodeOf(root.versioned)!!,
                root.nodeOf(root.overlaid)!!,
                root.byInt,
            ),
            byNode.keys,
        )
        for (store in listOf(root.authored, root.family, root.versioned, root.overlaid)) {
            val issue = byNode.getValue(root.nodeOf(store)!!)
            assertEquals(NamingIssue.Kind.ClassDerivedNameOnPersistedStore, issue.kind)
            assertContains(issue.message, "named(store")
        }
        val keyed = byNode.getValue(root.byInt)
        assertEquals(NamingIssue.Kind.KeyedBranchNotEncodable, keyed.kind)
        assertContains(keyed.message, "keyCodec")
        assertEquals(emptyMap<Int, NnKeyedStore>(), root.entries(root.byInt), "reported even when empty")
        assertTrue(issues.none { it.node == root.nodeOf(root.plain) }, "a plain store may keep its class name")
        assertTrue(issues.none { it.node == root.byString }, "String keys have a default codec")
        assertEquals(0, root.authored.initializerRuns, "the check reads declarations, never values")
    }

    @Test
    fun isCleanWhenPersistedLeavesArePinnedAndKeyedBranchesHaveCodecs() {
        val root = NnPinnedRoot()
        root.byInt.create(1) { NnPinnedKeyedStore(it, root) }
        assertEquals(emptyList<NamingIssue>(), root.verifyPersistedNames())
        assertEquals(0, root.authored.initializerRuns)
    }

    @Test
    fun scopesToTheGivenSubtree() {
        val root = NnRoot()
        assertEquals(listOf(root.byInt), root.verifyPersistedNames(root.harmless).map { it.node })
        assertEquals(4, root.verifyPersistedNames(root.unpinned).size)
        assertEquals(emptyList<NamingIssue>(), root.verifyPersistedNames(root.byString))
        assertEquals(listOf(NamingIssue.Kind.KeyedBranchNotEncodable), root.verifyPersistedNames(root.byInt).map { it.kind })
    }

    @Test
    fun aDisposedLeafIsNotReportedAndTheIssueReadsWell() {
        val root = NnRoot()
        root.authored.dispose()
        val issues = root.verifyPersistedNames(root.unpinned)
        assertEquals(3, issues.size)
        val text = issues.first().toString()
        assertContains(text, "ClassDerivedNameOnPersistedStore")
        assertContains(text, "NnAuthoredFamily")
    }
}
