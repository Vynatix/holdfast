@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.InitializerGraph
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.StoreLock
import com.vynatix.holdfast.displayName
import com.vynatix.holdfast.settling

// Children are declared eagerly and materialized lazily, like states: a
// `store { }`/`group { }` declaration registers a `ChildEntry` at
// `provideDelegate` and runs nothing; the child lambda runs on the first
// read of the delegate, or when a tree operation needs the subtree
// (`materializeSubtree`, called first by every public structural entrypoint).
//
// The lambda is ordinary user code run on the reading thread HOLDING NO LOCK
// OR LATCH of the tree's (only whatever its caller holds: read inside an
// action, it runs under that action's `transactionLock` and sees its
// uncommitted writes; read inside a state initializer, a migrate or a
// derived compute, it inherits that `NoWriteRegion`). So a lambda may open
// actions on any store, the parent included, while another thread reads the
// same child from inside an action of the parent: nothing it holds can be
// waited on by a reader. Racing first reads may each run the lambda; the
// first to claim the entry (Declared → Constructing under the registry lock)
// attaches its result through the attach phases, and every other reader
// returns that winner — disposing what its own run built that nothing else
// holds (`RunBuilt`). A same-thread re-entry — the lambda needing its own
// declaration, directly or through another child or a state initializer —
// is a cycle: it throws the cycle message naming the chain in order
// (`InitializerGraph.runMarked`, a per-thread mark). A cross-thread mutual
// recursion cannot hang: each thread runs the other lambda itself and meets
// its own mark. A throwing lambda publishes nothing, and the next read runs
// it again. Attaching is structural, not transactional: a rollback of the
// action the first read ran in does not undo it.

/**
 * The tree's structure lock: held only for parent-edge compare-and-set, the
 * cycle walk, node field writes and ancestor-chain captures. Never held
 * while running user code, announcing, syncing rings, or holding or taking
 * any registry lock or attachment-slot lock; it may be taken under a store's
 * `transactionLock` (a dispose from inside the store's own action).
 */
internal val treeStructureLock = StoreLock()

/**
 * The child of [entry] (a `store { }` child or a `group { }` of the
 * entry's owner), materialized if it has not been: run the lambda holding
 * nothing, then claim the entry and attach the result under the owner, or
 * answer the result another thread attached first. Joins the settle scope
 * open on this thread, else settles once after return, so a recompute the
 * attach queues runs after the child is visible, never inside the lambda.
 *
 * @throws IllegalStateException if the owner is disposed, on a same-thread
 *   materialization cycle, or when the child already has a parent, would be
 *   its own ancestor, or is disposed; the lambda's own throw propagates.
 *   The entry stays retryable, and the stores the failed run built that
 *   nothing else holds are disposed.
 * @throws IllegalArgumentException when a group lists a store twice, a
 *   disposed store, pins a store it does not list, or names two leaves alike.
 */
internal fun materializeChild(entry: ChildEntry): Any {
    entry.produced?.let { return it }
    return settling { awaitClaimable(entry) ?: materializeRacing(entry) }
}

/** What one run of a child lambda produced, validated and ready to attach. */
internal class Candidate(
    /** What the delegate answers: the child store or the group's [Branch]. */
    val produced: Any,
    /** The node the entry takes: the child's [LeafNode] or the [Branch]. */
    val node: StoreNode,
    /** The stores the run returned, for [RunBuilt.disposeOrphans]. */
    val stores: List<Store<*>>,
    val targets: List<AttachTarget>,
)

/** The stores a winner's [ChildEntry.produced] holds: what a loser must never dispose. */
internal fun storesOf(produced: Any?): List<Store<*>> =
    when (produced) {
        is Branch -> produced.stores
        is Store<*> -> listOf(produced)
        else -> emptyList()
    }

/** Run the lambda (the entry was free to claim when this began), then claim and attach, or join the winner. */
private fun materializeRacing(entry: ChildEntry): Any {
    entry.owner.checkNotDisposed()
    val built = RunBuilt.mark()
    // Phase 1: the lambda, holding nothing of the tree's; marked on this thread only.
    val pins = GroupScope()
    val output = InitializerGraph.Process.runMarked(entry) { entry.runLambda(pins) }
    var prepared = false
    val candidate =
        try {
            prepare(entry, output, pins).also { prepared = true }
        } finally {
            if (!prepared) built.disposeOrphans(producedStores(entry, output))
        }
    val winner = claimOrJoin(entry, candidate, built)
    if (winner == null) return attachClaimed(entry, candidate, built)
    built.disposeOrphans(candidate.stores, keep = storesOf(winner))
    return winner
}

/** The lambda's raw output as stores, for disposing a run that failed validation. */
private fun producedStores(
    entry: ChildEntry,
    output: Any,
): List<Store<*>> =
    when (entry.kind) {
        ChildEntry.Kind.Group -> (output as? List<*>).orEmpty().filterIsInstance<Store<*>>()
        else -> storesOf(output)
    }

@Suppress("UNCHECKED_CAST")
private fun ChildEntry.runLambda(scope: GroupScope): Any =
    when (kind) {
        ChildEntry.Kind.Store -> (lambda as () -> Any)()
        ChildEntry.Kind.Group -> (lambda as GroupScope.() -> List<Store<*>>)(scope)
        ChildEntry.Kind.Keyed -> error("$label is materialized at declaration")
    }

/**
 * Holding [entry]'s claim: attach phases 3–7. A failure before the attach
 * registered (phase 4) makes the entry retryable again and disposes the
 * run's orphaned stores; the claim ends under the registry lock either way.
 */
private fun attachClaimed(
    entry: ChildEntry,
    candidate: Candidate,
    built: RunBuilt,
): Any {
    val registry = entry.registry
    var reachedLive = false
    var done = false
    try {
        val parentNode = if (entry.kind == ChildEntry.Kind.Group) candidate.node else entry.ownerNode
        val attach = ChildAttach(entry.owner, registry, entry.name, parentNode, candidate.targets, null)
        attachUnder(attach, { reachedLive = true }) { live ->
            entry.phase = ChildEntry.Phase.Live
            (candidate.node as? Branch)?.live?.addAll(live)
        }
        done = true
    } finally {
        // Phase 7, LAST, so no reader sees a produced-but-unattached child. On
        // a registry closed meanwhile the attach completed and the child is a
        // subtree root by now: hand it to the caller anyway.
        registry.lock.withLock {
            if (reachedLive && !registry.disposed) entry.produced = candidate.produced
            if (!reachedLive && entry.phase == ChildEntry.Phase.Constructing) {
                entry.phase = ChildEntry.Phase.Declared
                entry.node = null
            }
            entry.attachingThreadId = null
            entry.attaching = null
            entry.attachLock.release()
        }
        if (!done && !reachedLive) built.disposeOrphans(candidate.stores)
    }
    return candidate.produced
}

/** Validate one run's output and take its stores' tree state (phase 1's tail and phase 2's node). */
private fun prepare(
    entry: ChildEntry,
    output: Any,
    scope: GroupScope,
): Candidate =
    if (entry.kind == ChildEntry.Kind.Group) {
        @Suppress("UNCHECKED_CAST")
        val listed = output as List<Store<*>>
        val named = nameGroup(entry.label, listed, scope.pins)
        val attachments = listed.map { it.treeAttachment() }
        val members = listed.zip(attachments.map { it.node })
        val branch = Branch(entry.ownerNode, entry.name, entry.origin, members, entry.owner, entry.registry)
        val targets = attachments.mapIndexed { i, a -> AttachTarget(a, named[i].first, named[i].second, key = null) }
        Candidate(branch, branch, listed, targets)
    } else {
        val child =
            checkNotNull(output as? Store<*>) {
                "${entry.label}: store { } must produce a Store, not " +
                    "${output::class.simpleName ?: "an anonymous object"}; " +
                    "an inline child is object : NodeStore(), YourInterface { … }"
            }
        val produced = output
        check(!child.isDisposed) { "${entry.label}: the child lambda returned a disposed store" }
        val attachment = child.treeAttachment()
        val target = AttachTarget(attachment, entry.name, entry.origin, key = null)
        Candidate(produced, attachment.node, listOf(child), listOf(target))
    }

/**
 * Validate a group's listing and name its leaves: by the `named` pin the
 * lambda gave the store (by identity), else by class name minus `Store`.
 */
private fun nameGroup(
    label: String,
    listed: List<Store<*>>,
    pins: List<Pair<Store<*>, String>>,
): List<Pair<String, NameOrigin>> {
    for ((index, store) in listed.withIndex()) {
        require(listed.indexOfFirst { it === store } == index) { "$label lists ${store.displayName} twice" }
        require(!store.isDisposed) { "$label lists a disposed ${store.displayName}" }
    }
    val unlisted = pins.firstOrNull { (pinned, _) -> listed.none { it === pinned } }
    require(unlisted == null) {
        "$label pins a ${unlisted?.first?.displayName} named '${unlisted?.second}' that the group does not list"
    }
    val taken = HashSet<String>()
    return listed.map { store ->
        val pin = pins.firstOrNull { it.first === store }?.second
        val name =
            pin ?: checkNotNull(defaultLeafName(store::class.simpleName)) {
                "$label lists a store whose class has no simple name (anonymous or local); pin it in the group " +
                    "lambda: group { listOf(store named \"...\") }"
            }
        require(taken.add(name)) { duplicateLeafMessage(label, name, store, listed) }
        name to (if (pin != null) NameOrigin.Pinned else NameOrigin.ClassName)
    }
}

/** Two leaves of one group would be named [name]: pin one of them apart with `named`. */
private fun duplicateLeafMessage(
    label: String,
    name: String,
    store: Store<*>,
    listed: List<Store<*>>,
): String {
    val what =
        if (listed.count { it::class == store::class } > 1) {
            "lists two ${store::class.simpleName}s, so two leaves would be named '$name'"
        } else {
            "has two leaves named '$name'"
        }
    return "$label $what; pin one of them with named: group { listOf(a named \"...\", b) }"
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
