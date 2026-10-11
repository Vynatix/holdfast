package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store

/**
 * A store's stable identity as the receiver of a tree capture: what
 * `TreeSnapshot.encode()` writes as the envelope's `receiver` and what
 * `tree.decode` checks it against, instead of the receiver's node name.
 *
 * Without it the receiver is identified by its node name — a child's
 * property name or pin, a top-level store's class name minus `Store` — so a
 * top-level store's persisted trees stop decoding when its class is renamed
 * or obfuscated (R8), and `verifyPersistedNames` reports
 * `NamingIssue.Kind.ReceiverNameIsClassDerived`. Implement it on the store
 * class (like `SchemaVersioned`; it is never a `Store` member):
 *
 * ```
 * class AppStore : Store<AppStore>(), TreeIdentified {
 *     override val treeId get() = "app"
 * }
 * ```
 */
@ExperimentalStoreApi
interface TreeIdentified {
    /** The receiver identity written into, and required of, this store's tree envelopes. Not empty. */
    val treeId: String
}

/**
 * [store]'s identity as a tree receiver at [node], its node: its
 * [TreeIdentified.treeId] when it has one, else the node's name.
 *
 * @throws IllegalStateException if the tree id is empty.
 */
@OptIn(ExperimentalStoreApi::class)
internal fun receiverIdentity(
    store: Store<*>?,
    node: LeafNode,
): String {
    val id = (store as? TreeIdentified)?.treeId ?: return node.name
    check(id.isNotEmpty()) { "${node.name}: TreeIdentified.treeId must not be empty" }
    return id
}
