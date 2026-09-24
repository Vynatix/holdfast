@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.MutableState
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.internalSealedState
import com.vynatix.holdfast.internalStageSealed
import com.vynatix.holdfast.internalTopLevelAction
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

// One store's hydration lifecycle (issue #20, R8; plan decision D19): the
// phase, the decisions that move it, and what follows a commit of it.
//
// The phase lives in two SEALED states of the store (core's
// internalSealedState): [HydrationEngine.phase], which the engine decides on
// and which records the in-flight refresh's number, and
// [HydrationEngine.public], its projection, which Hydrator.state hands out.
// Both are staged together, in the transaction that decides — so the in-flight
// mark commits in the same transaction that decides to refresh (R8's guard
// guarantee), and rolls back with it. Neither is store state: no snapshot,
// restore or reset captures or writes it, and every store write entrypoint
// refuses it.

/** The name [HydrationEngine.public] carries in messages and derived state names: `Store.hydration`. */
internal const val HYDRATION_STATE_NAME = "hydration"

/** The seed transaction's id: `base { }` and the move to Seeded. */
internal const val SEED_ID = "HydrationSeed"

/** The retry transaction's id: the move from Failed back to Seeded. */
internal const val RETRY_ID = "HydrationRetry"

/** A refresh in flight: its number, and the job running it. */
private class RunningRefresh(
    val refresh: Long,
    val job: Job,
)

/**
 * The machinery behind one store's [Hydrator]: see the top of this file, and
 * [Hydrator] for the contract.
 */
internal class HydrationEngine<V : Store<V>, F>(
    val store: V,
    val plan: HydrationPlan<V, F>,
) {
    val storeName: String = store::class.simpleName ?: "Store"

    /** The phase decided on: the public one, plus which refresh is in flight. */
    val phase: MutableState<HydrationPhase> =
        store.internalSealedState("$HYDRATION_STATE_NAME.phase", HydrationPhase.Detached, sealedRefusal(storeName))

    /** [phase]'s public projection: what [Hydrator.state] is. */
    val public: MutableState<Hydration> =
        store.internalSealedState(HYDRATION_STATE_NAME, Hydration.Detached, sealedRefusal(storeName))

    val gate = HydrationGate(store)
    val hydrator = Hydrator(this)

    /** Numbers each refresh a decision launches, so a stale one is told from the one in flight. */
    private val refreshes = atomic(0L)

    /** The latest refresh launched, for cancelling it once the phase leaves it. */
    private val running = atomic<RunningRefresh?>(null)

    /** The committed [phase], for [awaitSettled]; `null` once the store is disposed. */
    private val committed = MutableStateFlow<HydrationPhase?>(HydrationPhase.Detached)

    init {
        phase.observe { onCommitted(it) }
    }

    /**
     * [Hydrator.hydrate]: decide under the gate, then launch the refresh the
     * decision committed to — only after its transaction committed.
     */
    suspend fun hydrate(scope: CoroutineScope) {
        check(!store.isDisposed) { "store disposed" }
        refuseInsideEntry(store, "hydrate()")
        // A committed phase that decides nothing needs no gate: the call is
        // ordered as if it ran before whatever commits next.
        if (!phase.value.decides) return
        val refresh = gate.hold { decide() } ?: return
        HydrationRefreshRun(this, refresh).start(scope)
    }

    /**
     * Under the gate: seed a detached store, or retry a failed refresh,
     * committing the in-flight mark in the same transaction, and return the
     * new refresh's number; `null` when the phase decides nothing.
     *
     * @throws Throwable what the deciding transaction failed with (a throwing
     *   `base { }`, a middleware rejecting it): nothing is launched.
     */
    private fun decide(): Long? =
        when (phase.value) {
            HydrationPhase.Detached -> commitInFlight(SEED_ID, seeding = true)
            is HydrationPhase.Failed -> commitInFlight(RETRY_ID, seeding = false)
            else -> null
        }

    private fun commitInFlight(
        id: String,
        seeding: Boolean,
    ): Long {
        val refresh = refreshes.incrementAndGet()
        store
            .internalTopLevelAction(id) {
                if (seeding) plan.base(this)
                stagePhase(HydrationPhase.Seeded(refresh))
            }.getOrThrow()
        return refresh
    }

    /** Stage [next] into the store's transaction open on this thread: the phase and its projection together. */
    fun stagePhase(next: HydrationPhase) {
        store.internalStageSealed(phase, next)
        store.internalStageSealed(public, next.public)
    }

    /** Whether refresh [refresh] is the one in flight in the phase this thread reads. */
    fun isInFlight(refresh: Long): Boolean = (phase.value as? HydrationPhase.Seeded)?.refresh == refresh

    /** Record [job] as running refresh [refresh], unless a later refresh is recorded already. */
    fun track(
        refresh: Long,
        job: Job,
    ) {
        running.update { current ->
            if (current != null && current.refresh > refresh) current else RunningRefresh(refresh, job)
        }
    }

    /** [Hydrator.stageInvalidate]: detach, staged into the caller's transaction. */
    fun stageInvalidate() {
        check(!store.isDisposed) { "store disposed" }
        check(store.activeTransaction != null) {
            "hydrator.stageInvalidate() on $storeName stages into an action of $storeName open on this thread, and " +
                "none is. Call it inside store.action { … } (it commits, or rolls back, with that action), or call " +
                "hydrator.invalidate(), which opens an action of its own."
        }
        detach()
    }

    /** Stage the move to Detached, unless the phase this transaction reads already is. */
    fun detach() {
        if (phase.value != HydrationPhase.Detached) stagePhase(HydrationPhase.Detached)
    }

    /** [Hydrator.awaitSettled]. */
    suspend fun awaitSettled(): Hydration {
        check(!store.isDisposed) { "store disposed" }
        val settled = committed.first { it !is HydrationPhase.Seeded }
        return checkNotNull(settled) { "store disposed while awaiting its hydration" }.public
    }

    /**
     * A commit of [phase] (and the initial callback): mirror it for
     * [awaitSettled], and on a detach cancel the refresh it abandoned — its
     * result would be discarded anyway ([HydrationRefreshRun]).
     */
    private fun onCommitted(next: HydrationPhase) {
        committed.update { if (it == null) null else next }
        if (next == HydrationPhase.Detached) running.getAndSet(null)?.job?.cancel()
    }

    /** The store was disposed: stop the refresh in flight, and release [awaitSettled]'s waiters. */
    fun onDisposed() {
        committed.value = null
        running.getAndSet(null)?.job?.cancel()
    }
}

/** Whether a hydrate() decides anything in this phase: seed a detached store, or retry a failed refresh. */
private val HydrationPhase.decides: Boolean
    get() = this == HydrationPhase.Detached || this is HydrationPhase.Failed

/** What a write to a hydrator's state throws: whose it is, and what to call instead. */
private fun sealedRefusal(storeName: String): String =
    "it is the hydration phase of $storeName's hydrator, which only the hydrator writes: hydrate() moves it on, " +
        "and invalidate(), stageInvalidate() inside an action, or the store's reset() moves it back to Detached. It " +
        "is not store state — no snapshot, restore or reset captures or writes it. Fix: drive it through the " +
        "Hydrator instead of writing it."

/** A top-level transaction's id for the adoption of what a refresh fetched. */
internal const val ADOPT_ID = "HydrationAdopt"

/** A top-level transaction's id recording that a refresh (or its adoption) failed. */
internal const val FAILURE_ID = "HydrationFailure"

/** Commit [next] as a transaction of its own under [id] (the gate holds the store). */
internal fun <V : Store<V>> HydrationEngine<V, *>.commitPhase(
    id: String,
    next: HydrationPhase,
) = store.internalTopLevelAction(id) { stagePhase(next) }
