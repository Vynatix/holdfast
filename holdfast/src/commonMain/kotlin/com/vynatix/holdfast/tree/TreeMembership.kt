@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreAttachment
import com.vynatix.holdfast.StoreAttachmentKey
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.StoreMembership
import com.vynatix.holdfast.internalAttachIfAbsent
import com.vynatix.holdfast.internalDetach
import com.vynatix.holdfast.platform.currentMintLocal
import com.vynatix.holdfast.platform.currentThreadId
import com.vynatix.holdfast.platform.setMintLocal
import com.vynatix.holdfast.settling
import kotlin.reflect.KClass

// Keyed-store liveness is a factory bracket (issue #21 decision U2): a keyed
// store is constructed inside `KeyedBranch.create(key) { ThreadStore(it) }`,
// whose class header takes `root.threads.at(key)`. `at` is valid only inside
// the dynamic extent of that `create`, for that branch and key, on that
// thread (the mint below), and the store is live — visible to lookups,
// captures, `value`, reset and middleware install — only once the factory has
// returned and the instance it returned is the one that bound.

/** The one attachment key a store's tree membership lives under: a store belongs to at most one tree. */
internal val treeMembershipKey = StoreAttachmentKey<TreeLeafAttachment>("tree membership")

/**
 * A store's membership in a tree, attached through the PR 10 slot. Its
 * `onStoreDisposed` is how the tree hears that a leaf is gone.
 */
internal class TreeLeafAttachment(
    val root: Root,
    val leaf: LeafNode,
    val entry: LeafEntry?,
) : StoreAttachment {
    override fun onStoreDisposed() {
        root.leafDisposed(leaf, entry)
    }
}

/**
 * The `create` whose factory is running on this thread: which branch and key
 * it is for, and what has been minted and bound so far. Linked to the
 * enclosing one, so a factory that creates another keyed store inside works
 * (inner first), and a bare construction inside a factory fails.
 */
internal class Mint(
    val branch: KeyedBranch<*, *>,
    val key: Any,
    val entry: LeafEntry,
    val parent: Mint?,
) {
    var minted: Boolean = false
    var bound: Store<*>? = null
}

internal fun currentMint(): Mint? = currentMintLocal() as Mint?

/** The only [StoreMembership] implementation: minted by [KeyedBranch.at], single-use, thread-confined to its mint. */
internal class KeyedMembership<S : Store<S>>(
    private val mint: Mint,
    private val storeClass: KClass<S>,
) : StoreMembership<S>() {
    private var used = false

    override fun bind(store: Store<S>) {
        val branch = mint.branch
        check(!used) {
            "root '${branch.root.name}': the membership token from ${branch.name}.at(${mint.key}) was already " +
                "used to construct a store; each create(key) { } constructs exactly one store"
        }
        used = true
        check(currentMint() === mint) {
            "root '${branch.root.name}': a store took ${branch.name}.at(${mint.key}) on another thread than the " +
                "create(key) { } that minted it; construct the store inside the factory, on the calling thread"
        }
        check(storeClass.isInstance(store)) {
            "root '${branch.root.name}': ${branch.name} is declared as keyed<..., ${storeClass.simpleName}> but " +
                "${store::class.simpleName} took its token; a keyed store must be a ${storeClass.simpleName}"
        }
        check(mint.bound == null) {
            "root '${branch.root.name}': the factory for ${branch.name}.create(${mint.key}) already constructed a store"
        }
        val entry = mint.entry
        val attachment =
            store.internalAttachIfAbsent(treeMembershipKey) {
                TreeLeafAttachment(branch.root, entry.leaf, entry)
            }
        check(attachment.leaf === mint.entry.leaf) {
            "root '${branch.root.name}': the store constructed for ${branch.name}.create(${mint.key}) already " +
                "belongs to root '${attachment.root.name}' under '${attachment.leaf.parent.name}'"
        }
        mint.entry.phase = LeafEntry.Phase.Constructing(store)
        mint.bound = store
    }
}

/** [KeyedBranch.at]: valid only inside the `create`/`getOrCreate` for this branch and key on this thread. */
internal fun <K : Any, S : Store<S>> KeyedBranch<K, S>.mintAt(key: K): StoreMembership<S> {
    root.checkNotDisposed()
    val mint = currentMint()
    check(mint != null && mint.branch === this && mint.key == key) {
        val running =
            if (mint == null) {
                "construct keyed stores through create, never bare"
            } else {
                "the running create is for ${mint.branch.name}.at(${mint.key})"
            }
        "root '${root.name}': $name.at($key) is valid only inside $name.create($key) { } or " +
            "$name.getOrCreate($key) { } on the thread running its factory; $running"
    }
    check(!mint.minted) {
        "root '${root.name}': $name.at($key) was already called inside this create; " +
            "each create(key) { } constructs one store"
    }
    mint.minted = true
    return KeyedMembership(mint, storeClass)
}

/** [KeyedBranch.create]: reserve the key, run the factory inside the mint, promote or clean up. */
internal fun <K : Any, S : Store<S>> KeyedBranch<K, S>.createKeyed(
    key: K,
    factory: (K) -> S,
): S {
    root.checkNotDisposed()
    val (entry, reserved) = root.registry.reserveOrExisting(this, key, leafNameFor(key))
    check(reserved) {
        "root '${root.name}': $name.create($key) — a store for this key already exists (or is being created); " +
            "use getOrCreate(key) { } to share it"
    }
    return construct(entry, key, factory)
}

/** [KeyedBranch.getOrCreate]: the live store for [key], else construct it; parks on another thread's construction. */
internal fun <K : Any, S : Store<S>> KeyedBranch<K, S>.getOrCreateKeyed(
    key: K,
    factory: (K) -> S,
): S {
    while (true) {
        root.checkNotDisposed()
        val (entry, reserved) = root.registry.reserveOrExisting(this, key, leafNameFor(key))
        if (reserved) return construct(entry, key, factory)
        val phase = entry.phase
        if (phase is LeafEntry.Phase.Live) {
            @Suppress("UNCHECKED_CAST")
            return phase.store as S
        }
        check(entry.constructingThreadId != currentThreadId()) {
            "root '${root.name}': $name.getOrCreate($key) called from inside the factory constructing " +
                "that very key — a cycle"
        }
        // Another thread is constructing it: park on its construction lock,
        // then read the registry again (it is Live, or gone after a failure).
        entry.constructionLock.withLock { }
    }
}

/**
 * Construct inside a settle scope (joining the entry open on this thread, else
 * one of its own): a recompute the attach queues — the root's `value`
 * following the new store — runs once this returns, after the store is
 * promoted and visible, never inside the factory bracket.
 */
private fun <K : Any, S : Store<S>> KeyedBranch<K, S>.construct(
    entry: LeafEntry,
    key: K,
    factory: (K) -> S,
): S = settling { constructUnsettled(entry, key, factory) }

private fun <K : Any, S : Store<S>> KeyedBranch<K, S>.constructUnsettled(
    entry: LeafEntry,
    key: K,
    factory: (K) -> S,
): S {
    entry.constructionLock.acquire()
    entry.constructingThreadId = currentThreadId()
    val mint = Mint(this, key, entry, currentMint())
    setMintLocal(mint)
    var promoted = false
    try {
        val produced = factory(key)
        verifyProduced(mint, produced)
        entry.leaf.storeRef = produced
        entry.leaf.storeKey = produced.lockOrderKey
        root.promoteLeaf(entry, produced)
        promoted = true
        return produced
    } finally {
        if (!promoted) abandon(entry, mint)
        setMintLocal(mint.parent)
        entry.constructingThreadId = null
        entry.constructionLock.release()
    }
}

private fun <K : Any, S : Store<S>> KeyedBranch<K, S>.verifyProduced(
    mint: Mint,
    produced: S,
) {
    val bound = mint.bound
    check(bound != null) {
        "root '${root.name}': the factory for $name.create(${mint.key}) returned a store that did not take " +
            "$name.at(${mint.key}); a keyed ${storeClass.simpleName} declares `: Store<...>(root.$name.at(key))`"
    }
    check(bound === produced) {
        "root '${root.name}': the factory for $name.create(${mint.key}) returned a different instance than the one " +
            "that took $name.at(${mint.key}); return the store you constructed"
    }
    check(!produced.isDisposed) {
        "root '${root.name}': the factory for $name.create(${mint.key}) returned a disposed store"
    }
}

private fun KeyedBranch<*, *>.abandon(
    entry: LeafEntry,
    mint: Mint,
) {
    root.registry.abandon(entry)
    val bound = mint.bound ?: return
    bound.internalDetach(treeMembershipKey)
    runCatching { bound.dispose() }
    if (entry.attachedNotified) root.onLeafDetached(entry.leaf)
}
