@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.StoreInternalApi

// The dynamic store tree (issue #21). A store declares its children next to
// its states — `val settings by store { SettingsStore }`, `val session by
// stores { listOf(…) }`, `val threads by stores<String, ThreadStore> { … }`
// — and the tree is addressed by node and state identity: strings appear
// only in `encode()` and `render()`. Package `tree` is a plug-in over the
// root kernel: it imports `com.vynatix.holdfast` and `platform/` only.

/**
 * A position in a store tree: a [LeafNode] (one store's place, for the
 * store's whole life), a [Branch] (a `stores { }` group of a store) or a
 * [KeyedBranch] (a `stores<K, S> { }` family of a store, whose stores are
 * created per key at runtime). Nodes compare by identity; subtree
 * operations — `tree.snapshot(node)`, `tree.reset(node)`,
 * `tree.stores(node)` — take node values, never names.
 */
@ExperimentalStoreApi
sealed interface StoreNode {
    /**
     * This node's name: a child's property name or pin, a group leaf's pin
     * else its store's class name minus `Store`, a keyed leaf's encoded key,
     * a store with no parent's class name minus `Store`. Used by
     * `encode()`/`render()` only; see [nameOrigin] for where it came from.
     * A store whose parent disposed becomes a subtree root and takes its
     * class-derived name again.
     */
    val name: String

    /** The node this one hangs under; `null` for a store with no parent (a subtree root). */
    val parent: StoreNode?

    /** Where [name] came from — the persisted-name self-check reads this. */
    val nameOrigin: NameOrigin

    /** Whether this node is [node] or lies anywhere beneath it, over the live parent links. */
    fun isUnder(node: StoreNode): Boolean {
        var current: StoreNode? = this
        while (current != null) {
            if (current === node) return true
            current = current.parent
        }
        return false
    }
}

/**
 * Where a [StoreNode.name] came from. `Property` and `ClassName` are read
 * from the program's own identifiers and change under obfuscation;
 * `verifyPersistedNames` flags a persisted store whose leaf name is
 * `ClassName`. `Key` names never persist through anything but the branch's
 * key codec; `Pinned` names are literals the program chose.
 */
@ExperimentalStoreApi
enum class NameOrigin {
    /** A child, group or keyed branch named by its delegated property. */
    Property,

    /**
     * A leaf named by its class's simple name minus `Store` — a group member
     * that is not pinned, or a store with no parent (an unattached or
     * receiver node).
     */
    ClassName,

    /** A keyed leaf named by its encoded key. */
    Key,

    /** A name given as a literal: `store(named = …)`, `stores(names = …)`. */
    Pinned,
}

/**
 * The names from [top]'s child down to this node over the LIVE parent
 * links; empty when this is [top]. For preconditions and `toString()` only:
 * snapshots navigate the structure they froze at capture time.
 *
 * @throws IllegalArgumentException if this node is not under [top].
 */
internal fun StoreNode.pathUnder(top: StoreNode): List<String> {
    val names = ArrayList<String>()
    var current: StoreNode? = this
    while (current != null && current !== top) {
        names.add(current.name)
        current = current.parent
    }
    require(current === top) { "node '$name' is not under '${top.name}'" }
    return names.asReversed()
}

/**
 * The nodes from [top] down to this node over the live parent links, both
 * included.
 *
 * @throws IllegalArgumentException if this node is not under [top].
 */
internal fun StoreNode.pathNodesFrom(top: StoreNode): List<StoreNode> {
    val nodes = ArrayList<StoreNode>()
    var current: StoreNode? = this
    while (current != null && current !== top) {
        nodes.add(current)
        current = current.parent
    }
    require(current === top) { "node '$name' is not under '${top.name}'" }
    nodes.add(top)
    return nodes.asReversed()
}

/** The nearest store node above this one over the live links: a [Branch] or [KeyedBranch] is passed through. */
internal fun StoreNode.parentStoreNode(): LeafNode? {
    var current = parent
    while (current != null && current !is LeafNode) current = current.parent
    return current as LeafNode?
}

/**
 * The store nodes from [from]'s nearest store node — [from] itself when it
 * is a [LeafNode], else the store a [Branch]/[KeyedBranch] belongs to — up
 * to the parentless one, nearest first, over the live links. Read under
 * `treeStructureLock` when the caller needs a stable chain.
 */
internal fun ancestorsOf(from: StoreNode?): List<LeafNode> {
    val out = ArrayList<LeafNode>()
    var current: LeafNode? =
        when (from) {
            null -> null
            is LeafNode -> from
            else -> from.parentStoreNode()
        }
    while (current != null) {
        out.add(current)
        current = current.parentStoreNode()
    }
    return out
}
