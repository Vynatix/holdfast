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
    operator fun getValue(thisRef: Any?, property: KProperty<*>): State<T>
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
fun <T : Any> Transformer<T>.then(other: Transformer<T>): Transformer<T> = object : Transformer<T> {
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
