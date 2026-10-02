@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.InitializerGraph
import com.vynatix.holdfast.Stateful
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.StoreLock
import com.vynatix.holdfast.displayName
import com.vynatix.holdfast.settling
import kotlin.reflect.KClass

// Children are declared eagerly and materialized lazily, like states: a
// `store { }`/`stores { }` declaration registers a `ChildEntry` at
// `provideDelegate` and runs nothing; the child lambda runs on the first
// read of the delegate, or when a tree operation needs the subtree
// (`materializeSubtree`, called first by every public structural entrypoint).
// It runs behind the entry's latch in the process-wide `InitializerGraph`
// that state initializers use — so a cycle through child declarations and
// state initializers, on one thread or across threads, throws instead of
// deadlocking — and OUTSIDE every lock of the tree and outside any
// `NoWriteRegion` of its own (a child constructor may open actions); a child
// first read from inside an initializer, a migrate or a derived compute
// inherits that caller's region. A throwing lambda publishes nothing, and
// the next read runs it again.

/**
 * The tree's structure lock: held only for parent-edge compare-and-set, the
 * cycle walk, node field writes and ancestor-chain captures. Never held
 * while running user code, announcing, syncing rings, or holding or taking
 * any registry lock or attachment-slot lock; it may be taken under a store's
 * `transactionLock` (a dispose from inside the store's own action).
 */
internal val treeStructureLock = StoreLock()

/**
 * The child of [entry] (a `store { }` child or a `stores { }` group of the
 * entry's owner), materialized if it has not been: run the lambda, attach
 * the result under the owner, announce it. Joins the settle scope open on
 * this thread, else settles once after return, so a recompute the attach
 * queues runs after the child is visible, never inside the lambda.
 *
 * @throws IllegalStateException if the owner is disposed, on a
 *   materialization cycle, or when the child already has a parent, would be
 *   its own ancestor, or is disposed; the lambda's own throw propagates.
 *   The entry stays retryable.
 * @throws IllegalArgumentException when a group lists a store twice, a
 *   disposed store, pins a class it does not list, or names two leaves alike.
 */
internal fun materializeChild(entry: ChildEntry): Any =
    settling {
        InitializerGraph.Process.hold(entry) { entry.produced ?: materializeHeld(entry) }
    }

/**
 * Materialize every declared child of [ownerNode]'s store that has not run
 * yet, then, recursively, every live child's own. A child that disposed
 * meanwhile is skipped with its subtree; the first throwing lambda of a
 * live store propagates (its entry stays retryable and the entries after it
 * are left unmaterialized). A store whose tree state is gone has nothing to
 * materialize.
 */
internal fun materializeSubtree(ownerNode: LeafNode) {
    val registry = ownerNode.attachment?.registry ?: return
    settling {
        for (entry in registry.declaredEntries()) {
            if (entry.kind != ChildEntry.Kind.Keyed && entry.produced == null) materializeChild(entry)
        }
        for ((leaf, store) in registry.liveChildStores()) {
            if (store.isDisposed) continue
            try {
                materializeSubtree(leaf)
            } catch (e: IllegalStateException) {
                // A child that disposed meanwhile is skipped as a listing skips it,
                // so an ancestor's `children` never throws "disposed" for it.
                if (leaf.store?.isDisposed == false) throw e
            }
        }
    }
}

/**
 * Holding [entry]'s latch: phases 1–7. A failure before the attach
 * registered (phase 4) makes the entry retryable again — before the latch
 * is released, so a waiter that runs the phases next is never reset.
 */
private fun materializeHeld(entry: ChildEntry): Any {
    val registry = entry.registry
    var reachedLive = false
    try {
        val produced =
            when (entry.kind) {
                ChildEntry.Kind.Store -> attachStoreChild(entry) { reachedLive = true }
                ChildEntry.Kind.Group -> attachGroup(entry) { reachedLive = true }
                ChildEntry.Kind.Keyed -> error("${entry.label} is materialized at declaration")
            }
        // Phase 7, LAST, so no reader sees a produced-but-unattached child. On
        // a registry closed meanwhile the attach completed and the child is a
        // subtree root by now: hand it to the caller anyway.
        registry.lock.withLock { if (!registry.disposed) entry.produced = produced }
        return produced
    } finally {
        if (!reachedLive) {
            registry.lock.withLock {
                if (entry.phase == ChildEntry.Phase.Constructing) {
                    entry.phase = ChildEntry.Phase.Declared
                    entry.node = null
                }
            }
        }
    }
}

/** Phases 1–6 of a `store { }` child; [registered] runs right after phase 4. */
private fun attachStoreChild(
    entry: ChildEntry,
    registered: () -> Unit,
): Stateful {
    val registry = entry.registry

    // Phase 1: the lambda, outside every lock of the tree.
    @Suppress("UNCHECKED_CAST")
    val produced = (entry.lambda as () -> Stateful)()
    val child = produced.owningStore
    check(!child.isDisposed) { "${entry.label}: the child lambda returned a disposed store" }
    val attachment = child.treeAttachment()
    // Phase 2: claim the entry; no slot lookup under the registry lock.
    registry.lock.withLock {
        registry.checkOpen()
        entry.phase = ChildEntry.Phase.Constructing
        entry.node = attachment.node
    }
    val target = AttachTarget(attachment, entry.name, entry.origin, key = null)
    val attach = ChildAttach(entry.owner, registry, entry.name, entry.ownerNode, listOf(target), null)
    attachUnder(attach, registered) { entry.phase = ChildEntry.Phase.Live }
    return produced
}

/** Phases 1–6 of a `stores { }` group; [registered] runs right after phase 4. */
private fun attachGroup(
    entry: ChildEntry,
    registered: () -> Unit,
): Branch {
    val registry = entry.registry

    // Phase 1: the lambda, outside every lock; validate; take the attachments.
    @Suppress("UNCHECKED_CAST")
    val listed = (entry.lambda as () -> List<Store<*>>)()

    @Suppress("UNCHECKED_CAST")
    val pins = entry.pin as Map<KClass<out Store<*>>, String>
    val named = nameGroup(entry.label, listed, pins)
    val attachments = listed.map { it.treeAttachment() }
    // Phase 2: the group's node, complete before anything can list it.
    val members = listed.zip(attachments.map { it.node })
    val branch = Branch(entry.ownerNode, entry.name, entry.origin, members, entry.owner, registry)
    registry.lock.withLock {
        registry.checkOpen()
        entry.phase = ChildEntry.Phase.Constructing
        entry.node = branch
    }
    val targets = attachments.mapIndexed { i, a -> AttachTarget(a, named[i].first, named[i].second, key = null) }
    val attach = ChildAttach(entry.owner, registry, entry.name, branch, targets, null)
    attachUnder(attach, registered) { live ->
        entry.phase = ChildEntry.Phase.Live
        branch.live.addAll(live)
    }
    return branch
}

/**
 * Validate a group's listing and name its leaves: by `stores(names = …)`
 * pin (an exact-class lookup), else by class name minus `Store`.
 */
private fun nameGroup(
    label: String,
    listed: List<Store<*>>,
    pins: Map<KClass<out Store<*>>, String>,
): List<Pair<String, NameOrigin>> {
    for ((index, store) in listed.withIndex()) {
        require(listed.indexOfFirst { it === store } == index) { "$label lists ${store.displayName} twice" }
        require(!store.isDisposed) { "$label lists a disposed ${store.displayName}" }
    }
    val unlisted = pins.keys.firstOrNull { pinned -> listed.none { it::class == pinned } }
    require(unlisted == null) {
        "stores(names = …) on '$label' pins ${unlisted?.simpleName}, which the group does not list"
    }
    val taken = HashSet<String>()
    return listed.map { store ->
        val pin = pins[store::class]
        val name =
            pin ?: checkNotNull(defaultLeafName(store::class.simpleName)) {
                "$label lists a store whose class has no simple name (anonymous or local); pin it with " +
                    "stores(names = mapOf(Store::class to \"...\"))"
            }
        require(taken.add(name)) {
            "$label has two leaves named '$name'; pin one with stores(names = mapOf(Store::class to \"...\"))"
        }
        name to (if (pin != null) NameOrigin.Pinned else NameOrigin.ClassName)
    }
}

/**
 * Phases 3–6 of [attach]: link, register (running [registryStep] over the
 * leaves registered), tell [registered] (the attach is irrevocable from
 * here), sync the rings, announce.
 */
internal fun attachUnder(
    attach: ChildAttach,
    registered: () -> Unit,
    registryStep: (List<LeafNode>) -> Unit,
) {
    attach.link()
    attach.publish(registryStep)
    registered()
    attach.syncRings()
    attach.announce()
}
