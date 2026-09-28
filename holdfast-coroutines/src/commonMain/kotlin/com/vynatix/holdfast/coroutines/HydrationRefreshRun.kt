@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.State
import com.vynatix.holdfast.StateTag
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.Transaction
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.internalForbidStructuralWrites
import com.vynatix.holdfast.internalQualifiedName
import com.vynatix.holdfast.internalTopLevelAction
import com.vynatix.holdfast.tags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// One refresh of a hydration (issue #20, R8): fetch, then adopt — or record
// the failure — under the gate.
//
// The refresh is launched only once the transaction that decided on it has
// committed, with CoroutineStart.ATOMIC: its body always runs, even on a
// scope cancelled before it starts, so it always reaches the settle that moves
// the phase on. The settle runs NonCancellable, and does nothing once the
// refresh is no longer the one in flight (an invalidate or a reset overtook
// it) or the store is disposed. Outside the gate it asks the COMMITTED phase:
// a suspendAction parked on this thread may have staged a detach it then
// rolls back, which a read of the phase here would see. Under the gate, and
// again in each of its transactions' bodies, it reads the phase as the store
// holds it.
//
// A hydration marked in flight is never left with nothing running unnoticed:
// when its outcome cannot be recorded — a middleware rejects the
// HydrationFailure record too, or the settle itself throws — the refresh is
// STRANDED (HydrationEngine.strand) once the gate has released the store: its
// Seeded phase stays committed, the next hydrate() retries it as from Failed,
// awaitSettled() returns Failed with what could not be recorded, and the
// failure goes to the store's uncaughtObserverHandler — a handler that throws
// there is ignored, since the refresh is already stranded. Both wait for the
// release: a waiter awaitSettled() resumes inline (Dispatchers.Unconfined,
// Main.immediate) may open a blocking action on the store, which would spin
// on the serializer the gate holds.
//
// What it fetched is adopted in one transaction (id HydrationAdopt): `adopt`
// runs as a SAVEPOINT of it (id Adopt), checked when it returns — every write
// it staged into this store, and every keyed-entry eviction, must be of a
// StateTag.Remote state — then the phase moves to Hydrated. A failing savepoint
// (adopt threw, or broke the policy) rolls back whole, and the same
// transaction moves the phase to Failed instead. A failing adopt transaction
// (a middleware rejected it), or a failing refresh, is recorded by a
// transaction of its own (id HydrationFailure).

/** An outcome the store refused to record: what the refresh failed with, and why the record was refused. */
private class Unrecorded(
    val cause: Throwable,
    val refusal: Throwable,
)

/** One launched refresh of [engine]'s hydration: number [refresh]. */
internal class HydrationRefreshRun<V : Store<V>, F>(
    private val engine: HydrationEngine<V, F>,
    private val refresh: Long,
) {
    private val store: V get() = engine.store

    /**
     * Launch this refresh on [scope] (ATOMIC: see the top of this file). When
     * launching itself fails, record the failure, then throw it.
     *
     * ATOMIC is delicate because a body that starts on a cancelled scope must
     * cope with it: this one only fetches (whose `CancellationException` is
     * the refresh's failure), then settles under `NonCancellable`.
     */
    @OptIn(DelicateCoroutinesApi::class)
    suspend fun start(scope: CoroutineScope) {
        val job =
            runCatching { scope.launch(start = CoroutineStart.ATOMIC) { run() } }
                .getOrElse { failure ->
                    withContext(NonCancellable) { settle(Result.failure(failure)) }
                    throw failure
                }
        engine.track(refresh, job)
    }

    private suspend fun run() {
        // Overtaken before it started (an invalidate, a reset): fetch nothing.
        if (store.isDisposed || !engine.isCommittedInFlight(refresh)) return
        // Everything the fetch throws, a CancellationException included (the
        // launching scope was cancelled), is the refresh's failure.
        val fetched = runCatching { engine.plan.fetch(store) }
        withContext(NonCancellable) { settle(fetched) }
    }

    /**
     * Move the phase on from what the refresh [fetched], under the gate —
     * unless the refresh is no longer in flight, or the store is disposed.
     * An outcome that could not be recorded strands the refresh, and its
     * failure is reported through the store's `uncaughtObserverHandler`, as
     * the post-commit side effect it is — both once the gate has released
     * the store (see the top of this file). A handler that throws there is
     * ignored: the refresh is already stranded, and the next hydrate()
     * retries it.
     *
     * Nothing is lost between the release and the strand: a hydrate() there
     * finds Seeded, not yet stranded, and does nothing, as if it ran first;
     * an invalidate() there commits Detached, which the strand leaves in
     * place; and after a dispose the committed phase stays `null`.
     */
    private suspend fun settle(fetched: Result<F>) {
        if (store.isDisposed || !engine.isCommittedInFlight(refresh)) return
        val unrecorded =
            runCatching {
                engine.gate.hold { if (!store.isDisposed && engine.isInFlight(refresh)) commit(fetched) else null }
            }.getOrElse { thrown -> Unrecorded(thrown, thrown) } ?: return
        engine.strand(refresh, unrecorded.cause)
        if (!store.isDisposed) runCatching { store.internalReportUncaughtFailure(unrecorded.refusal) }
    }

    /**
     * Under the gate: adopt, or record the failure. Returns the outcome the
     * store refused to record — for [settle] to strand the refresh with —
     * else `null`.
     */
    private fun commit(fetched: Result<F>): Unrecorded? {
        val failure = fetched.fold(onSuccess = { adopt(it) }, onFailure = { it })
        // An adoption whose commit applied but whose fanout then failed (an
        // observer through a rethrowing uncaughtObserverHandler, which has
        // that failure) moved the phase on: nothing is left to record. So has
        // a detach committed meanwhile by a holder the gate does not keep out.
        if (failure == null || !engine.isInFlight(refresh)) return null
        val recorded =
            store.internalTopLevelAction(FAILURE_ID) {
                if (engine.isInFlight(refresh)) engine.stagePhase(HydrationPhase.Failed(failure))
            }
        val refused = (recorded as? TransactionResult.Error)?.exception
        // A record that applied, and only its fanout failed as above, is recorded.
        return if (refused != null && engine.isInFlight(refresh)) Unrecorded(failure, refused) else null
    }

    /**
     * The adopt transaction: [fetched] adopted by a savepoint, then the phase
     * moved on in the same transaction. `null` when it committed, or found
     * the refresh overtaken; its failure otherwise (a middleware rejected
     * it, or its commit's fanout failed after it applied), for [commit] to
     * record — unless the phase moved on.
     */
    private fun adopt(fetched: F): Throwable? {
        val adoption =
            store.internalTopLevelAction(ADOPT_ID) {
                // Under the transaction lock, where a holder the gate's
                // serializer does not keep out has committed its detach.
                if (!engine.isInFlight(refresh)) return@internalTopLevelAction
                val adopted = action(Adopt(engine, fetched))
                val failure = (adopted as? TransactionResult.Error)?.exception
                engine.stagePhase(if (failure != null) HydrationPhase.Failed(failure) else HydrationPhase.Hydrated)
            }
        return (adoption as? TransactionResult.Error)?.exception
    }
}

/**
 * The adopt savepoint's body. A class rather than a lambda so its
 * transaction's id — the body's simple name — reads `Adopt`.
 */
private class Adopt<V : Store<V>, F>(
    private val engine: HydrationEngine<V, F>,
    private val fetched: F,
) : (V) -> Unit {
    override fun invoke(store: V) {
        val savepoint = checkNotNull(store.activeTransaction) { "adopt runs inside its savepoint" }
        savepoint.internalForbidStructuralWrites(structuralRefusal(engine.storeName))
        engine.plan.adopt(store, fetched)
        refuseNonRemoteWrites(savepoint)
    }

    /**
     * Throw, naming them, when [savepoint] — the adopt savepoint, with the
     * savepoints `adopt` committed into it — writes (or evicts) a state that
     * is not `StateTag.Remote`, other than the hydrator's own.
     */
    private fun refuseNonRemoteWrites(savepoint: Transaction) {
        val touched: Set<State<*>> = savepoint.modifiedStates + savepoint.stagedEvictions
        val offending =
            touched
                .filter { it !== engine.phase && it !== engine.public && StateTag.Remote !in it.tags }
                .map { it.internalQualifiedName ?: "a state of ${engine.storeName}" }
                .distinct()
        check(offending.isEmpty()) { adoptPolicyMessage(engine.storeName, offending) }
    }
}

private fun adoptPolicyMessage(
    storeName: String,
    offending: List<String>,
): String {
    val what = if (offending.size == 1) "${offending.single()}, which is" else "${offending.joinToString()}, which are"
    return "The hydrator's adopt { } on $storeName wrote $what not tagged StateTag.Remote. adopt may write only " +
        "$storeName's Remote states (state(tags = setOf(StateTag.Remote)) { … }), and evict only entries of Remote " +
        "keyed state families, so that what a fetch brings can never overwrite what the user wrote. The adoption " +
        "was rolled back whole, and the hydration failed. Fix: tag the state Remote if sync owns it; write it in " +
        "base { } or an action of your own; or keep the user's side and sync's side apart with merged(local, remote)."
}

private fun structuralRefusal(storeName: String): String =
    "the hydrator's adopt { } on $storeName is running on this thread. An adoption rolls back whole when it fails, " +
        "and removeState/clearStates drop states at once, outside its transaction, where no rollback reaches. Fix: " +
        "drop states outside adopt { }."
