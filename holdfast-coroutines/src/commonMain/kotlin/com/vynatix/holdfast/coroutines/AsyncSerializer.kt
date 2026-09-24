@file:OptIn(com.vynatix.holdfast.StoreInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.FanoutMarkers
import com.vynatix.holdfast.MutableState
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.Transaction
import com.vynatix.holdfast.fanOutApplied
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlin.coroutines.cancellation.CancellationException

/**
 * AsyncSerializer impl backed by a coroutine [Mutex]. Blocking `action` callers
 * spin via `tryLock` + platform yield; suspending [suspendAction] / [suspendAtomic]
 * callers use the natural `Mutex.lock(owner)` suspending wait. Shared between
 * the two suspending entry points so a suspendAtomic and a suspendAction on
 * the same store block each other.
 *
 * Every blocking acquire locks with a fresh owner token. They used to share
 * one process-wide owner, and kotlinx `Mutex.tryLock(owner)` does not return
 * `false` when that owner already holds the mutex — it THROWS
 * `IllegalStateException`. So while one thread's blocking action held the
 * serializer, a second thread's blocking action on the same store failed with
 * a raw mutex error instead of waiting its turn.
 */
internal class MutexSerializer : Store.AsyncSerializer {
    val mutex = Mutex()

    /**
     * Owner token of the blocking caller currently holding [mutex], or `null`.
     * Written only by that caller after it wins the lock and cleared by the
     * same caller before it unlocks, so it is never contended; `@Volatile`
     * publishes the value alongside the mutex handover.
     */
    @kotlin.concurrent.Volatile
    private var blockingHolder: Any? = null

    override fun blockingAcquire() {
        val token = Any()
        while (!mutex.tryLock(token)) {
            com.vynatix.holdfast.platform
                .threadYield()
        }
        blockingHolder = token
    }

    override fun tryBlockingAcquire(): Boolean {
        val token = Any()
        val acquired = mutex.tryLock(token)
        if (acquired) blockingHolder = token
        return acquired
    }

    override fun blockingRelease() {
        val token = blockingHolder ?: return
        blockingHolder = null
        runCatching { mutex.unlock(token) }
    }
}

/**
 * Lazy installation of the [Store.AsyncSerializer] hook on each store. The
 * hook is installed on first use and persists for the store's lifetime — the
 * coroutine [Mutex] inside it serializes blocking action, [suspendAction], and
 * [suspendAtomic] for any store that participates in any one of them.
 */
private val installLock = object : SynchronizedObject() {}

internal fun ensureSerializer(store: Store<*>): MutexSerializer {
    val installed = store.asyncSerializer as? MutexSerializer
    if (installed != null) return installed
    return synchronized(installLock) {
        val again = store.asyncSerializer as? MutexSerializer
        if (again != null) return@synchronized again
        val fresh = MutexSerializer()
        store.asyncSerializer = fresh
        fresh
    }
}

/**
 * Commit a transaction with the suspending-action bridge / event interpose.
 *
 * Identical to [Transaction.commit] for nested (savepoint) transactions —
 * pending writes merge into the parent's buffer. For a top-level transaction,
 * every pending write is applied to state first, then observers fan out via
 * [MutableState.fanOutToObservers] (NO bridge publish), and the bridge publish
 * is dispatched separately:
 *
 *  - If the bound bridge is a [SuspendingBridge], call its [SuspendingBridge.publishAwaited]
 *    directly — the surrounding `withContext(NonCancellable)` ensures the write
 *    completes even if the calling scope cancels.
 *  - Otherwise (sync [com.vynatix.holdfast.Bridge] or no bridge), call
 *    [com.vynatix.holdfast.Bridge.publish] fire-and-forget, matching the sync
 *    action contract.
 *
 * Events are drained AFTER bridge publishes via suspending `emit` so
 * `BufferOverflow.SUSPEND` back-pressure is honored.
 *
 * The publish queue is collected during fanout and drained after
 * [Transaction.commitDispatching] returns, so the suspending `publishAwaited`
 * never runs inside the transaction's `pendingLock` — it could otherwise
 * deadlock or re-enter the lock.
 *
 * Publish failures are isolated per state and reported through
 * [com.vynatix.holdfast.Store.uncaughtObserverHandler] (logged loudly while no
 * handler is set), matching the sync path: a bridge is external sync, so a
 * failed write cannot undo values that are already committed, nor stop the
 * remaining states from publishing.
 *
 * The transaction stays installed as the store's active one until
 * [suspendAction]/[suspendAtomic] unwinds, but from the end of the apply pass it
 * refuses further writes. Code running inside this commit — an observer, a
 * bridge publish, an event collector the emit resumes inline — that writes back
 * into the store (or emits on it) gets an `IllegalStateException`, and a
 * blocking `action`/`atomic` it opens on the store returns an `Error`, instead
 * of staging into a transaction that is never applied again — or, for the
 * blocking calls, waiting for the serializer this very commit holds. The whole
 * commit runs under [inSuspendingCommitOf], which is how the store recognises
 * that code across thread hops. (A bare `mutate`/`update` from another thread
 * is refused too while the store is held: see `Store.stagesInto`.)
 *
 * Used by [suspendAction]. [suspendAtomic] applies all its roots first and
 * then fans each out with [suspendingFanOut]. Both paths go through
 * [fanOutThenPublish], so the commit-phase ordering contract is written in one
 * place.
 */
internal suspend fun suspendingCommit(txn: Transaction) {
    // inSuspendingCommitOf adds txn to any marker an enclosing suspending
    // commit on this coroutine already carries.
    inSuspendingCommitOf(listOf(txn)) { commitThenPublish(txn) }
}

/**
 * Run [block], a suspending commit phase, with the core's [FanoutMarkers]
 * naming [roots] — added to any roots an enclosing suspending commit on this
 * coroutine already marks — on every thread it resumes on. A savepoint in
 * [roots] (a nested [suspendAtomic]'s entry for a store its enclosing frame
 * holds) marks that savepoint: once it has committed into the enclosing root,
 * a blocking call from this commit that reaches its store is recognised as
 * nested too. [suspendAtomic] marks every participant for its whole commit, so
 * a later participant's fanout also counts as nested for an earlier,
 * already-applied one, and so do its frame observers.
 */
internal suspend fun <T> inSuspendingCommitOf(
    roots: Collection<Transaction>,
    block: suspend () -> T,
): T {
    val marked = FanoutMarkers.current().orEmpty() + roots
    return withFanoutMarker(marked, block)
}

/** The body of [suspendingCommit]: apply and fan out, then publish, then drain events. */
private suspend fun commitThenPublish(txn: Transaction) {
    fanOutThenPublish { fanout, drainEvents -> txn.commitDispatching(fanout, drainEvents) }
}

/**
 * The per-store fanout of a [suspendAtomic] participant that
 * [com.vynatix.holdfast.applyFrameCommit] has already applied, with every
 * other participant:
 * observers, then the suspending bridge publishes, then the suspending event
 * drain — the [suspendingCommit] contract without the apply pass. The caller
 * runs it inside the frame's [inSuspendingCommitOf].
 */
internal suspend fun suspendingFanOut(txn: Transaction) {
    fanOutThenPublish { fanout, drainEvents -> txn.fanOutApplied(fanout, drainEvents) }
}

/**
 * Run [dispatch] — a commit's synchronous part, which calls its fanout with
 * the writes that changed a state and its event drain with the staged events
 * — then publish to bridges (awaiting a [SuspendingBridge]) and emit the
 * events suspendingly.
 */
@Suppress("UNCHECKED_CAST")
private suspend fun fanOutThenPublish(
    dispatch: (
        fanout: (List<Pair<MutableState<*>, Any>>) -> Unit,
        drainEvents: (List<Pair<MutableSharedFlow<*>, Any>>) -> Unit,
    ) -> Unit,
) {
    val publishQueue = mutableListOf<Pair<MutableState<Any>, Any>>()
    val eventsQueue = mutableListOf<Pair<MutableSharedFlow<*>, Any>>()
    dispatch(
        { committed ->
            // Step 2: observers for every state whose value actually changed.
            // Deduped `distinct` states never reach here, so they correctly skip
            // the bridge publish too.
            committed.forEach { (state, value) ->
                val ms = state as MutableState<Any>
                ms.fanOutToObservers(value)
                publishQueue += ms to value
            }
        },
        { snapshot ->
            eventsQueue.addAll(snapshot)
        },
    )
    // Step 3a: bridge publish phase. SuspendingBridge gets awaited;
    // every other Bridge falls back to fire-and-forget Bridge.publish.
    for ((ms, value) in publishQueue) {
        val br = ms.bridge ?: continue
        try {
            if (br is SuspendingBridge<*>) {
                (br as SuspendingBridge<Any>).publishAwaited(value)
            } else {
                br.publish(value)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            ms.owningStore.internalReportUncaughtFailure(e)
        }
    }
    // Step 3b: events drain via suspending emit, honoring SUSPEND back-pressure.
    for ((channel, event) in eventsQueue) {
        (channel as MutableSharedFlow<Any>).emit(event)
    }
}
