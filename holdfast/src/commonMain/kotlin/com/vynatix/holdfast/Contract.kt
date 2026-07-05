package com.vynatix.holdfast

import kotlin.reflect.KProperty

/**
 * Read-only side of a two-way external sync. The store calls [observe] when a
 * [Bridge] is attached; implementations typically replay any persisted/initial
 * value through `observer` for load-on-attach.
 */
fun interface Observable<T : Any> {
    fun observe(observer: (T) -> Unit): Disposable
}

/**
 * Write-only side of a two-way external sync. The store calls [publish] on every
 * successful commit that includes a state attached via [Bridge].
 *
 * API-honesty note: the [Boolean] return is currently NOT read by the store —
 * commit-time publish is fire-and-forget, so returning `false` does not fail or
 * retry the commit. The return type is retained for a future
 * publish-acknowledgement contract and will be reconsidered (likely narrowed to
 * `Unit`) in the pre-1.0 breaking window; do not rely on the caller observing it.
 */
fun interface Publisher<T : Any> {
    fun publish(value: T): Boolean
}

/**
 * Two-way bridge between a [State] and an external system (persistence, server,
 * StateFlow, …). Inbound updates flow via [Observable.observe]; outbound through
 * [Publisher.publish] on each commit.
 */
interface Bridge<T : Any> :
    Observable<T>,
    Publisher<T>

/** Lazy initial-value provider for [Store.state]. */
fun interface Initializer<T : Any> : () -> T

/**
 * Read-only contract for a piece of store state. Read `value` to see the
 * post-`transformer.get` view; on the owner thread of an active transaction,
 * `value` reflects pending writes (read-your-own-writes).
 */
interface State<T : Any> {
    val value: T
}

/**
 * Property delegate produced by [Store.state]. Returns the same `State<T>` on
 * every access — delegate identity is preserved.
 */
fun interface StateDelegate<T : Any> {
    operator fun getValue(
        thisRef: Any?,
        property: KProperty<*>,
    ): State<T>
}

/**
 * Register a `by state { … }` property EAGERLY, at the owner's construction
 * time, instead of lazily on first read. Kotlin calls this `provideDelegate`
 * operator when the delegate is created (during the owner's constructor); it
 * invokes [StateDelegate.getValue] once to create the backing state, then
 * returns the same delegate for normal reads.
 *
 * Why: previously `snapshot()` and [Store.properties] missed any state whose
 * delegate had never been read. Eager registration closes that surprise — a
 * freshly-constructed store already has every declared state registered.
 *
 * Trade-off (semantic break vs. the old lazy behavior): the `{ init }`
 * initializer lambdas now run at construction, in declaration order. A
 * forward-referencing initializer — `val y by state { x.value }` where `x` is
 * declared LATER — will now fail at construction instead of on first read.
 * Reorder such declarations so a state's dependencies are declared before it,
 * or compute the dependency inside a `computed { }` / `derived(...)` instead.
 */
operator fun <T : Any> StateDelegate<T>.provideDelegate(
    thisRef: Any?,
    property: KProperty<*>,
): StateDelegate<T> {
    // Force registration now (idempotent — a later read returns the same state).
    getValue(thisRef, property)
    return this
}

/**
 * Pure transformation applied symmetrically on read and write of a [State].
 *
 *  - [set] runs once per `mutate` to compute the value to store. Use it for
 *    normalization (`trim`, `lowercase`), encoding, validation that fails fast.
 *  - [get] runs on every read of `value`. Use it for projection (decode).
 *
 * Both must be pure — they are called inside locks. For asymmetric behavior
 * (`set` and `get` produce different shapes) the library guarantees that
 * rollback never re-runs `set` on a stored value, so reading invariants
 * survive rolled-back transactions.
 *
 * [shouldTransform] is consulted before each call to skip the transformer for
 * sentinel values (e.g. an empty/uninitialized instance that should round-trip
 * unchanged).
 */
interface Transformer<T : Any> {
    fun set(value: T): T

    fun get(value: T): T

    fun shouldTransform(value: T): Boolean = true
}

/**
 * Compose two [Transformer]s. The result's `set` runs `this.set` then
 * `other.set`; its `get` runs `other.get` then `this.get` (reverse order, so
 * round-trips: `outer.then(inner).get(outer.then(inner).set(x)) == x` when
 * each transformer is itself a round-trip).
 *
 * Useful for chaining domain transformers — e.g. validate-then-encrypt:
 * ```kotlin
 * val pipeline = ValidatingTransformer(EmailValidator).then(EncryptingTransformer(cipher))
 * store { state(transformer = pipeline) { … } }
 * ```
 *
 * If either transformer's [shouldTransform] returns false on the inbound
 * value, the composed transformer skips that side accordingly.
 */
fun <T : Any> Transformer<T>.then(other: Transformer<T>): Transformer<T> =
    object : Transformer<T> {
        override fun set(value: T): T {
            val first = if (this@then.shouldTransform(value)) this@then.set(value) else value
            return if (other.shouldTransform(first)) other.set(first) else first
        }

        override fun get(value: T): T {
            val first = if (other.shouldTransform(value)) other.get(value) else value
            return if (this@then.shouldTransform(first)) this@then.get(first) else first
        }

        override fun shouldTransform(value: T): Boolean = this@then.shouldTransform(value) || other.shouldTransform(value)
    }

/**
 * A handle to release something — an effect subscription, an inbound bridge
 * registration, etc. [dispose] is idempotent: calling it twice is safe.
 */
fun interface Disposable {
    fun dispose()
}
