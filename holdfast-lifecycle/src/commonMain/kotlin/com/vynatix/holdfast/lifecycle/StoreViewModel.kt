@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.lifecycle

import androidx.lifecycle.ViewModel
import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.tree.tree

/**
 * A [ViewModel] that owns one [Store] for its lifetime and disposes it in
 * [onCleared] — exactly once, whether the store was ever read or not.
 *
 * The store is created lazily by the first [store] read and never recreated
 * (`createStore` runs at most once per ViewModel). [onCleared] disposes it
 * and nukes the reference, so a repeated `onCleared` — which the platform
 * never issues, but a defensive host might — is a no-op; [Store.dispose] is
 * idempotent regardless (GUIDE §19.1).
 *
 * Two ownership shapes, chosen with [disposeSubtree]:
 *
 * - **A store per ViewModel** (the default): `onCleared` disposes the one
 *   store. When that store is a tree root, the tree's own dispose semantics
 *   apply unchanged (GUIDE §17.3): its keyed stores are disposed with it,
 *   its `store { }` / `group { }` children are *released* as subtree roots —
 *   deliberately not disposed, because the lambdas that built them may have
 *   shared them. A released child that observes anything longer-lived than
 *   the ViewModel keeps those subscriptions until something disposes it.
 * - **A store tree per ViewModel** ([treeViewModel], or
 *   `disposeSubtree = true` in your own subclass): `onCleared` disposes
 *   every store of the subtree, then the root — the screen-private-tree
 *   ownership claim. Listing the subtree materializes never-read
 *   `store { }` / `group { }` children, so their declaring lambdas run at
 *   teardown: keep tree-child lambdas side-effect-free (a keyed factory
 *   runs only on `create`/`getOrCreate`, so an unread keyed entry simply
 *   does not exist and its factory never runs). A child lambda that throws
 *   there does not break the guarantee — the root is still disposed — and
 *   the failure is reported through the root store's
 *   `uncaughtObserverHandler` (or the default failure log), wrapped in a
 *   [StoreTreeTeardownException]; the children follow the tree's release
 *   semantics (GUIDE §17.3), so key the screen's disposable children as
 *   keyed branches or materialize them during the ViewModel's life.
 *
 * Reading [store] after `onCleared` throws an [IllegalStateException]: the
 * ViewModel is dead and its store disposed. To run code at clear time while
 * the store is still live — persisting a snapshot, for instance — add an
 * `AutoCloseable` (ViewModel `addCloseable`); the platform closes closeables
 * *before* `onCleared`, so it sees the live store.
 *
 * ## Process death, honestly
 *
 * `onCleared` never runs on process death — the ViewModel, its store and any
 * unsaved state die with the process, and a restored ViewModel builds a
 * fresh store. This module persists nothing. The process-death story is the
 * snapshot pipeline's (GUIDE §16.2, §16.8): `snapshot().encode()` what you
 * need to survive, through a closeable at clear time or on every commit, and
 * `restore`/`decode` it in `createStore`.
 *
 * ## Scopes
 *
 * `dispose()` never cancels any scope, and a cancelled scope is not
 * observed (GUIDE §18.4). Post-commit work (derived-state recomputes,
 * hydration writers) therefore lands on the store's resolved scope — bind
 * the store to `viewModelScope` with `bindToScope` in `createStore` if you
 * want that work to stop with the ViewModel; the platform cancels
 * `viewModelScope` independently of this class's `dispose` wiring.
 *
 * Like every [ViewModel], instances are main-thread confined; [store] and
 * the wiring are not synchronized.
 *
 * @param S the store class this ViewModel owns; a tree root is just a store
 *   whose subtree follows the shape chosen above.
 */
@ExperimentalStoreApi
abstract class StoreViewModel<S : Store<*>> : ViewModel() {
    private var holder: S? = null
    private var cleared = false

    /**
     * The owned store — created by [createStore] on the first read, answered
     * unchanged afterwards.
     *
     * @throws IllegalStateException if the ViewModel was already cleared.
     */
    val store: S
        get() {
            check(!cleared) {
                "StoreViewModel.store was read after onCleared(); the ViewModel is cleared and its store disposed."
            }
            return holder ?: createStore().also { holder = it }
        }

    /**
     * Build the store this ViewModel owns. Runs at most once, on the first
     * [store] read (never from a constructor — the factory may reference
     * subclass state that is not ready while `this` leaks).
     */
    protected abstract fun createStore(): S

    /**
     * Whether `onCleared` disposes the whole store tree the owned store
     * roots, not just the store itself — the screen-private-tree ownership
     * claim (see [StoreViewModel] for what each shape disposes). The default
     * `false` gives the tree's own §17.3 semantics; [treeViewModel] sets it.
     */
    protected open val disposeSubtree: Boolean = false

    final override fun onCleared() {
        if (cleared) return
        cleared = true
        val root = holder
        holder = null
        if (root == null) return
        if (disposeSubtree) disposeTreeOf(root) else root.dispose()
    }
}

/** Disposes every store of [root]'s subtree, then [root] itself. */
private fun disposeTreeOf(root: Store<*>) {
    val members =
        runCatching { root.tree.stores() }
            .onFailure { error -> root.internalReportUncaughtFailure(StoreTreeTeardownException(error)) }
            .getOrDefault(emptyList())
    for (member in members) {
        if (member !== root) member.dispose()
    }
    root.dispose()
}

/**
 * Wraps a tree-child lambda failure that surfaced while
 * [StoreViewModel.onCleared] listed the subtree for disposal. The root store
 * is disposed regardless; the other stores of the subtree follow the tree's
 * release semantics (GUIDE §17.3).
 */
internal class StoreTreeTeardownException(
    cause: Throwable,
) : IllegalStateException(
        "holdfast-lifecycle: a store-tree child factory threw while StoreViewModel disposed its " +
            "subtree at onCleared(); the root store was disposed regardless — see the cause.",
        cause,
    )
