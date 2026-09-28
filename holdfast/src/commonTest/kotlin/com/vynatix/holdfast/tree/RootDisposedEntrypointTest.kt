@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.effect
import com.vynatix.holdfast.internalAttachment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class DisposedLeafStore : Store<DisposedLeafStore>() {
    val n by state { 0 }
}

private class DisposedKeyedStore(
    id: String,
    root: DisposedProbeRoot,
) : Store<DisposedKeyedStore>(root.keyed.at(id)) {
    val n by state { 0 }
}

private class DisposedProbeRoot : Root() {
    val leafStore = DisposedLeafStore()
    val leaf by branch(leafStore)
    val keyed by keyed<String, DisposedKeyedStore>()

    /** A declaration not yet bound, so a row can bind it after dispose. */
    fun lateBranch(): BranchDeclaration = branch(DisposedLeafStore())

    fun lateKeyed(): KeyedDeclaration<String, DisposedKeyedStore> = keyed()
}

private class RootEntrypoint(
    val name: String,
    val call: (DisposedProbeRoot) -> Unit,
)

/**
 * Every `Root` and `KeyedBranch` entrypoint throws `IllegalStateException`
 * naming "disposed" once the root is disposed; the documented reads keep
 * working. Table-driven like the core `DisposedEntrypointTest`, so a new
 * tree entrypoint gets a row here.
 */
class RootDisposedEntrypointTest {
    private val gated =
        listOf(
            RootEntrypoint("nodes") { it.nodes },
            RootEntrypoint("children") { it.children(it) },
            RootEntrypoint("get") { it[it.keyed, "k"] },
            RootEntrypoint("entries") { it.entries(it.keyed) },
            RootEntrypoint("nodeOf") { it.nodeOf(it.leafStore) },
            RootEntrypoint("snapshot") { it.snapshot() },
            RootEntrypoint("restore") { it.restore(DisposedProbeRoot().snapshot()) },
            RootEntrypoint("reset") { it.reset() },
            RootEntrypoint("decode") { it.decode("{}") },
            RootEntrypoint("verifyPersistedNames") { it.verifyPersistedNames() },
            RootEntrypoint("value.value (never read before)") { it.value.value },
            RootEntrypoint("value observed (never read before)") { it.value effect { } },
            RootEntrypoint("bindToScope") { it.bindToScope(CoroutineScope(Dispatchers.Unconfined + Job())) },
            RootEntrypoint("internalSettleNow (never read before)") { it.internalSettleNow() },
            RootEntrypoint("KeyedBranch.at") { it.keyed.at("k") },
            RootEntrypoint("KeyedBranch.create") { r -> r.keyed.create("k") { DisposedKeyedStore(it, r) } },
            RootEntrypoint("KeyedBranch.getOrCreate") { r -> r.keyed.getOrCreate("k") { DisposedKeyedStore(it, r) } },
            RootEntrypoint("branch declaration (provideDelegate)") { r -> r.lateBranch().provideDelegate(r, DisposedProbeRoot::leaf) },
            RootEntrypoint("keyed declaration (provideDelegate)") { r -> r.lateKeyed().provideDelegate(r, DisposedProbeRoot::keyed) },
            RootEntrypoint("internalAddMembershipListener") { r -> r.internalAddMembershipListener(object : LeafMembershipListener() {}) },
        )

    private val exempt =
        listOf(
            RootEntrypoint("isDisposed") { check(it.isDisposed) },
            RootEntrypoint("name / nameOrigin / parent / root") {
                check(
                    it.name == "DisposedProbeRoot" && it.parent == null && it.root === it,
                )
            },
            RootEntrypoint("dispose (idempotent)") { it.dispose() },
            RootEntrypoint("Branch.stores / leafName") { check(it.leaf.leafName(it.leafStore) == "DisposedLeaf") },
            RootEntrypoint("LeafNode.store is null after the root's dispose") {
                check(
                    it.leaf.leaves
                        .single()
                        .store == null,
                )
            },
            RootEntrypoint("isUnder") { check(it.keyed.isUnder(it)) },
            RootEntrypoint("value (the handle)") { check(it.value === it.value) },
            RootEntrypoint("scope / uncaughtObserverHandler") {
                check(it.scope === Store.defaultScope && it.uncaughtObserverHandler == null)
            },
            RootEntrypoint("internalHost") { check(it.internalHost().isDisposed) },
        )

    @Test
    fun everyGatedEntrypointThrowsOnADisposedRoot() {
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
    fun documentedExemptionsWorkOnADisposedRoot() {
        val failures =
            exempt.mapNotNull { entry ->
                runCatching { callOnDisposed(entry) }.exceptionOrNull()?.let { "${entry.name}: threw $it" }
            }
        assertTrue(failures.isEmpty(), failures.joinToString("\n", prefix = "documented to work after dispose:\n"))
    }

    @Test
    fun disposeDetachesEveryLeafWithoutDisposingStoresAndDropsListeners() {
        val root = DisposedProbeRoot()
        val keyed = root.keyed.create("k") { DisposedKeyedStore(it, root) }
        var detachedHeard = 0
        root.internalAddMembershipListener(
            object : LeafMembershipListener() {
                override fun onDetached(leaf: LeafNode) {
                    detachedHeard++
                }
            },
        )
        root.dispose()
        assertTrue(!root.leafStore.isDisposed && !keyed.isDisposed)
        assertNull(root.leafStore.internalAttachment(treeMembershipKey))
        assertNull(keyed.internalAttachment(treeMembershipKey))
        // Disposing a former leaf afterwards no longer reaches the root or its (dropped) listeners.
        keyed.dispose()
        root.leafStore.dispose()
        assertTrue(detachedHeard == 0, "a root's dispose drops listeners instead of notifying them; heard $detachedHeard")
    }

    private fun callOnDisposed(entry: RootEntrypoint) {
        val root = DisposedProbeRoot()
        root.keyed.create("k") { DisposedKeyedStore(it, root) }
        root.dispose()
        entry.call(root)
    }
}
