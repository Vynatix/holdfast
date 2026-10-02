@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.internalAttachment
import kotlinx.atomicfu.locks.synchronized

/**
 * This store's view of its subtree: the store and every store declared
 * under it (see [StoreTree]). One handle per store, created on first use
 * with its value machinery. A handle obtained before the store's `dispose()`
 * stays usable for [StoreTree.node], [StoreTree.parent],
 * [StoreTree.removeMiddleware] and a value read before; this accessor
 * itself throws on a disposed store.
 *
 * @throws IllegalStateException if the store is disposed ("store disposed").
 */
@ExperimentalStoreApi
val Store<*>.tree: StoreTree
    get() =
        internalAttachment(treeMembershipKey)?.handleRef?.value
            ?: treeAttachment().let { attachment ->
                synchronized(attachment.handleLock) {
                    attachment.handleRef.value
                        ?: StoreTreeImpl(this, attachment).also { attachment.handleRef.value = it }
                }
            }
