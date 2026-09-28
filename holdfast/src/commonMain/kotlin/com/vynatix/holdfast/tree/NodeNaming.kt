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
 * anonymous or local class) — a declaration then fails unless the leaf is
 * pinned with `named(store, "...")`.
 */
internal fun defaultLeafName(simpleName: String?): String? =
    when {
        simpleName.isNullOrEmpty() -> null
        simpleName.length > STORE_SUFFIX.length && simpleName.endsWith(STORE_SUFFIX) ->
            simpleName.removeSuffix(STORE_SUFFIX)
        else -> simpleName
    }

/**
 * `Root.verifyPersistedNames(node)` (T6): every persisted store at a
 * class-named leaf of the subtree, and every keyed branch of it declared
 * without a key codec. A store is persisted when it declares a
 * `UserAuthored` state or keyed family, implements `SchemaVersioned`, or
 * has an attachment reporting `persistenceKeys`. Reads declarations only —
 * no initializer runs, no lock beyond the registries' is taken.
 */
internal fun verifyNames(
    root: Root,
    node: StoreNode,
): List<NamingIssue> {
    val issues = ArrayList<NamingIssue>()
    for (candidate in root.registry.nodesPreorder(node)) {
        when (candidate) {
            is LeafNode -> {
                val store = candidate.store ?: continue
                if (candidate.nameOrigin == NameOrigin.ClassName && !store.isDisposed && isPersisted(store)) {
                    issues +=
                        NamingIssue(
                            node = candidate,
                            kind = NamingIssue.Kind.ClassDerivedNameOnPersistedStore,
                            message =
                                "leaf '${candidate.name}' is named by its store's class and the store persists; " +
                                    "pin the name with branch(store).named(store, \"...\")",
                        )
                }
            }
            is KeyedBranch<*, *> ->
                if (candidate.keyCodec == null) {
                    issues +=
                        NamingIssue(
                            node = candidate,
                            kind = NamingIssue.Kind.KeyedBranchNotEncodable,
                            message =
                                "keyed branch '${candidate.name}' has no key codec, so encode() skips it; " +
                                    "declare it with keyed(keyCodec = ...)",
                        )
                }
            is Branch, is Root -> Unit
        }
    }
    return issues
}

private fun isPersisted(store: Store<*>): Boolean =
    store is SchemaVersioned ||
        store.registry.declarationsInOrder().any { StateTag.UserAuthored in it.tags } ||
        store.registry.keyed
            .familiesInOrder()
            .any { StateTag.UserAuthored in it.tags } ||
        store.internalAttachments().any { it.persistenceKeys.isNotEmpty() }
