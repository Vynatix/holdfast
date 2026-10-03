@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.effect
import com.vynatix.holdfast.internalAttachment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class DisposedLeafStore : Store<DisposedLeafStore>() {
    val n by state { 0 }
}

private class DisposedKeyedStore(
    val id: String,
) : Store<DisposedKeyedStore>() {
    val n by state { 0 }
}

private class DisposedProbeParent : Store<DisposedProbeParent>() {
    val n by state { 0 }
    val leaf by store(onParentDispose = KeyedDisposal.Release) { DisposedLeafStore() }
    val keyed by keyed<String, DisposedKeyedStore> { DisposedKeyedStore(it) }

    /** The probe's node, read before it is disposed. */
    var nodeBeforeDispose: LeafNode? = null

    /** A group never read, so its delegate read after dispose would be its first. */
    val group by group { listOf(DisposedLeafStore()) }

    /** A declaration not yet bound, so a row can bind it after dispose. */
    fun lateStore() = store { DisposedLeafStore() }

    /** A group declaration not yet bound. */
    fun lateGroup() = group { listOf(DisposedLeafStore()) }

    /** A keyed declaration not yet bound. */
    fun lateKeyed() = keyed<String, DisposedKeyedStore> { DisposedKeyedStore(it) }
}

/** Hangs a probe under a parent of its own, so the probe's own place can be checked after its dispose. */
private class DisposedProbeGrandparent : Store<DisposedProbeGrandparent>() {
    val probe by store { DisposedProbeParent() }
}

/** One entrypoint and a call to it on a disposed probe, with the `tree` handle and keyed branch obtained before the dispose. */
private class TdeEntrypoint(
    val name: String,
    val call: (probe: DisposedProbeParent, tree: StoreTree, keyed: KeyedBranch<String, DisposedKeyedStore>) -> Unit,
)

/**
 * Every `StoreTree`, `KeyedBranch` and tree-declaration entrypoint throws
 * `IllegalStateException` naming "disposed" once the store is disposed; the
 * documented reads keep working. Table-driven like the core
 * `DisposedEntrypointTest`, so a new tree entrypoint gets a row here.
 */
class TreeDisposedEntrypointTest {
    private val gated =
        listOf(
            TdeEntrypoint("Store.tree (the accessor, a handle existing)") { probe, _, _ -> probe.tree },
            TdeEntrypoint("Store.tree (the accessor, no handle ever made)") { _, _, _ ->
                DisposedProbeParent().also { it.dispose() }.tree
            },
            TdeEntrypoint("children") { _, tree, _ -> tree.children() },
            TdeEntrypoint("stores") { _, tree, _ -> tree.stores() },
            TdeEntrypoint("nodeOf") { probe, tree, _ -> tree.nodeOf(probe) },
            TdeEntrypoint("snapshot") { _, tree, _ -> tree.snapshot() },
            TdeEntrypoint("restore") { _, tree, _ -> tree.restore(DisposedProbeParent().tree.snapshot()) },
            TdeEntrypoint("reset") { _, tree, _ -> tree.reset() },
            TdeEntrypoint("decode") { _, tree, _ -> tree.decode("{}") },
            TdeEntrypoint("verifyPersistedNames") { _, tree, _ -> tree.verifyPersistedNames() },
            TdeEntrypoint("middlewares") { _, tree, _ -> tree.middlewares(object : TreeMiddleware() {}) },
            // The keyed rows call a branch obtained BEFORE the dispose, so they reach the
            // KeyedBranch members' own checks, not the delegate read's (its own row below).
            TdeEntrypoint("KeyedBranch.create") { _, _, keyed -> keyed.create("x") },
            TdeEntrypoint("KeyedBranch.getOrCreate") { _, _, keyed -> keyed.getOrCreate("x") },
            TdeEntrypoint("KeyedBranch.get") { _, _, keyed -> keyed["k"] },
            TdeEntrypoint("KeyedBranch.entries") { _, _, keyed -> keyed.entries() },
            TdeEntrypoint("KeyedBranch.dispose(key)") { _, _, keyed -> keyed.dispose("k") },
            TdeEntrypoint("KeyedBranch.disposeAll") { _, _, keyed -> keyed.disposeAll() },
            TdeEntrypoint("keyed<K, S> { } delegate read") { probe, _, _ -> probe.keyed },
            TdeEntrypoint("keyed<K, S> declaration (provideDelegate)") { probe, _, _ ->
                probe.lateKeyed().provideDelegate(probe, DisposedProbeParent::keyed)
            },
            TdeEntrypoint("store { } delegate read") { probe, _, _ -> probe.leaf },
            TdeEntrypoint("store declaration (provideDelegate)") { probe, _, _ ->
                probe.lateStore().provideDelegate(probe, DisposedProbeParent::leaf)
            },
            TdeEntrypoint("group { } delegate read (never read before)") { probe, _, _ -> probe.group },
            TdeEntrypoint("group { } declaration (provideDelegate)") { probe, _, _ ->
                probe.lateGroup().provideDelegate(probe, DisposedProbeParent::group)
            },
            TdeEntrypoint("internalAddMembershipListener") { probe, _, _ ->
                probe.internalAddMembershipListener(object : LeafMembershipListener() {})
            },
            TdeEntrypoint("internalSettleNow (value never read)") { _, tree, _ -> tree.internalSettleNow() },
            TdeEntrypoint("internalHost (value never read: no host was ever built)") { _, tree, _ -> tree.internalHost() },
            TdeEntrypoint("value (never read)") { _, tree, _ -> tree.value },
            TdeEntrypoint("value observed (never read)") { _, tree, _ -> tree effect { } },
        )

    private val exempt =
        listOf(
            TdeEntrypoint("isDisposed") { probe, _, _ -> check(probe.isDisposed) },
            TdeEntrypoint("node / parent / name / nameOrigin") { probe, tree, _ ->
                check(
                    tree.node === probe.nodeBeforeDispose &&
                        tree.parent == null &&
                        tree.node.name == "DisposedProbeParent" &&
                        tree.node.nameOrigin == NameOrigin.ClassName,
                )
            },
            TdeEntrypoint("isUnder") { _, tree, _ -> check(tree.node.isUnder(tree.node)) },
            TdeEntrypoint("LeafNode.store is null after the store's dispose") { _, tree, _ -> check(tree.node.store == null) },
            TdeEntrypoint("removeMiddleware answers false") { _, tree, _ ->
                check(!tree.removeMiddleware(object : TreeMiddleware() {}))
            },
            TdeEntrypoint("internalSettleCount (its last value)") { _, tree, _ -> check(tree.internalSettleCount == 0L) },
            TdeEntrypoint("dispose (idempotent)") { probe, _, _ -> probe.dispose() },
        )

    @Test
    fun everyGatedEntrypointThrowsOnADisposedStore() {
        val failures =
            gated.mapNotNull { entry ->
                val thrown = runCatching { callOnDisposed(entry) }.exceptionOrNull()
                when {
                    thrown !is IllegalStateException -> "${entry.name}: expected IllegalStateException, got ${thrown ?: "no throw"}"
                    thrown.message?.contains("disposed") != true -> "${entry.name}: message lacks 'disposed': ${thrown.message}"
                    else -> null
                }
            }
        assertTrue(failures.isEmpty(), failures.joinToString("\n", prefix = "not gated on dispose:\n"))
    }

    @Test
    fun documentedExemptionsWorkOnADisposedStore() {
        val failures =
            exempt.mapNotNull { entry ->
                runCatching { callOnDisposed(entry) }.exceptionOrNull()?.let { "${entry.name}: threw $it" }
            }
        assertTrue(failures.isEmpty(), failures.joinToString("\n", prefix = "documented to work after dispose:\n"))
    }

    @Test
    fun aValueReadBeforeTheDisposeAnswersTheLastTreeAfterIt() {
        val probe = DisposedProbeParent()
        val tree = probe.tree
        probe.leaf action { n mutate 4 }
        val last = tree.value
        assertEquals(4, last[probe.leaf.n])
        val leafStore = probe.leaf
        probe.dispose()
        assertSame(last, tree.value)
        assertSame(last, tree.internalSettleNow())
        assertEquals(4, tree.value[leafStore.n])
        assertTrue(tree.internalSettleCount > 0L, "the counters answer their last values")
        assertTrue(tree.internalHost().isDisposed, "a host built before the dispose answers, disposed")
    }

    @Test
    fun disposingAParentReleasesItsChildrenDisposesItsKeyedStoresAndDropsItsListeners() {
        val grandparent = DisposedProbeGrandparent()
        val probe = grandparent.probe
        val leafStore = probe.leaf
        val keyed = probe.keyed.create("k")
        val probeNode = probe.tree.node
        val leafNode = leafStore.tree.node
        val keyedNode = keyed.tree.node
        assertSame(grandparent.tree.node, probeNode.parent)
        assertSame(probeNode, leafNode.parent)
        assertSame(probe.keyed, keyedNode.parent)

        var probeHeard = 0
        probe.internalAddMembershipListener(
            object : LeafMembershipListener() {
                override fun onDetached(leaf: LeafNode) {
                    probeHeard++
                }
            },
        )
        val grandparentHeard = ArrayList<String>()
        grandparent.internalAddMembershipListener(
            object : LeafMembershipListener() {
                override fun onDetached(leaf: LeafNode) {
                    grandparentHeard += leaf.name
                }
            },
        )

        probe.dispose()

        assertFalse(leafStore.isDisposed, "a store { } child is released, never disposed")
        assertTrue(keyed.isDisposed, "a keyed store its branch's factory built is disposed with the parent")
        for ((node, name) in listOf(leafNode to "DisposedLeaf", keyedNode to "DisposedKeyed")) {
            assertNull(node.parent, "$name was released as a subtree root")
            assertEquals(name, node.name)
            assertEquals(NameOrigin.ClassName, node.nameOrigin)
            assertNull(node.key)
        }
        val store = assertNotNull(leafNode.store)
        assertNull(store.internalAttachment(treeMembershipKey)?.parentEdge?.value)
        assertNull(keyedNode.store, "the disposed keyed store dropped its node's store")
        // The disposed store's own node keeps the place it had.
        assertSame(grandparent.tree.node, probeNode.parent)
        assertEquals("probe", probeNode.name)
        assertEquals(NameOrigin.Property, probeNode.nameOrigin)
        assertNull(probeNode.store)
        assertEquals(listOf("probe", "leaf", "k"), grandparentHeard, "the grandparent hears the whole subtree leave")
        assertEquals(listOf<Store<*>>(grandparent), grandparent.tree.stores())

        // Disposing a former child afterwards reaches neither the probe nor its (dropped) listeners.
        keyed.dispose()
        leafStore.dispose()
        assertEquals(0, probeHeard, "a store's dispose drops its listeners instead of notifying them; heard $probeHeard")
        assertEquals(listOf("probe", "leaf", "k"), grandparentHeard)
    }

    private fun callOnDisposed(entry: TdeEntrypoint) {
        val probe = DisposedProbeParent()
        val tree = probe.tree
        probe.nodeBeforeDispose = tree.node
        probe.leaf
        val keyed = probe.keyed
        keyed.create("k")
        probe.dispose()
        entry.call(probe, tree, keyed)
    }
}
