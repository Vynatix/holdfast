package com.vynatix.holdfast

/**
 * Subscribe to commits on this state. Top-level extension on [State] so it can be
 * called outside any `store { … }` block.
 *
 * The receiver `T` of [handler] is the new value (post-`transformer.get`). The handler
 * fires once immediately with the current value, then once for every successful
 * top-level commit that includes this state in its pending writes.
 *
 * Returns a [Disposable] that removes the observer when called. Double-dispose is safe.
 *
 * A handler that throws on a commit fire never undoes the commit. Its exception goes
 * to [Store.uncaughtObserverHandler], or is logged (naming the store) while none is
 * set, and the other observers still run — unless [Store.uncaughtObserverHandler]
 * itself throws, which ends that commit's fanout early and makes the action return an
 * Error (the values stay applied). The initial fire is different: it runs inside
 * `effect` and throws to the caller.
 *
 * On a commit fire the handler must not write back into the state's own store: that
 * commit has already applied, so the write could never land. `mutate`/`update`/`emit`
 * on it throw an [IllegalStateException] (reported as above). A nested `action`/`atomic`
 * on it returns [TransactionResult.Error] without running — check that result (e.g.
 * `getOrThrow()`), or the write is dropped without a log line. Write in the action
 * itself, derive the value (`computed`/`derived`), or run a follow-up action once the
 * commit has finished: from another thread, or launched on a dispatcher that does not
 * run it inline, checking the result —
 * `store.scope.launch(Dispatchers.Default) { store action { … }.getOrThrow() }`. On
 * `Dispatchers.Unconfined`, or `Dispatchers.Main.immediate` while already on the main
 * thread, the launched body runs inside this commit and is refused the same way; the
 * launch alone would drop that Error. See GUIDE §4.4.
 *
 * Resolution detail: the cast to [MutableState] stays inside this function — it never
 * leaks into the published signature. Calling `effect` on a foreign `State` (not produced
 * by `store.state { … }`) throws — every observable state in this library is a
 * [MutableState] under the hood.
 *
 * ```
 * val v = MyStore()
 * val d = v.count effect { println(it) }   // top-level, no `v { … }` wrapping
 * v action { count mutate 7 }              // prints 7
 * d.dispose()
 * ```
 */
@OptIn(StoreInternalApi::class)
infix fun <T : Any> State<T>.effect(handler: T.() -> Unit): Disposable {
    @Suppress("UNCHECKED_CAST")
    val mutable = (this as? MutableState<T>) ?: error("effect is only defined for State produced by store.state { ... }")
    if (mutable.owningStore.isDisposed) error("store disposed")
    return mutable.observe(handler::invoke)
}
