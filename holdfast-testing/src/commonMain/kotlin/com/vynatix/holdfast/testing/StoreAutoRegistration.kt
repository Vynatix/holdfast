package com.vynatix.holdfast.testing

import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.State
import com.vynatix.holdfast.Transaction
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.Holdfast
import kotlin.reflect.KProperty1

/**
 * Member-extension surface that lets tests skip explicit [HoldfastTestScope.track]
 * and call action / read / timeline / emissions / etc. directly on a
 * [Holdfast] instance inside a [vaultTest] block:
 *
 * ```
 * @Test fun implicit() = vaultTest {
 *     val v = MyVault()
 *     v.action { count mutate 1 }                  // member of Holdfast, sees recorder if installed
 *     assertEquals(1, v.read { count.value })      // extension — auto-registers on first touch
 * }
 * ```
 *
 * **Resolution model — scope-member extensions, not context receivers.**
 * Kotlin 2.3.21 supports `context(...)` receivers, but library guidance still
 * treats the syntax as in-flux for stable ABI. The KMP-portable choice is the
 * member-extension pattern: extension functions declared as members of an
 * interface (here [HoldfastAutoRegistration]) which [HoldfastTestScope] implements.
 *
 * On first invocation of any extension below, [track] is called on `this`.
 * [HoldfastTestScope]'s implementation of [track] is idempotent by reference
 * identity — see [com.vynatix.holdfast.testing.internal.HandleRegistry] — so a
 * mix of explicit `track(v)` and implicit `v.read { }` always resolves to the
 * same [HoldfastHandle]. Subsequent invocations route to the same handle, and
 * the handle is disposed at scope exit by [HoldfastTestScope.tearDown] regardless
 * of whether it was registered explicitly or via auto-registration.
 *
 * Each extension forwards directly to its [HoldfastHandle] counterpart. Behaviour
 * is unchanged from the explicit form — pending-error tracking, recorder
 * lifecycle, [com.vynatix.holdfast.testing.internal.PendingErrorRegistry] mark
 * consumption, and [Capture] semantics all match the no-extension code path.
 *
 * Auto-registered handles use [Capture.All]. Tests that need [Capture.None] or
 * [Capture.RingBuffer] must call [track] explicitly before the first
 * extension-call site so the registry's idempotent-by-identity rule attaches
 * the right [Capture] to the handle.
 *
 * **Important caveat — `v.action {}` does NOT auto-register**. [com.vynatix.holdfast.Holdfast]
 * declares `action` as a member infix function, and Kotlin's resolution rules
 * always prefer a class member over an extension of the same shape. The
 * auto-registration extension `V.action` declared here is therefore shadowed
 * by `Holdfast.action` whenever the call site is `v.action { ... }`. The
 * extension is still kept in the API surface — it routes through
 * [HoldfastHandle.action] for explicit-receiver call paths and is the natural
 * spec-compliant declaration — but in practice the recorder fires for
 * `v.action {}` only when one of the **other** extensions has already
 * triggered auto-registration (or the user has called [track] explicitly).
 * The same caveat does NOT apply to [V.suspendAction]: `Holdfast` has no
 * matching member, so the member-extension wins.
 *
 * Practical pattern: trigger auto-registration once via any non-action
 * extension (`v.read { }`, `v.timeline`, etc.) BEFORE the first
 * `v.action { }`, OR call `track(v)` upfront. Once the recorder is installed,
 * subsequent `v.action {}` calls fire it through `Holdfast`'s middleware chain.
 *
 * The single exception to "all extensions live in this interface" is
 * [middlewareEventsOf] with a reified type parameter — declared on
 * [HoldfastTestScope] itself, not here. Reified type parameters require `inline`,
 * and the inline-reified call ergonomically resolves cleaner when declared on
 * the implementing class with a star-projected receiver. See [HoldfastTestScope]
 * for that declaration.
 */
interface HoldfastAutoRegistration {

    /**
     * Auto-registration's underlying registry call. Implemented by
     * [HoldfastTestScope] using its [com.vynatix.holdfast.testing.internal.HandleRegistry].
     * Idempotent by reference identity — repeated calls with the same vault
     * return the same handle, ignoring a different [capture] on the second
     * call.
     */
    fun <V : Holdfast<V>> track(vault: V, capture: Capture = Capture.All): HoldfastHandle<V>

    /**
     * Auto-registering wrapper around [HoldfastHandle.action]. Calls
     * [track] on `this` first (idempotent), then forwards to the handle's
     * [com.vynatix.holdfast.testing.HoldfastHandle.action]. Returns the
     * [TransactionResult] verbatim; pending-error tracking applies.
     *
     * **Caveat**: This extension is shadowed by [com.vynatix.holdfast.Holdfast.action],
     * the member infix function on `Holdfast`, whenever the call site is
     * `v.action { ... }`. The shadow rule is Kotlin's standard member-vs-
     * extension precedence and cannot be overridden. See the
     * [HoldfastAutoRegistration] file-level KDoc for the practical pattern.
     */
    fun <V : Holdfast<V>, R> V.action(body: V.() -> R): TransactionResult<R> = track(this).action(body)

    /**
     * Auto-registering wrapper around [HoldfastHandle.suspendAction]. Calls
     * [track] on `this` first (idempotent), then forwards to the handle's
     * [com.vynatix.holdfast.testing.HoldfastHandle.suspendAction]. Returns the
     * [TransactionResult] verbatim; pending-error tracking applies.
     */
    suspend fun <V : Holdfast<V>, R> V.suspendAction(body: suspend V.() -> R): TransactionResult<R> = track(this).suspendAction(body)

    /**
     * Auto-registering wrapper around [HoldfastHandle.read]. Calls [track] on
     * `this` first (idempotent), then forwards to the handle's
     * [com.vynatix.holdfast.testing.HoldfastHandle.read]. Plain read assertion — no
     * transaction is opened.
     */
    fun <V : Holdfast<V>, R> V.read(block: V.() -> R): R = track(this).read(block)

    /**
     * Auto-registering wrapper around [HoldfastHandle.timeline]. Calls [track]
     * on `this` first; returns the recorder's defensive-copy snapshot in push
     * order.
     */
    val <V : Holdfast<V>> V.timeline: List<HoldfastEvent>
        get() = track(this).timeline

    /**
     * Auto-registering wrapper around [HoldfastHandle.transactions]. Calls
     * [track] on `this` first; returns the timeline filtered to
     * [TransactionEvent]s.
     */
    val <V : Holdfast<V>> V.transactions: List<TransactionEvent>
        get() = track(this).transactions

    /**
     * Auto-registering wrapper around [HoldfastHandle.emissions]. Calls
     * [track] on `this` first; returns [EmissionEvent]s targeting the [State]
     * referenced by [prop].
     */
    fun <V : Holdfast<V>> V.emissions(prop: KProperty1<V, State<*>>): List<EmissionEvent> = track(this).emissions(prop)

    /**
     * Auto-registering wrapper around [HoldfastHandle.bridgeEvents]. Calls
     * [track] on `this` first; returns [BridgeEvent]s targeting the [State]
     * referenced by [prop]. Empty in v1 — Issue 12 owns the bridge
     * instrumentation; the typed view ships now so future matchers can target
     * it without churn.
     */
    fun <V : Holdfast<V>> V.bridgeEvents(prop: KProperty1<V, State<*>>): List<BridgeEvent> = track(this).bridgeEvents(prop)

    /**
     * Auto-registering wrapper around the instance-keyed
     * [HoldfastHandle.middlewareEventsOf] overload. Calls [track] on `this`
     * first; returns [MiddlewareEvent]s whose [MiddlewareEvent.middleware]
     * IS [instance] by referential equality.
     *
     * The reified-type overload is on [HoldfastTestScope] itself rather than
     * here — see the file-level KDoc for the rationale.
     */
    fun <V : Holdfast<V>, M : Middleware<V>> V.middlewareEventsOf(instance: M): List<MiddlewareEvent> =
        track(this).middlewareEventsOf(instance)

    /**
     * Auto-registering wrapper around [HoldfastHandle.lastTransaction]. Calls
     * [track] on `this` first; returns the most recent [Transaction] the
     * recorder observed in `onTransactionStarted`, or `null` when no action
     * has run yet (or [Capture.None] is in effect).
     */
    val <V : Holdfast<V>> V.lastTransaction: Transaction?
        get() = track(this).lastTransaction

    /**
     * Auto-registering wrapper around [HoldfastHandle.lastResult]. Calls
     * [track] on `this` first; returns the most recent [TransactionResult]
     * captured by [HoldfastHandle.action] / [HoldfastHandle.suspendAction], or
     * `null` when no action has run yet.
     */
    val <V : Holdfast<V>> V.lastResult: TransactionResult<*>?
        get() = track(this).lastResult
}
