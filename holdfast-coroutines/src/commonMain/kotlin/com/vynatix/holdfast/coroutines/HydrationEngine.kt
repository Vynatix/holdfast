@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.MutableState
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.internalSealedState
import com.vynatix.holdfast.internalStageSealed
import com.vynatix.holdfast.internalStagesHere
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
// guarantee), and rolls back with it. Neither is store state: no snapshot or
// restore captures or writes it, reset() only moves it to Detached (through
// the hydrator's attachment, inside the reset's transaction), and every store
// write entrypoint refuses it.
//
// A hydrator with an overlay (HydrationSpec.overlay; OverlayBinding.kt) reads
// what the seed puts back BEFORE it takes the gate — the blob under the
// overlay's key, suspending, outside every lock — and the seed transaction
// restores it after `base { }`. A read the gate finds overtaken (the overlay
// has stood anew since: another seed loaded it, clearOverlay() ran) is read
// again before anything is decided. The overlay's report, and the signal that
// lets its writer write, wait until the gate has released the store.

/** The name [HydrationEngine.public] carries in messages and derived state names: `Store.hydration`. */
internal const val HYDRATION_STATE_NAME = "hydration"

/** The seed transaction's id: `base { }` and the move to Seeded. */
internal const val SEED_ID = "HydrationSeed"

/** The retry transaction's id: the move from Failed (or a stranded Seeded) back to Seeded. */
internal const val RETRY_ID = "HydrationRetry"

/** A refresh in flight: its number, and the job running it. */
private class RunningRefresh(
    val refresh: Long,
    val job: Job,
)

/**
 * What a decision committed to: the refresh to launch, what its
 * transaction's fanout failed with after the commit applied, if anything
 * (an observer failing through a rethrowing `uncaughtObserverHandler`), and
 * where its seed left the overlay: the report of a blob it could not apply,
 * or whether it stands LOADED, so its writer is signalled.
 */
private class Decision(
    val refresh: Long,
    val fanoutFailure: Throwable?,
    val overlay: OverlaySettled = OverlaySettled.NONE,
) {
    companion object {
        /** No decision: the overlay read before the gate was overtaken, so read it again first. */
        val REREAD = Decision(0L, null)
    }
}

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

    /** The persisted overlay ([HydrationSpec.overlay]), or `null` when the hydrator has none. */
    val overlay: OverlayBinding<V>? = plan.overlay?.let { OverlayBinding(store, it, gate, storeName) }

    val hydrator = Hydrator(this)

    /** Numbers each refresh a decision launches, so a stale one is told from the one in flight. */
    private val refreshes = atomic(0L)

    /** The latest refresh launched, for cancelling it once the phase leaves it. */
    private val running = atomic<RunningRefresh?>(null)

    /**
     * The highest refresh whose outcome could not be recorded ([strand]), `0`
     * while none: its `Seeded` phase stays committed with nothing running, so
     * [hydrate] retries it as it retries `Failed`.
     */
    private val stranded = atomic(0L)

    /**
     * The committed [phase], for [awaitSettled] and the checks a refresh makes
     * outside the gate; `null` once the store is disposed. Updated in the
     * phase's commit fanout — so before a refresh that commit decided on is
     * launched — and by [strand]. [phase] is sealed, so its observer runs
     * before every other observer of the commit (core's fanOutApplied): a
     * user callback that ends the fanout through a rethrowing
     * `uncaughtObserverHandler` cannot leave this mirror behind the phase.
     */
    private val committed = MutableStateFlow<HydrationPhase?>(HydrationPhase.Detached)

    init {
        phase.observe { onCommitted(it) }
    }

    /**
     * [Hydrator.hydrate]: decide under the gate, then launch the refresh the
     * decision committed to — only after its transaction committed (applied:
     * a fanout failure after that still launches it, then throws).
     */
    suspend fun hydrate(scope: CoroutineScope) {
        check(!store.isDisposed) { "store disposed" }
        refuseInsideEntry(store, "hydrate()", ::insideEntryMessage)
        var inbound: OverlayInbound? = null
        var decision: Decision? = Decision.REREAD
        while (decision === Decision.REREAD) {
            // A committed phase that decides nothing needs no gate: the call is
            // ordered as if it ran before whatever commits next. (A phase staged
            // by a transaction parked on this thread can only be a detach, which
            // decides: the gate then reads the committed one.)
            if (!phase.value.decides(stranded.value)) return
            // What a seed would put back, read outside the gate.
            if (overlay != null && phase.value == HydrationPhase.Detached) inbound = overlay.current(inbound)
            decision = gate.hold { decide(inbound) }
        }
        val decided = decision ?: return
        // Once the gate has released the store: the handler — and the writer,
        // which a dispatcher may run inline here — may open an action on it.
        decided.overlay.failure?.let { store.reportOverlay(it) }
        if (decided.overlay.loaded) overlay?.writer?.signalIfBehind()
        HydrationRefreshRun(this, decided.refresh).start(scope)
        decided.fanoutFailure?.let { throw it }
    }

    /**
     * Under the gate: seed a detached store — putting back [inbound], the
     * overlay read before the gate, unless it was overtaken
     * ([Decision.REREAD]) — or retry a failed (or stranded) refresh; `null`
     * when the phase decides nothing. See [commit].
     */
    private fun decide(inbound: OverlayInbound?): Decision? {
        val seen = phase.value
        return when {
            seen == HydrationPhase.Detached ->
                if (overlay?.isCurrent(inbound) == false) Decision.REREAD else commit(seen, SEED_ID, inbound)
            seen.decides(stranded.value) -> commit(seen, RETRY_ID, inbound = null)
            else -> null
        }
    }

    /**
     * Under the gate: commit the decision [id] on the phase [seen] — the
     * seed (`base { }`, then the overlay's [inbound]) or the retry — with the
     * in-flight mark in the same transaction, and return it: the new
     * refresh's number; `null` when the transaction found the phase moved.
     *
     * The transaction re-reads the phase once it holds the store's
     * transaction lock: the gate's serializer keeps out every holder but a
     * blocking action that read it before its first install, which holds
     * that lock alone and may have committed a detach since the gate read
     * the phase. Then it decides nothing — as if this call had decided, and
     * that detach had cancelled what it launched. (A `:holdfast-testing`
     * open transaction, which holds the active-transaction slot without the
     * lock, makes the transaction fail instead: see HydrationGate.kt.)
     *
     * A transaction whose commit applied but whose fanout then failed (an
     * observer failing through a rethrowing `uncaughtObserverHandler`, which
     * has received that failure) still decided: its phase is committed, so
     * its refresh is returned with that failure, for [hydrate] to launch and
     * then throw — as `action` reports an error while its values stay applied.
     *
     * The overlay stands where the seed left it only when the seed's commit
     * applied ([OverlayBinding.settle]); its report of a blob it could not
     * apply, or that it stands LOADED, is returned with the decision, for
     * [hydrate] to report, or to signal the writer, once the gate has
     * released the store.
     *
     * @throws Throwable what the deciding transaction failed with when it
     *   rolled back (a throwing `base { }`, a middleware rejecting it):
     *   nothing is launched.
     */
    private fun commit(
        seen: HydrationPhase,
        id: String,
        inbound: OverlayInbound?,
    ): Decision? {
        var decided = 0L
        var load: OverlayLoad<V>? = null
        val result =
            store.internalTopLevelAction(id) {
                if (phase.value != seen) return@internalTopLevelAction null
                if (seen == HydrationPhase.Detached) {
                    // Base, then the overlay, in this one transaction: the
                    // overlay wins over whatever base wrote or restored.
                    val opened = inbound?.let { overlay?.open(it) }
                    load = opened
                    plan.base(this)
                    opened?.apply()
                }
                val refresh = refreshes.incrementAndGet()
                decided = refresh
                stagePhase(HydrationPhase.Seeded(refresh))
                refresh
            }
        // No transaction of this thread is open here, so this reads the
        // committed phase: Seeded(decided) only if the commit applied, since
        // the number is new.
        val applied =
            when (result) {
                is TransactionResult.Success -> result.value != null
                is TransactionResult.Error -> decided != 0L && isInFlight(decided)
            }
        val settled = load?.let { checkNotNull(overlay).settle(it, applied) } ?: OverlaySettled.NONE
        return when {
            !applied -> if (result is TransactionResult.Error) throw result.exception else null
            else -> Decision(decided, (result as? TransactionResult.Error)?.exception, settled)
        }
    }

    /** Stage [next] into the store's transaction open on this thread: the phase and its projection together. */
    fun stagePhase(next: HydrationPhase) {
        store.internalStageSealed(phase, next)
        store.internalStageSealed(public, next.public)
    }

    /** The committed [phase] ([committed]): never one a transaction parked on this thread staged. */
    val committedPhase: HydrationPhase? get() = committed.value

    /**
     * Record [job] as running refresh [refresh], unless a later refresh is
     * recorded already — and cancel it at once when a detach or a dispose
     * already went past [running] before it was recorded here.
     */
    fun track(
        refresh: Long,
        job: Job,
    ) {
        running.update { current ->
            if (current != null && current.refresh > refresh) current else RunningRefresh(refresh, job)
        }
        // onCommitted and onDisposed write `committed` BEFORE they swap
        // `running`, and this update precedes the read below: either they
        // find this job there, or this read finds their phase.
        if (store.isDisposed || !isCommittedInFlight(refresh)) job.cancel()
    }

    /**
     * Refresh [refresh] ended without recording its outcome — a middleware
     * rejected its `HydrationFailure` record, or its settle threw — so its
     * `Seeded` phase stays committed with nothing running: let the next
     * [hydrate] retry it, and release [awaitSettled]'s waiters with [cause].
     * Only once the gate has released the store: a waiter resumed inline may
     * open a blocking action on it.
     */
    fun strand(
        refresh: Long,
        cause: Throwable,
    ) {
        stranded.update { maxOf(it, refresh) }
        committed.update { current -> if (current.isSeededAs(refresh)) HydrationPhase.Failed(cause) else current }
    }

    /** [Hydrator.stageInvalidate]: detach, staged into the caller's transaction. */
    fun stageInvalidate() {
        check(!store.isDisposed) { "store disposed" }
        check(store.internalStagesHere()) {
            "hydrator.stageInvalidate() on $storeName stages into an action of $storeName open on this thread, and " +
                "none is. Call it inside store.action { … } (it commits, or rolls back, with that action), or call " +
                "hydrator.invalidate(), which opens an action of its own."
        }
        // Staged even over Detached (both states are distinct: an equal value
        // fires nothing), so a transaction closed to writes refuses it.
        stagePhase(HydrationPhase.Detached)
    }

    /** Stage the move to Detached, unless the phase this transaction reads already is. */
    fun detach() {
        if (phase.value != HydrationPhase.Detached) stagePhase(HydrationPhase.Detached)
    }

    /** [Hydrator.awaitSettled]. */
    suspend fun awaitSettled(): Hydration {
        check(!store.isDisposed) { "store disposed" }
        // Only a refresh's settle moves Seeded on, and it takes this store
        // under the gate: inside an entry that holds it, it would wait forever.
        refuseInsideEntry(store, "awaitSettled()", ::awaitInsideEntryMessage)
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

    /**
     * The store was disposed: stop the refresh in flight and the overlay's
     * writer, and release [awaitSettled]'s waiters.
     */
    fun onDisposed() {
        committed.value = null
        running.getAndSet(null)?.job?.cancel()
        overlay?.onDisposed()
    }
}

/**
 * Whether refresh [refresh] is the one in flight in the phase this thread
 * reads. Only under the gate, or in a transaction it opened: elsewhere a
 * transaction parked on this thread (a `suspendAction` that staged a detach
 * and may roll it back) would show its staged phase.
 */
internal fun HydrationEngine<*, *>.isInFlight(refresh: Long): Boolean = phase.value.isSeededAs(refresh)

/**
 * Whether refresh [refresh] is in flight in the COMMITTED phase — never a
 * phase staged by a transaction parked on this thread. For the checks a
 * refresh makes outside the gate.
 */
internal fun HydrationEngine<*, *>.isCommittedInFlight(refresh: Long): Boolean = committedPhase.isSeededAs(refresh)

private fun HydrationPhase?.isSeededAs(refresh: Long): Boolean = (this as? HydrationPhase.Seeded)?.refresh == refresh

/**
 * Whether a hydrate() decides anything in this phase: seed a detached store,
 * or retry a failed refresh, or the [stranded] one.
 */
private fun HydrationPhase.decides(stranded: Long): Boolean =
    this == HydrationPhase.Detached || this is HydrationPhase.Failed || isSeededAs(stranded)

/** What a write to a hydrator's state throws: whose it is, and what to call instead. */
private fun sealedRefusal(storeName: String): String =
    "it is the hydration phase of $storeName's hydrator, which only the hydrator writes: hydrate() moves it on, " +
        "and invalidate(), stageInvalidate() inside an action, or the store's reset() moves it back to Detached. It " +
        "is not store state — no snapshot or restore captures or writes it, and reset() re-runs no initializer for " +
        "it: it only detaches it, through the hydrator. Fix: drive it through the Hydrator instead of writing it."

/** A top-level transaction's id for the adoption of what a refresh fetched. */
internal const val ADOPT_ID = "HydrationAdopt"

/** A top-level transaction's id recording that a refresh (or its adoption) failed. */
internal const val FAILURE_ID = "HydrationFailure"
