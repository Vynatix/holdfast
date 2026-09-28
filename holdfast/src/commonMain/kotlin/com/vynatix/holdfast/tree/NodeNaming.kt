@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.StoreInternalApi

// Free functions over node names (issue #21 decision U7): how a leaf is
// named by default, and (from PR 21-4 on) the persisted-name self-check.

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
