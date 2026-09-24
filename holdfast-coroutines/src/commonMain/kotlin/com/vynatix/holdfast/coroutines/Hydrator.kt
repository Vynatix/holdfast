@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.State
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreAttachment
import com.vynatix.holdfast.StoreAttachmentKey
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.internalAttachIfAbsent
import com.vynatix.holdfast.internalAttachment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope

// The hydration lifecycle's public surface (issue #20, R8; plan decision D19
// and deviation 8): `hydrator { }`, the Hydrator it returns, and the lookups
// over it. A store has at most one hydrator, attached through core's
// StoreAttachment slot under one key, so the store's reset() and dispose()
// reach it (HydrationEngine.kt holds the machinery).

/**
 * A store's hydration: seed it from bundled data ([HydrationSpec.base]), then
 * fetch ([HydrationSpec.refresh]) and adopt what was fetched
 * ([HydrationRefresh.adopt]) — once, however many callers ask. What
 * [hydrator] returns.
 *
 * ```
 * class HistoryStore(api: HistoryApi) : Store<HistoryStore>() {
 *     val entries by state(tags = setOf(StateTag.Remote)) { emptyList<String>() }
 *     val hydration = hydrator {
 *         base { restore(Seeds.history) }
 *         refresh { api.fetchHistory() } adopt { fetched -> entries mutate fetched }
 *     }
 * }
 *
 * store.hydration.hydrate()   // on every screen entry: only the first seeds and fetches
 * ```
 *
 * **The lifecycle** ([Hydration]). [hydrate] on a [Hydration.Detached] store
 * runs `base { }` in ONE transaction (id `HydrationSeed`) that also moves the
 * phase to [Hydration.Seeded] and marks a refresh in flight; once that
 * transaction has committed — never when it rolled back — the refresh is
 * launched on the call's `scope`. What it fetches is adopted in one
 * transaction (id `HydrationAdopt`) that moves the phase to
 * [Hydration.Hydrated]; a refresh that throws, or an adoption that fails,
 * moves it to [Hydration.Failed] in one (id `HydrationFailure`, or within
 * `HydrationAdopt`). Middleware sees every one of these transactions.
 *
 * - **Idempotent.** [hydrate] while [Hydration.Seeded] (a refresh is in
 *   flight) or [Hydration.Hydrated] does nothing: no `base { }`, no refresh.
 * - **Single flight.** Every decision — seed, retry, adopt, record a failure
 *   — reads the phase and commits the next while the hydration gate holds
 *   the store, so concurrent [hydrate] calls seed and refresh once.
 * - **Retry.** [hydrate] on [Hydration.Failed] refreshes again, WITHOUT
 *   running `base { }`: the transaction that decides to (id
 *   `HydrationRetry`) moves the phase back to [Hydration.Seeded], so fifty
 *   concurrent calls refetch once.
 * - **Back to Detached** only through [invalidate] (or [stageInvalidate]
 *   inside an action) and the store's `reset()`, which detaches inside its
 *   transaction (initial values are not hydrated ones). The next [hydrate]
 *   runs `base { }` again. A refresh still in flight then is cancelled, and
 *   whatever it brings is discarded. A sterile `restore` does not detach:
 *   stage [stageInvalidate] in the same action if its reset Remote states
 *   should be fetched again.
 * - **Adopt writes Remote only.** See [HydrationRefresh.adopt].
 *
 * **Observing it.** [state] is a read-only state of the store: observe it with
 * `effect`, the coroutines flows or Compose, and use it as a source of a
 * `derivedState` — hydrators of several stores feeding one health flag need no
 * cross-store frame. It is the hydrator's own: writing it through the store
 * (`mutate`, `bridge`, …) throws, and no snapshot, restore or reset captures
 * or writes it.
 *
 * **Where to call it.** [hydrate] runs transactions of its own, so it throws
 * inside an action, an `atomic` frame, a `suspendAction` or a `suspendAtomic`
 * of any store — body or commit — instead of deadlocking or escaping the
 * enclosing transaction. Call it from a coroutine outside them; to detach
 * inside an action, use [stageInvalidate].
 *
 * After the store is disposed, [hydrate], [invalidate], [stageInvalidate]
 * and [awaitSettled] throw [IllegalStateException]; a refresh in flight is
 * cancelled and never adopted, and [state] and [current] keep their last
 * value.
 *
 * Experimental (issue #20, R8): new surface, which soaks for two minors
 * before it can be stabilized (ROADMAP principle 6).
 */
@ExperimentalStoreApi
class Hydrator<V : Store<V>> internal constructor(
    private val engine: HydrationEngine<V, *>,
) {
    /**
     * The phase, as a read-only state of the store: observable, a valid
     * `derivedState` source, the same instance on every read. Writing it
     * through the store throws; see the class KDoc.
     */
    val state: State<Hydration> get() = engine.public

    /** [state]'s value. */
    val current: Hydration get() = engine.public.value

    /**
     * Hydrate the store, unless it already is, or is being hydrated: on
     * [Hydration.Detached], run `base { }` in the seed transaction (moving to
     * [Hydration.Seeded]); on [Hydration.Failed], decide to retry in a
     * transaction of its own (moving to [Hydration.Seeded], without
     * `base { }`); then launch the refresh on [scope]. Returns once that
     * transaction has committed and the refresh is launched — not when the
     * refresh is done: [awaitSettled] waits for that. On [Hydration.Seeded]
     * and [Hydration.Hydrated] it returns at once, doing nothing.
     *
     * [scope] runs the refresh, and defaults to the store's `Store.scope`
     * (its override, else its `bindToScope` binding, else `Store.defaultScope`,
     * as `scope` resolves). A cancellation of [scope] while the refresh runs
     * fails the hydration with that `CancellationException`, which the next
     * [hydrate] retries. A caller cancelled while it waits for the store
     * changes nothing; one cancelled after the deciding transaction committed
     * still launches the refresh.
     *
     * The wait for the store is polite — it never queues on the store's
     * serializer, and backs off in coroutine time, never through the store's
     * clock — and every decision holds the store only for its transaction.
     *
     * @throws IllegalStateException if the store is disposed; inside a state
     *   initializer, a schema migration or a derived state's compute; and
     *   inside an action, frame, `suspendAction` or `suspendAtomic` of any
     *   store (see the class KDoc).
     * @throws Throwable what the deciding transaction failed with: a throwing
     *   `base { }`, a middleware rejecting it. The phase is unchanged, and
     *   nothing is launched.
     */
    suspend fun hydrate(scope: CoroutineScope = engine.store.scope) {
        engine.hydrate(scope)
    }

    /**
     * Detach: move the phase back to [Hydration.Detached], in an action of
     * its own on the store (id `HydrationInvalidate`) — a savepoint inside
     * an action of the store, which commits, or rolls back, with it. Once it
     * commits, the refresh in flight (if any) is cancelled and never adopted,
     * and the next [hydrate] runs `base { }` again. The store's states keep
     * their values. Does nothing more on a detached hydrator.
     *
     * A blocking action like any other: see [Store.action] for where it
     * throws or waits.
     *
     * @throws IllegalStateException if the store is disposed.
     */
    fun invalidate(): TransactionResult<Unit> = engine.store.action(HydrationInvalidate(engine))

    /**
     * [invalidate], staged into the store's action open on this thread (or a
     * `suspendAction`'s transaction) instead of an action of its own: it
     * commits, or rolls back, with that action.
     *
     * @throws IllegalStateException if the store is disposed, when no action
     *   of the store is open on this thread, or when it is closed to writes
     *   (an observer of its commit).
     */
    fun stageInvalidate() {
        engine.stageInvalidate()
    }

    /**
     * Wait until no refresh is in flight, and return the phase then:
     * [Hydration.Hydrated], [Hydration.Failed], or [Hydration.Detached] (never
     * hydrated, or invalidated meanwhile) — at once when the committed phase
     * is one of those. Reads committed phases only.
     *
     * @throws IllegalStateException if the store is disposed, before or while
     *   waiting.
     */
    suspend fun awaitSettled(): Hydration = engine.awaitSettled()

    /** Names the store, never a value. */
    override fun toString(): String = "Hydrator(${engine.storeName}, ${engine.public.value::class.simpleName})"
}

/**
 * Give this store its hydrator, declared by [spec] (see [HydrationSpec] and
 * [Hydrator]). Call it once per store — typically as a property of the store,
 * `val hydration = hydrator { … }` — and [hydrate][Hydrator.hydrate] through
 * the hydrator it returns. [spec] runs now, to declare the blocks; none of
 * them runs until [Hydrator.hydrate]. The hydrator starts
 * [Hydration.Detached], lives as long as the store, and is found again with
 * [hydratorOrNull].
 *
 * Experimental (issue #20, R8).
 *
 * @throws IllegalStateException if the store is disposed, if it already has
 *   a hydrator (one per store: two would race each other's decisions), or
 *   when [spec] sets a block twice or leaves out `refresh { } adopt { }`.
 */
@ExperimentalStoreApi
fun <V : Store<V>> V.hydrator(spec: HydrationSpec<V>.() -> Unit): Hydrator<V> {
    check(!isDisposed) { "store disposed" }
    val name = this::class.simpleName ?: "Store"
    val engine = HydrationEngine(this, HydrationSpec<V>(name).apply(spec).build())
    val attachment = HydratorAttachment(engine)
    check(internalAttachIfAbsent(HydratorKey) { attachment } === attachment) {
        "$name already has a hydrator: a store has at most one, since two would race each other's decisions on " +
            "one store. Declare it once, and find it again with hydratorOrNull()."
    }
    return engine.hydrator
}

/**
 * This store's hydrator ([hydrator]), or `null` when it has none.
 *
 * Experimental (issue #20, R8).
 *
 * @throws IllegalStateException if the store is disposed.
 */
@ExperimentalStoreApi
fun <V : Store<V>> V.hydratorOrNull(): Hydrator<V>? {
    check(!isDisposed) { "store disposed" }
    @Suppress("UNCHECKED_CAST") // The key holds a hydrator of this very store, whose type is V.
    return internalAttachment(HydratorKey)?.engine?.hydrator as Hydrator<V>?
}

/**
 * [Hydrator.hydrate] each of [hydrators], in order, each on its store's
 * `Store.scope`: every seed has committed, and every refresh is launched, when
 * this returns. A hydrator that throws does not stop the rest; the first
 * failure is thrown once all have run, the others added to it as suppressed.
 * A cancellation stops at once.
 *
 * Until issue #21's tree brings `hydrateAll()`, this is how an app hydrates
 * several stores at boot.
 *
 * Experimental (issue #20, R8).
 */
@ExperimentalStoreApi
suspend fun hydrateEach(vararg hydrators: Hydrator<*>) {
    var failure: Throwable? = null
    for (hydrator in hydrators) {
        runCatching { hydrator.hydrate() }.onFailure { thrown ->
            if (thrown is CancellationException) throw thrown
            failure?.addSuppressed(thrown) ?: run { failure = thrown }
        }
    }
    failure?.let { throw it }
}

/** Where a store keeps its hydrator: one per store. */
private val HydratorKey = StoreAttachmentKey<HydratorAttachment>("hydrator")

/** The hydrator's place in its store's attachments: told of the store's reset and dispose. */
private class HydratorAttachment(
    val engine: HydrationEngine<*, *>,
) : StoreAttachment {
    /** Inside the reset's transaction: initial values are not hydrated ones, so detach (plan deviation 8). */
    override fun onStoreReset() {
        engine.detach()
    }

    override fun onStoreDisposed() {
        engine.onDisposed()
    }
}

/**
 * [Hydrator.invalidate]'s action body. A class rather than a lambda so its
 * transaction's id — the body's simple name — reads `HydrationInvalidate`.
 */
private class HydrationInvalidate<V : Store<V>>(
    private val engine: HydrationEngine<V, *>,
) : (V) -> Unit {
    override fun invoke(store: V) {
        engine.detach()
    }
}
