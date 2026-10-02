@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import kotlinx.coroutines.CoroutineScope

/**
 * The store that hosts a root's `value` (issue #21 decision U1: a `Root` is
 * not a `Store`, but a derived state needs a host whose transaction commits
 * it): one per root, never a member of the tree, with no declared state —
 * only the unregistered backing of the tree value, written by its
 * recompute. Its transaction lock is the "host lock" of §10.2: taken by a
 * settle, never under a leaf's lock. Its `scope` is the root's, so
 * `asStateFlow()` on the value defaults there; its
 * `uncaughtObserverHandler` is the root's.
 */
internal class RootHost(
    val root: Root,
) : Store<RootHost>() {
    override val scope: CoroutineScope get() = root.scope
}
