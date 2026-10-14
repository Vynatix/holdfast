@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.lifecycle

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store

/**
 * A [StoreViewModel] whose store comes from [create] — the no-boilerplate
 * shape when you do not need a ViewModel of your own:
 *
 * ```kotlin
 * val vm = storeViewModel { CounterStore() }
 * // or, through the platform's factory machinery:
 * viewModel { storeViewModel { CounterStore() } }
 * ```
 *
 * `onCleared` disposes the store. When the store is a tree root, the tree's
 * own dispose semantics apply (GUIDE §17.3) — keyed stores disposed, plain
 * children released as subtree roots; use [treeViewModel] when the ViewModel
 * owns the whole tree.
 *
 * @param create builds the store; runs at most once, on the first `store`
 *   read. Keep it side-effect-free apart from building the store — on
 *   process death it simply never runs.
 */
@ExperimentalStoreApi
fun <S : Store<*>> storeViewModel(create: () -> S): StoreViewModel<S> =
    object : StoreViewModel<S>() {
        override fun createStore(): S = create()
    }

/**
 * A [StoreViewModel] that owns the whole store tree rooted at the store
 * [create] builds: `onCleared` disposes **every** store of the subtree, then
 * the root — not just the root (see [StoreViewModel] for the two shapes).
 *
 * Listing the subtree materializes never-read `store { }` / `group { }`
 * children, so their declaring lambdas run at teardown — keep tree-child
 * lambdas side-effect-free. A child lambda that throws there is reported
 * through the root store's `uncaughtObserverHandler` (wrapped in a teardown
 * exception) and the root is disposed regardless — a fatal error aside
 * (GUIDE §18.5), which `onCleared` throws once every store is disposed.
 *
 * ```kotlin
 * val vm = treeViewModel { ScreenStore() }   // ScreenStore declares the screen's children
 * ```
 */
@ExperimentalStoreApi
fun <S : Store<*>> treeViewModel(create: () -> S): StoreViewModel<S> =
    object : StoreViewModel<S>() {
        override fun createStore(): S = create()

        override val disposeSubtree: Boolean = true
    }
