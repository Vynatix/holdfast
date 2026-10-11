@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.SchemaVersioned
import com.vynatix.holdfast.StateTag
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.internalAttachments

// Free functions over node names (issue #21 decision U7): how a leaf is
// named by default, and the persisted-name self-check.

private const val STORE_SUFFIX = "Store"

/**
 * The default name of a branch leaf: its store's class simple name minus a
 * trailing `Store` (`SettingsStore` → `Settings`; a class named just `Store`
 * keeps it), or `null` when the platform reports no simple name (an
 * anonymous or local class) — a group then fails unless the leaf is
 * pinned with `named`.
 */
internal fun defaultLeafName(simpleName: String?): String? =
    when {
        simpleName.isNullOrEmpty() -> null
        simpleName.length > STORE_SUFFIX.length && simpleName.endsWith(STORE_SUFFIX) ->
            simpleName.removeSuffix(STORE_SUFFIX)
        else -> simpleName
    }

/**
 * `tree.verifyPersistedNames(node)` (T6): in the subtree at [node], every
 * persisted store at a class-named group leaf, every property-named node (a
 * `store { }` child, a group, a keyed branch) with a persisted store at or
 * under it, and every keyed branch declared without a key codec; and the
 * receiver itself when it is a class-named top-level store, not
 * [TreeIdentified], with a persisted store in the subtree checked. The
 * receiver's own name is never part of a path, so it is checked only as the
 * envelope's `receiver`. A store is persisted when it declares a
 * `UserAuthored` state or keyed family, implements `SchemaVersioned`, or has
 * an attachment reporting `persistenceKeys`. Reads declarations only — no
 * initializer runs, no lock beyond the registries' is taken.
 */
internal fun verifyNames(
    ownerNode: LeafNode,
    node: StoreNode,
): List<NamingIssue> {
    val owner = checkNotNull(ownerNode.store) { "${ownerNode.name}'s store disposed" }
    owner.checkNotDisposed()
    val issues = ArrayList<NamingIssue>()
    val shape = listingOf(ownerNode, node).shape
    val persisted = persistedSubtrees(shape)
    receiverIssue(ownerNode, owner, persisted.getValue(shape))?.let(issues::add)
    for (candidate in shape.preorder()) {
        val at = candidate.node
        if (at === ownerNode) continue
        val holdsPersisted = persisted.getValue(candidate)
        val persistsItself = candidate.store?.let { !it.isDisposed && isPersisted(it) } == true
        when {
            at.nameOrigin == NameOrigin.Property && holdsPersisted -> issues += propertyNamed(at)
            at is LeafNode && at.nameOrigin == NameOrigin.ClassName && persistsItself ->
                issues +=
                    NamingIssue(
                        node = at,
                        kind = NamingIssue.Kind.ClassDerivedNameOnPersistedStore,
                        message =
                            "leaf '${at.name}' is named by its store's class and the store persists; pin the name " +
                                "in the group lambda: group { listOf(store named \"...\") }",
                    )
        }
        if (at is KeyedBranch<*, *> && at.keyCodec == null) {
            issues +=
                NamingIssue(
                    node = at,
                    kind = NamingIssue.Kind.KeyedBranchNotEncodable,
                    message =
                        "keyed branch '${at.name}' has no key codec, so encode() skips it; " +
                            "declare it with keyed<K, S>(keyCodec = ...)",
                )
        }
    }
    return issues
}

private fun receiverIssue(
    ownerNode: LeafNode,
    owner: Store<*>,
    holdsPersisted: Boolean,
): NamingIssue? {
    if (!holdsPersisted || owner is TreeIdentified || ownerNode.nameOrigin != NameOrigin.ClassName) return null
    return NamingIssue(
        node = ownerNode,
        kind = NamingIssue.Kind.ReceiverNameIsClassDerived,
        message =
            "the receiver '${ownerNode.name}' is a top-level store identified by its class name, which encode() " +
                "writes as the envelope's receiver and a class rename or obfuscation would change; implement " +
                "TreeIdentified on it",
    )
}

private fun propertyNamed(at: StoreNode): NamingIssue {
    val how =
        when (at) {
            is LeafNode -> "store(named = \"...\")"
            is Branch -> "group(named = \"...\")"
            is KeyedBranch<*, *> -> "keyed(named = \"...\")"
        }
    return NamingIssue(
        node = at,
        kind = NamingIssue.Kind.PropertyDerivedNameOnPersistedSubtree,
        message =
            "'${at.name}' is named by its Kotlin property and a store at or under it persists; a property " +
                "rename or obfuscation would orphan what was persisted under it; pin it with $how",
    )
}

/** Each shape of [shape]'s subtree, mapped to whether a persisted store sits at or under it. */
private fun persistedSubtrees(shape: TreeShape): Map<TreeShape, Boolean> {
    val out = HashMap<TreeShape, Boolean>()

    fun walk(at: TreeShape): Boolean {
        var any = at.store?.let { !it.isDisposed && isPersisted(it) } == true
        for (child in at.children) any = walk(child) or any
        out[at] = any
        return any
    }
    walk(shape)
    return out
}

/** Every shape of this subtree, in pre-order. */
private fun TreeShape.preorder(): List<TreeShape> {
    val out = ArrayList<TreeShape>()

    fun walk(at: TreeShape) {
        out.add(at)
        at.children.forEach(::walk)
    }
    walk(this)
    return out
}

private fun isPersisted(store: Store<*>): Boolean =
    store is SchemaVersioned ||
        store.registry.declarationsInOrder().any { StateTag.UserAuthored in it.tags } ||
        store.registry.keyed
            .familiesInOrder()
            .any { StateTag.UserAuthored in it.tags } ||
        store.internalAttachments().any { it.persistenceKeys.isNotEmpty() }
