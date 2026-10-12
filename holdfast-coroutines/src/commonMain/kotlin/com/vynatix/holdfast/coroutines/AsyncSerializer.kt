@file:OptIn(com.vynatix.holdfast.StoreInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.FanoutMarkers
import com.vynatix.holdfast.FatalHold
import com.vynatix.holdfast.MutableState
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreSuspendInFlightException
import com.vynatix.holdfast.Transaction
import com.vynatix.holdfast.coroutines.platform.blockingWaitCanEnd
import com.vynatix.holdfast.fanOutApplied
import com.vynatix.holdfast.internalRethrowFatal
import com.vynatix.holdfast.internalTransactionLockFree
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

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
 *
 * ## The escapable wait (issue 003)
 *
 * A blocking acquire that finds the mutex held by a suspending entry waits a
 * progress-aware budget ([budget], 2 s by default) instead of forever: the
 * clock restarts on every observable step the holder takes (each advance of
 * `Store.suspendingProgressEpoch` — entry, a staged write, a commit phase
 * starting), so an actively progressing holder never trips the guard however
 * long it runs. When the holder goes quiet for the whole budget the waiter
 * throws [StoreSuspendInFlightException] — teaching, with the elapsed wait
 * and budget in the message — after one final `tryLock`. A holder recognized
 * as BLOCKING (another `action`/`atomic`, which never parks the thread it
 * needs) is never budgeted: blocking-vs-blocking waits stay unbounded, as
 * for serializer-free stores. The holder kind is read through
 * `Store.suspendingOwner` (set only while a suspending entry holds the mutex,
 * cleared before it releases); the suspending side's install/teardown windows
 * read as "not suspending", which merely pauses the clock — conservative in
 * the safe direction. Where the wait is provably futile outright — a
 * single-threaded host (wasmJs, [blockingWaitCanEnd]) — the pre-existing
 * refusal of issue #27 fires before any of this.
 */
internal class MutexSerializer(
    /** The store's class name, for the refusal and budget messages. */
    private val storeName: String,
    /** The serialized store: its epoch makes the budget progress-aware. */
    private val store: Store<*>,
    /** How long a blocking acquire waits on a quiet suspending holder before throwing. */
    internal val budget: Duration = SUSPENDING_WAIT_BUDGET,
) : Store.AsyncSerializer {
    val mutex = Mutex()

    /**
     * Owner token of the blocking caller currently holding [mutex], or `null`.
     * Written only by that caller after it wins the lock and cleared by the
     * same caller before it unlocks, so it is never contended; `@Volatile`
     * publishes the value alongside the mutex handover.
     */
    @kotlin.concurrent.Volatile
    private var blockingHolder: Any? = null

    /**
     * Kind of the suspending entry currently holding [mutex] —
     * "suspendAction" / "suspendAtomic" — for the budget-exceeded message.
     * Set by the suspending acquire paths right after they take the mutex and
     * cleared where they release it (or tear the entry down), so it is never
     * stale across holders.
     */
    @kotlin.concurrent.Volatile
    internal var suspendingHolderLabel: String? = null

    override fun blockingAcquire() {
        val token = Any()
        // When the current unproductive wait began: (re)started on every
        // observable step by the suspending holder, nulled while the hold
        // reads as blocking.
        var quietSince: TimeMark? = null
        var seenEpoch = 0L
        while (true) {
            if (tryLockBlocking(token)) return
            // Single-threaded (wasmJs): the holder (a suspendAction/suspendAtomic
            // body or hydration decision parked at a suspension point, or a waiter
            // the mutex was just handed to) can only run once this call returns
            // the thread, so a failed tryLock can never succeed later. Refuse
            // instead of spinning forever (threadYield() is a no-op there).
            check(blockingWaitCanEnd) { blockingWaitCannotEnd(storeName) }
            // Progress-aware budget: run the clock only while a suspending
            // entry holds the store. A blocking holder is never budgeted —
            // it cannot park the thread it needs, so its wait always ends.
            if (store.suspendingOwner != null) {
                val epoch = store.suspendingProgressEpoch
                val clock = quietSince
                if (clock == null || epoch != seenEpoch) {
                    seenEpoch = epoch
                    quietSince = TimeSource.Monotonic.markNow()
                } else if (clock.elapsedNow() >= budget) {
                    // Race the release one last time before giving up.
                    if (tryLockBlocking(token)) return
                    throw StoreSuspendInFlightException(
                        suspendingBudgetExceededMessage(
                            storeName = storeName,
                            holderLabel = suspendingHolderLabel,
                            quietFor = clock.elapsedNow(),
                            budget = budget,
                        ),
                    )
                }
            } else {
                quietSince = null
            }
            com.vynatix.holdfast.platform
                .threadYield()
        }
    }

    /** Take [mutex] for a blocking caller: record the holder, self-heal a leaked owner. */
    private fun tryLockBlocking(token: Any): Boolean {
        if (!mutex.tryLock(token)) return false
        blockingHolder = token
        // Self-heal: suspendingOwner is set only while a suspending entry
        // holds this mutex and cleared before it releases, so one observed by
        // a fresh holder of the mutex is provably leaked (a torn-down entry
        // that skipped its clear). Clear it, race-free — no suspending entry
        // can be installing while we hold — so it cannot mask later waits.
        store.suspendingOwner = null
        return true
    }

    override fun tryBlockingAcquire(): Boolean {
        val token = Any()
        val acquired = mutex.tryLock(token)
        if (acquired) {
            blockingHolder = token
            store.suspendingOwner = null
        }
        return acquired
    }

    override fun blockingRelease() {
        val token = blockingHolder ?: return
        blockingHolder = null
        // A fatal error (issue #61) the release throws propagates: the mutex
        // may still be locked, and a silent one would wedge the store.
        runCatching { mutex.unlock(token) }.internalRethrowFatal()
    }
}

/** Why [MutexSerializer.blockingAcquire] refuses to wait where [blockingWaitCanEnd] is `false`. */
private fun blockingWaitCannotEnd(storeName: String): String =
    "A blocking action, atomic(...), reset() or restore() (a store tree's reset/restore and a hydrator's " +
        "invalidate() included) found $storeName held by a suspendAction, suspendAtomic or hydration " +
        "decision that has not finished. On this single-threaded platform (wasmJs) that holder can only go " +
        "on once this call returns, so the wait would never end. From an unrelated coroutine, write through " +
        "suspendAction { … } or suspendAtomic(…), which wait by suspending, or make the blocking call once " +
        "that holder has finished. Inside that holder's own body, write with mutate/update: a nested action " +
        "or suspendAction on the same store cannot run there. From its commit (an observer, bridge publish " +
        "or event collector), launch the write once the commit has finished: " +
        "store.scope.launch { store.suspendAction { … } }."

/** Default budget a blocking acquire gives a quiet suspending holder (issue 003). */
internal val SUSPENDING_WAIT_BUDGET: Duration = 2.seconds

/**
 * Why [MutexSerializer.blockingAcquire] gives up on a suspending holder:
 * names the store and the holder kind, carries the elapsed wait and the
 * configured budget, and teaches the two ways out.
 */
private fun suspendingBudgetExceededMessage(
    storeName: String,
    holderLabel: String?,
    quietFor: Duration,
    budget: Duration,
): String {
    val holder = holderLabel ?: "suspendAction or suspendAtomic"
    return "A blocking action, atomic(...), reset() or restore() gave up waiting for $storeName after " +
        "${quietFor.inWholeMilliseconds} ms without observable progress from the $holder holding it " +
        "(budget ${budget.inWholeMilliseconds} ms; the clock restarts whenever that operation visibly moves — " +
        "it staged no write and started no commit phase for the whole window, so if it is waiting on " +
        "something only this call's thread can finish, the wait would never end). From a coroutine, write " +
        "through suspendAction { … } — or suspendAtomic(…) for several stores — which waits by suspending; " +
        "from a blocking thread, call again once the suspending operation has finished. A wait behind " +
        "another blocking action is never budgeted."
}

/**
 * Lazy installation of the [Store.AsyncSerializer] hook on each store. The
 * hook is installed on first use and persists for the store's lifetime — the
 * coroutine [Mutex] inside it serializes blocking action, [suspendAction], and
 * [suspendAtomic] for any store that participates in any one of them.
 *
 * Installing it opens a window in which a blocking action, `atomic` or derived
 * recompute that read it as not installed yet holds the store under its
 * transaction lock alone. So every caller that takes the serializer and then
 * installs a transaction without that lock waits such a holder out first
 * ([awaitLockOnlyHolders]).
 */
private val installLock = object : SynchronizedObject() {}

internal fun ensureSerializer(store: Store<*>): MutexSerializer {
    val installed = store.asyncSerializer as? MutexSerializer
    if (installed != null) return installed
    return synchronized(installLock) {
        val again = store.asyncSerializer as? MutexSerializer
        if (again != null) return@synchronized again
        val fresh =
            MutexSerializer(
                storeName = store::class.simpleName ?: "Store",
                store = store,
            )
        store.asyncSerializer = fresh
        fresh
    }
}

/**
 * Refuse a suspending entry on this store when the coroutine CALLING it is
 * the very one holding the store: a `suspendAction`/`suspendAtomic` whose
 * body is making this call would wait for the mutex that body itself holds
 * (issue #38). Until #38 the fail-fast rode kotlinx's own same-owner
 * refusal — the holder locked the mutex with the `Job` its body's coroutine
 * read, so a directly-nested entry locking with the same `Job` threw
 * `IllegalStateException("This mutex is already locked by the specified
 * owner: …")` instead of suspending forever. A nested entry now runs its
 * body under its settle-scope VIEW, a new coroutine whose `Job` is not the
 * caller's, so kotlinx can no longer see the collision — the entry kinds
 * probe it here instead: [caller] is the `Job` this call's coroutine had
 * BEFORE its settle handling (exactly the object a directly-nested entry's
 * enclosing holder locked with, at any nesting depth), and
 * [Store.suspendingOwner] is what the current suspending holder locked its
 * mutex with. They are the same object only when this call is made from that
 * holder's own body coroutine — kotlinx's exact same-owner set — so a
 * suspending entry from a coroutine the body merely launched (a different
 * `Job`) still waits for the holder, as before.
 *
 * @throws IllegalStateException with kotlinx's wording (and the way out)
 *   instead of waiting for the caller itself.
 */
internal fun Store<*>.refuseOwnSuspendingOwner(caller: Any?) {
    val holder = suspendingOwner ?: return
    if (holder !== caller) return
    val name = this::class.simpleName ?: "Store"
    throw IllegalStateException(
        "This mutex is already locked by the specified owner: $holder — this coroutine's own suspending " +
            "entry (a suspendAction or suspendAtomic body) holds $name's suspend mutex, so a nested " +
            "suspending entry on the same store would wait for itself. Stage the write with `mutate`/`update`, " +
            "or make the entry once that body has returned. A suspending entry on another store — and one from " +
            "a coroutine the body launched, which waits for the holder — is not affected.",
    )
}

/**
 * Wait until no blocking holder that read this store's serializer as not
 * installed yet still holds the store under its transaction lock alone:
 * poll [internalTransactionLockFree] politely ([backOffUntil]), never
 * holding a thread. The serializer's install-window rule for its suspending
 * side (core `SerializerInstallWindow.kt`, which proves it): a caller that
 * holds the serializer calls this before it installs a transaction, which it
 * does without the lock, and drains the store's post-commit queue after it
 * has released the serializer, on every exit. Returns after one uncontended
 * probe unless such a holder — or another brief holder of the lock — is there.
 *
 * @throws CancellationException when the caller is cancelled while it waits;
 *   it still holds the serializer then, and must release it.
 */
internal suspend fun Store<*>.awaitLockOnlyHolders() {
    backOffUntil { internalTransactionLockFree() }
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
 * remaining states from publishing. Except a fatal error (issue #61): it is
 * never reported, but thrown once the remaining states have published and
 * the events have gone out.
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
    // A fatal error (issue #61) — from one state's observers, the commit's own
    // fanout steps or a publish — is held instead of reported, and thrown once
    // every other state's observers have run, every state has published and
    // the events have gone out: the commit has applied.
    val fatal = FatalHold()
    runCatching {
        dispatch(
            { committed ->
                // Step 2: observers for every state whose value actually changed.
                // Deduped `distinct` states never reach here, so they correctly skip
                // the bridge publish too.
                committed.forEach { (state, value) ->
                    val ms = state as MutableState<Any>
                    runCatching { ms.fanOutToObservers(value) }.onFailure(fatal::holdOrThrow)
                    publishQueue += ms to value
                }
            },
            { snapshot ->
                eventsQueue.addAll(snapshot)
            },
        )
    }.onFailure(fatal::holdOrThrow)
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
            fatal.rethrowHeld(alongside = e)
            throw e
        } catch (e: Throwable) {
            if (!fatal.holdIfFatal(e)) ms.owningStore.internalReportUncaughtFailure(e)
        }
    }
    // Step 3b: events drain via suspending emit, honoring SUSPEND back-pressure.
    val drained =
        runCatching {
            for ((channel, event) in eventsQueue) {
                (channel as MutableSharedFlow<Any>).emit(event)
            }
        }
    // A held fatal error outranks whatever the drain threw, carried as suppressed.
    fatal.rethrowHeld(alongside = drained.exceptionOrNull())
    drained.getOrThrow()
}

/** Hold [error] when it is fatal (issue #61), else throw it at once. */
private fun FatalHold.holdOrThrow(error: Throwable) {
    if (!holdIfFatal(error)) throw error
}
