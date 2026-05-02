package com.vynatix.vault

import kotlin.reflect.KProperty

/**
 * Read-only side of a two-way external sync. The vault calls [observe] when a
 * [Bridge] is attached; implementations typically replay any persisted/initial
 * value through `observer` for load-on-attach.
 */
fun interface Observable<T : Any> {
    fun observe(observer: (T) -> Unit): Disposable
}

/**
 * Write-only side of a two-way external sync. The vault calls [publish] on every
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

/** Lazy initial-value provider for [Vault.state]. */
fun interface Initializer<T : Any> : () -> T

/**
 * Read-only contract for a piece of vault state. Read `value` to see the
 * post-`transformer.get` view; on the owner thread of an active transaction,
 * `value` reflects pending writes (read-your-own-writes).
 */
interface State<T : Any> {
    val value: T
}

/**
 * Property delegate produced by [Vault.state]. Returns the same `State<T>` on
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
 * A handle to release something — an effect subscription, an inbound bridge
 * registration, etc. [dispose] is idempotent: calling it twice is safe.
 */
fun interface Disposable {
    fun dispose()
}
