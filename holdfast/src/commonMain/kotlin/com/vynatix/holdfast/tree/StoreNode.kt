@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.StoreInternalApi

// The typed state tree (issue #21). A `Root` names its branches through
// delegated properties, keyed stores join through a factory bracket, and the
// tree is addressed by node and state identity — strings appear only in
// `encode()` and `render()`. Package `tree` is a plug-in over the root
// kernel: it imports `com.vynatix.holdfast` and `platform/` only.

/**
 * A position in a [Root]'s tree: the root itself, a [Branch] (a delegated
 * property listing stores), a [KeyedBranch] (a delegated property whose
 * stores are created per key at runtime) or a [LeafNode] (one store's place
 * under a branch). Nodes compare by identity; subtree operations —
 * `Root.snapshot(node)`, `Root.reset(node)`, `Root.children(node)` — take
 * node values, never names.
 */
@ExperimentalStoreApi
sealed interface StoreNode {
    /** The tree this node belongs to. A [Root] is its own root. */
    val root: Root

    /**
     * This node's name: a branch's property name, a root's constructor
     * argument else its class name, a leaf's pinned name else its store's
     * class name minus `Store`, a keyed leaf's encoded key. Used by
     * `encode()`/`render()` only; see [nameOrigin] for where it came from.
     */
    val name: String

    /** The node this one is declared under; `null` for a [Root]. */
    val parent: StoreNode?

    /** Where [name] came from — the persisted-name self-check reads this. */
    val nameOrigin: NameOrigin

    /** Whether this node is [node] or lies anywhere beneath it. */
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
 * `Root.verifyPersistedNames` flags a persisted store whose leaf name is
 * `ClassName`. `Key` names never persist through anything but the branch's
 * key codec; `Pinned` names are literals the program chose.
 */
@ExperimentalStoreApi
enum class NameOrigin {
    /** A branch named by its delegated property. */
    Property,

    /** A root or leaf named by its class's simple name (a leaf's minus `Store`). */
    ClassName,

    /** A keyed leaf named by its encoded key. */
    Key,

    /** A name given as a literal: `Root("app")`, `branch(...).named(...)`. */
    Pinned,
}
