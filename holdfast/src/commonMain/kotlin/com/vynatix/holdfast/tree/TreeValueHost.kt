@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.SettleTask
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import kotlinx.coroutines.CoroutineScope

/**
 * The store that hosts one `tree` handle's value: created lazily with the
 * value (its first read or observation, or `internalHost()`),
 * never a member of any tree, with no declared state — only the unregistered
 * backing of the tree value, written by its recompute. Its transaction lock
 * is the "host lock" of GUIDE §10.2: taken by a settle and by its own
 * dispose, never under a lock of [owner]'s. Its `scope` is [owner]'s, so
 * `asStateFlow()` on the value defaults there; a failing observer or
 * recompute of the value is reported through [owner]'s
 * `uncaughtObserverHandler`.
 */
internal class TreeValueHost(
    val owner: Store<*>,
) : Store<TreeValueHost>() {
    override val scope: CoroutineScope get() = owner.scope

    init {
        uncaughtObserverHandler = { owner.internalReportUncaughtFailure(it) }
    }
}

/**
 * The host's dispose as a settle task: [owner]'s dispose (step 6 of
 * `StoreDetach`) never disposes the host inline under a lock of the owner's;
 * inside an entry it runs when that entry settles, after it released
 * everything.
 */
internal class HostDisposeTask(
    private val host: TreeValueHost,
) : SettleTask {
    override val settleRank: Int get() = 0

    override fun settle() {
        host.dispose()
    }

    override fun deferPastSettle(report: Boolean) {
        host.handOffPostCommit { host.dispose() }
    }
}
