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
