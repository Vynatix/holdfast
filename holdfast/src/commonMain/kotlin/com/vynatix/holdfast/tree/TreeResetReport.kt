@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi

/**
 * What `Root.reset(node)` did: the leaves it reset — every live leaf of the
 * subtree, each in its own transaction of the one frame — and the leaves it
 * skipped because their store was disposed after the reset began. A subtree
 * with no live leaf resets nothing and reports nothing.
 */
@ExperimentalStoreApi
class TreeResetReport internal constructor(
    val reset: List<StoreNode>,
    val skipped: List<StoreNode>,
) {
    override fun toString(): String =
        "TreeResetReport(reset=${reset.map { it.name }}, " +
            "skipped=${skipped.map { it.name }})"
}
