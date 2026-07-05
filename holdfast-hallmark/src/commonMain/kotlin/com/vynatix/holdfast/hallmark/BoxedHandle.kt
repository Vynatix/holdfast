@file:OptIn(StoreInternalApi::class)

package com.vynatix.holdfast.hallmark

import com.vynatix.hallmark.Boxed
import com.vynatix.hallmark.Validator
import com.vynatix.holdfast.MutableState
import com.vynatix.holdfast.State
import com.vynatix.holdfast.StateDelegate
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import kotlin.reflect.KProperty

/**
 * Bundles a Store [State] with the [Validator] that gates it. Returned by
 * [boxedHandle]. The [V] type parameter ties the handle to the concrete
 * [Store] subclass that owns it, so [assign] can reject a handle used inside a
 * different store's `action { }` at compile time.
 *
 * Inside a `store action { ... }` block the handle exposes:
 *  - [state] — the underlying Store state, used as the receiver for `mutate`.
 *  - [validator] — the static validator, used to civilize raw primitives.
 *  - [civilize] — convenience: civilize a primitive into the wrapped form.
 *
 * Usage:
 * ```kotlin
 * class UserStore : Store<UserStore>() {
 *     val email by boxedHandle(EmailValidator) { "init@example.com" }
 * }
 *
 * store action {
 *     email.state mutate email.civilize("alice@example.com")
 * }
 * ```
 *
 * The two-step `state mutate civilize(...)` pattern above is the closest you can
 * get to the one-line `email assign "..."` infix ([assign]) **without** enabling
 * Kotlin context parameters (`-Xcontext-parameters`) in the consuming module.
 * `civilize` is just sugar for `validator of primitive` — same throw semantics.
 */
data class BoxedHandle<V : Store<V>, P : Any, O : Boxed<P>>(val state: State<O>, val validator: Validator<P, O>) {
    /** Civilize [primitive] through the bundled validator. Throws on rejection. */
    fun civilize(primitive: P): O = validator of primitive
}

/**
 * Property delegate that returns a [BoxedHandle] (state + validator) on every
 * read.
 */
class BoxedHandleDelegate<V : Store<V>, P : Any, O : Boxed<P>> internal constructor(
    private val backing: StateDelegate<O>,
    private val validator: Validator<P, O>,
) {
    operator fun getValue(thisRef: Any?, property: KProperty<*>): BoxedHandle<V, P, O> =
        BoxedHandle(backing.getValue(thisRef, property), validator)
}

/**
 * Like [boxed], but the resulting property is a [BoxedHandle] (carrying both
 * the underlying state and the validator) instead of a bare [State]. Useful
 * when the validator instance is awkward to reference at the mutate site,
 * or when you want the [assign] infix sugar.
 *
 * If you don't need the bundled validator at the call site, prefer [boxed].
 */
fun <V : Store<V>, P : Any, O : Boxed<P>> Store<V>.boxedHandle(
    validator: Validator<P, O>,
    initial: () -> P,
): BoxedHandleDelegate<V, P, O> =
    BoxedHandleDelegate(
        backing = state(transformer = ValidatingTransformer(validator)) { validator of initial() },
        validator = validator,
    )

/**
 * Civilize [primitive] through the handle's validator and atomically mutate
 * the underlying state inside an existing `store action { … }` block:
 *
 * ```kotlin
 * store action {
 *     email assign "alice@example.com"
 *     displayName mutate "Alice"
 * }
 * ```
 *
 * Reads as the natural one-liner one would expect for boundary-validated
 * state. Throws `HallmarkException` (and rolls back the transaction) if
 * the primitive fails validation.
 *
 * **Compile-time gate.** The [context] receiver is the handle's owning store
 * type [V] (not a bare `Store<*>`), so inside `otherStore.action { }` — where
 * the `@StoreActionDsl` marker hides every outer receiver — a handle from a
 * different store fails to resolve. You can only `assign` a handle inside its
 * own store's action.
 *
 * **Runtime gate.** Even past the type system (generic/erased escapes),
 * `assign` refuses to run unless it is genuinely inside the owning store's open
 * transaction on the owning execution context. Outside an `action { }` it would
 * otherwise synthesize a silent one-shot transaction — committing immediately
 * and unable to roll back with the surrounding logic. Misuse throws
 * [IllegalStateException] with a teaching message instead.
 *
 * Requires Kotlin context parameters (`-Xcontext-parameters`) in the consuming
 * module. If you cannot enable that flag, use the two-step
 * `handle.state mutate handle.civilize(primitive)` form instead.
 */
context(store: V)
infix fun <V : Store<V>, P : Any, O : Boxed<P>> BoxedHandle<V, P, O>.assign(primitive: P) {
    requireInOwningAction(store, state)
    with(store) { state mutate civilize(primitive) }
}

/**
 * Runtime guard for [assign]: proves the caller is inside [store]'s own open
 * transaction, on the transaction's owner execution context, before letting the
 * mutate fall through (where a missing/foreign transaction would silently become
 * a one-shot). All three failure modes throw [IllegalStateException] with a
 * teaching message.
 */
private fun requireInOwningAction(store: Store<*>, state: State<*>) {
    val txn = store.activeTransaction
        ?: error(
            "`assign` must be called inside `store action { }`. Outside an action it " +
                "would commit a silent one-shot transaction — observers would see the " +
                "write immediately and it could not roll back with the surrounding " +
                "logic. Open an action (`store action { handle assign value }`), or use " +
                "the two-step `handle.state mutate handle.civilize(value)` form.",
        )

    // Owner-context check. A `suspendAction` body may legitimately resume on a
    // different thread; the store signals that window via `suspendingOwner`, and
    // `mutate` relaxes its thread check for it, so we do too. Otherwise the active
    // transaction must be owned by THIS thread — `Transaction.modifiedStates` is
    // the store's owner-thread-only accessor and throws off-thread.
    if (store.suspendingOwner == null) {
        try {
            txn.modifiedStates
        } catch (e: IllegalStateException) {
            throw IllegalStateException(
                "`assign` must run on the thread that opened `store action { }`. The " +
                    "active transaction is owned by another thread, so this call would " +
                    "fork a silent one-shot transaction instead of joining the action. " +
                    "Perform the assign on the action's owner thread.",
                e,
            )
        }
    }

    // Wrong-store check: covers generic/erased escapes past the typed context.
    val owningStore = (state as? MutableState<*>)?.owningStore
    check(owningStore === store) {
        "`assign` target belongs to a different Store than the enclosing `action { }`. " +
            "The BoxedHandle's state is owned by another Store instance — assign it " +
            "inside that store's own action."
    }
}
