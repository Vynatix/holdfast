@file:OptIn(com.vynatix.holdfast.StoreInternalApi::class, com.vynatix.holdfast.ExperimentalStoreApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.FatalHold
import com.vynatix.holdfast.FrameContractException
import com.vynatix.holdfast.FrameMarker
import com.vynatix.holdfast.FrameMarkers
import com.vynatix.holdfast.FrameMiddlewareSession
import com.vynatix.holdfast.FrameObservers
import com.vynatix.holdfast.FramePolicy
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.Transaction
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.TransactionStatus
import com.vynatix.holdfast.applyFrameCommit
import com.vynatix.holdfast.internalDrainPostCommitTasksWhenSettled
import com.vynatix.holdfast.internalRefuseInitializerWrite
import com.vynatix.holdfast.internalRethrowFatal
import com.vynatix.holdfast.platform.currentThreadId
import com.vynatix.holdfast.verifyFrameNesting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Run [body] as a cross-store atomic frame with a suspending body — the
 * suspending peer of [com.vynatix.holdfast.atomic]. Every enrolled store's
 * transaction commits or rolls back together, unless the commit itself fails
 * partway (see **Commit failure**).
 *
 * **Enrollment is enforced.** A write to a store not in the [stores] list
 * throws [com.vynatix.holdfast.UnenrolledStoreException] — such a write would
 * commit independently and would NOT roll back with the frame. Pass
 * `policy = FramePolicy.AllowUnenrolled` for a deliberate independent
 * side-transaction. The enforcement marker travels with the coroutine across
 * thread hops (a `ThreadContextElement` keeps the thread-local slot coherent);
 * it does NOT follow coroutines launched onto OTHER scopes from inside the
 * body (`GlobalScope.launch { … }` escapes the frame — those writes are
 * concurrent, not in-frame, exactly as before).
 *
 * **Inner errors escalate.** `store.suspendAction { }` on a participant runs
 * as a savepoint of that store's frame root; an inner
 * [TransactionResult.Error] aborts the whole frame unless
 * `policy = FramePolicy.TolerateInnerErrors`.
 *
 * **Interop fails fast.** Blocking `store.action { }` on a participant inside
 * the body throws [com.vynatix.holdfast.FrameInteropException] immediately
 * instead of deadlocking on the store's suspend mutex. The same refusal,
 * with the same teaching shape, covers a blocking entry on a store held by
 * its own suspending body outside any frame — inside a `suspendAction` body
 * (issue #31). Nested frames that introduce a store sorting below an
 * already-held `lockOrderKey` throw
 * [com.vynatix.holdfast.FrameLockOrderException] at entry. Frame-contract
 * violations rethrow out of `suspendAtomic` after rollback — they are never
 * folded into an ignorable `Error` result.
 *
 * Locking: [stores] are sorted by [Store.lockOrderKey]; each store's
 * [Store.AsyncSerializer] coroutine `Mutex` (the same one [suspendAction]
 * uses) is acquired in that order — deadlock-safe global ordering, mutually
 * exclusive with blocking [Store.action] and per-store [suspendAction]. A
 * blocking `action`/`atomic` from another thread waiting on a participant
 * this frame holds waits the progress-aware budget and then throws
 * [com.vynatix.holdfast.StoreSuspendInFlightException] (issue 003); from
 * inside the body or the frame's commit on that store it is refused outright
 * instead (issue #31).
 *
 * Reentrancy: nested `suspendAtomic` calls within the same coroutine reuse
 * the outer call's locks. Stores already held by the outer frame get a
 * SAVEPOINT of the outer root — the nested frame's commit merges into the
 * outer frame; the nested frame's rollback discards only its own writes.
 * Only newly-introduced stores' mutexes are acquired (kotlinx `Mutex` is not
 * owner-reentrant, so reentrancy is managed at the frame level).
 *
 * Commit fanout order (the cross-store consistency contract): per-store
 * middleware `onTransactionStarted` in lock order, then the body, then ALL
 * stores' `onTransactionCompleted` hooks (a throw rolls the whole frame
 * back — `completed` does not mean durably-committed for frames), then,
 * under `withContext(NonCancellable)`, EVERY store applies its writes inside
 * one write bracket before any fans out — so no consistent read (a snapshot,
 * a derived state's compute) ever sees one participant applied without the
 * others, and no reader sees one applied while another's publish is in
 * flight (for the participants this frame opened a root for: a store an
 * enclosing frame holds joins as a savepoint and applies with that frame) —
 * then per-store fanout in lock order: observers → bridge publish
 * ([SuspendingBridge.publishAwaited] awaited) → suspending event drain
 * honoring `BufferOverflow.SUSPEND` back-pressure. An observer (or bridge, or
 * event collector) finds every participant applied, and may not write into
 * any of them. The `derivedState`/`merged` states the frame changes a source
 * of settle once the outermost entry on this coroutine — this frame, unless
 * it is nested in another entry — has released every store (see
 * [com.vynatix.holdfast.SettleScope]). On body throw or
 * [CancellationException] from the body, roots roll back in REVERSE lock
 * order under `NonCancellable`, with per-store middleware
 * `onTransactionError` first. Every participant root shares one
 * [Transaction.frameId].
 *
 * **Cancellation.** Called from an already-cancelled coroutine, an outermost
 * `suspendAtomic` throws that [CancellationException] before taking any
 * store: no middleware hook and no [com.vynatix.holdfast.FrameObserver]
 * callback fires — on every platform. A call nested in another
 * `suspendAction`/`suspendAtomic` on this coroutine joins that entry instead
 * of checking. Once the body has returned, the commit runs under
 * `NonCancellable`, and the call returns the commit's [TransactionResult]
 * even if the caller was cancelled meanwhile; the caller sees its
 * cancellation at its next suspension point.
 *
 * **Commit failure.** The same in-memory-2PC limitation as
 * [com.vynatix.holdfast.atomic]. If the apply throws partway (only a
 * `distinct` state's `equals` can), the roots applied before it still fan out
 * and stay committed; the rest roll back, and so does every participant
 * joined as a savepoint. A root whose fanout fails — a throwing
 * [Store.uncaughtObserverHandler], or a [CancellationException] from its
 * [SuspendingBridge.publishAwaited], such as one that times out — does not
 * stop the later ones from fanning out and committing. In every such case
 * the frame returns [TransactionResult.Error] — carrying that
 * [CancellationException] when there is one, with any other failure attached
 * as suppressed — and [com.vynatix.holdfast.FrameObserver.onFrameRolledBack]
 * fires instead of `onFrameCommitted`, although the applied participants'
 * values stand. Only the participants that roll back get
 * `onTransactionError`.
 *
 * **Fatal errors** (issue #61) — a `VirtualMachineError` such as
 * `StackOverflowError` on JVM/Android, a `kotlin.OutOfMemoryError` on
 * iOS/wasmJs — are never folded into a [TransactionResult.Error], wherever
 * they strike: the body, a middleware hook, a
 * [com.vynatix.holdfast.FrameObserver] callback, the commit or its fanout.
 * The frame still finishes what keeps its participants consistent — every
 * root still open rolls back after its `onTransactionError` hooks, every
 * applied root fans out, every frame observer is told — and then
 * `suspendAtomic` throws the first fatal error, carrying any other failure as
 * suppressed. One thrown by `onFrameCommitted` is thrown once every observer
 * has been told that the frame committed.
 *
 * Middleware caveat: frame-driven hooks are the SYNC `Middleware` hooks;
 * [SuspendingMiddlewareHooks] async siblings do not fire for the frame's
 * roots yet (they still fire for in-frame `suspendAction` savepoints).
 *
 * Example:
 * ```
 * val r = suspendAtomic(accountA, accountB) {
 *     accountA { balance update { it - amount } }
 *     accountB { balance update { it + amount } }
 * }
 * when (r) {
 *     is TransactionResult.Success -> log("transfer ok")
 *     is TransactionResult.Error   -> log("transfer rolled back: ${r.exception}")
 * }
 * ```
 */
@OptIn(ExperimentalUuidApi::class)
suspend fun <R> suspendAtomic(
    vararg stores: Store<*>,
    policy: FramePolicy = FramePolicy.Strict,
    body: suspend () -> R,
): TransactionResult<R> {
    require(stores.isNotEmpty()) { "suspendAtomic requires at least one store" }
    // De-duplicate by identity and sort by global lock order key.
    val sorted = stores.toSet().sortedBy { it.lockOrderKey }
    // A state initializer or a schema migration (SchemaVersioned.migrate) may
    // read states but not write them (Store.state).
    sorted.first().internalRefuseInitializerWrite("open a suspendAtomic(...) frame")

    // Nested-frame safety (interop flavor + lock order), BEFORE any lock is
    // acquired. The enclosing marker is coherent on this thread because the
    // enclosing frame's ThreadContextElement re-installs it on every resume.
    val enclosingMarker = FrameMarkers.current()
    verifyFrameNesting(enclosingMarker, sorted, suspending = true)

    // The coroutine making this call, read BEFORE the entry's settle handling:
    // a nested entry's join wrap is a new coroutine, so kotlinx's same-owner
    // mutex refusal can no longer see that the caller IS a suspending holder's
    // body — takeParticipant's probe compares this against each participant's
    // suspendingOwner (issue #38, refuseOwnSuspendingOwner).
    val caller: Any = coroutineContext[Job] ?: SuspendAtomicFallbackOwner()

    // An entry: the derived states its participants' commits change a source
    // of settle once the outermost entry — this frame, unless it is nested in
    // another — has released everything, as do the post-commit drains it
    // owes the stores whose roots it opened.
    val result = settlingSuspended { runSuspendAtomic(sorted, enclosingMarker, policy, caller, body) }
    // A nested frame is an inner unit of the enclosing frame's body: its
    // Error escalates like an inner action's, unless the ENCLOSING policy
    // tolerates inner errors. (Contract exceptions rethrow directly.)
    if (result is TransactionResult.Error && enclosingMarker != null && !enclosingMarker.policy.tolerateInnerErrors) {
        throw result.exception
    }
    return result
}

/**
 * [suspendAtomic] from resolving its owner on: take every participant, run
 * the body, commit or roll back, release, and defer the post-commit drains it
 * owes to the settle of the entry it runs in.
 */
@OptIn(ExperimentalUuidApi::class)
private suspend fun <R> runSuspendAtomic(
    sorted: List<Store<*>>,
    enclosingMarker: FrameMarker?,
    policy: FramePolicy,
    caller: Any,
    body: suspend () -> R,
): TransactionResult<R> {
    // Resolve the suspending owner: prefer the parent frame's owner so a
    // nested suspendAtomic in the same coroutine sees the same owner key.
    // Fall back to coroutineContext[Job], then a per-call sentinel. This is
    // the coroutine the frame BODY runs in — what a directly-nested entry's
    // caller probe recognizes — not [caller]: this call's own body may run in
    // a new coroutine (its settle join wrap, issue #38).
    val parentFrame = coroutineContext[SuspendAtomicFrame.Key]
    val owner: Any = parentFrame?.owner ?: coroutineContext[Job] ?: SuspendAtomicFallbackOwner()
    val frame = parentFrame ?: SuspendAtomicFrame(owner)

    // Already-held stores from an outer frame get a savepoint of the outer
    // root; only newly-introduced stores get a fresh root and a
    // freshly-acquired mutex.
    val parentHeld: Set<Store<*>> = parentFrame?.heldVaults ?: emptySet()
    val newlyHeld = sorted.filter { it !in parentHeld }

    val id = "suspendAtomic-${Uuid.random()}"
    val ownerThreadId = currentThreadId()
    val marker =
        FrameMarker(
            frameId = id,
            participants = sorted.toSet(),
            policy = policy,
            suspending = true,
            parent = enclosingMarker,
        )

    // Stores this frame owes a post-commit drain: those whose root it opened
    // top-level, one whose mutex acquire was cancelled after kotlinx may have
    // handed it the mutex, and one whose wait-out (awaitLockOnlyHolders) was
    // cancelled while it held the mutex. Drained only once the frame has fully
    // unwound: a drain at a participant's own unwind step ran derived
    // recomputes (and their observers) while EARLIER participants still had
    // this frame's roots installed and their mutexes held, so an observer's
    // write to one of those stores was staged into a root nobody would
    // commit, or hit a finished one.
    val drainOnExit = mutableListOf<Store<*>>()
    return try {
        acquireAndRun(
            sorted = sorted,
            newlyHeldSet = newlyHeld.toSet(),
            index = 0,
            rootsAcquired = mutableListOf(),
            drainOnExit = drainOnExit,
            ownerKey = owner,
            caller = caller,
            frame = frame,
            marker = marker,
            id = id,
            ownerThreadId = ownerThreadId,
            body = body,
        )
    } finally {
        // Every root this frame installed is uninstalled and every mutex it
        // took is released by now; the drains are deferred further, to the
        // settle of the outermost entry. A recompute opens a fresh top-level
        // action, so it could not run while this frame held its store. This
        // drain also serves recomputes handed to this frame while it held
        // the store (Store.tryTopLevelAction).
        drainOnExit.forEach { it.internalDrainPostCommitTasksWhenSettled() }
    }
}

/**
 * Recursive lock acquisition mirroring sync [com.vynatix.holdfast.atomic]'s
 * `acquireAndRun`. Each step either:
 *  - acquires the next newly-held store's mutex via `mutex.lock(owner)`,
 *    waits out a blocking holder that read it as not installed yet
 *    ([takeParticipant]) and opens a fresh frame root, OR
 *  - reuses a parent frame's already-held store by opening a SAVEPOINT of the
 *    outer frame's root (no mutex acquire) — the nested frame's commit merges
 *    into the outer, its rollback discards only nested writes.
 *
 * On unwind, newly-acquired locks release in reverse order. Deferred
 * post-commit work (derived recomputes) is not drained here: each store whose
 * root this frame opened top-level, whose mutex acquire was cancelled after
 * kotlinx may have handed it the mutex, or whose wait-out was cancelled while
 * it held the mutex ([takeParticipant]), is added to [drainOnExit], and
 * [suspendAtomic] drains those once the whole frame has unwound.
 */
@OptIn(ExperimentalUuidApi::class)
@Suppress("LongParameterList", "LongMethod")
private suspend fun <R> acquireAndRun(
    sorted: List<Store<*>>,
    newlyHeldSet: Set<Store<*>>,
    index: Int,
    rootsAcquired: MutableList<RootEntry>,
    drainOnExit: MutableList<Store<*>>,
    ownerKey: Any,
    caller: Any,
    frame: SuspendAtomicFrame,
    marker: FrameMarker,
    id: String,
    ownerThreadId: Long,
    body: suspend () -> R,
): TransactionResult<R> {
    if (index == sorted.size) {
        return executeBody(rootsAcquired, frame, marker, body)
    }

    val v = sorted[index]
    val isNewlyHeld = v in newlyHeldSet

    return if (isNewlyHeld) {
        val serializer = takeParticipant(v, ownerKey, caller, drainOnExit)
        try {
            val priorActive = v.activeTransaction
            val priorOwner = v.suspendingOwner
            // A fresh top-level root makes this frame the store's holder. A store
            // that already had a transaction leaves the drain to its holder.
            if (priorActive == null) drainOnExit += v
            try {
                // Installed inside this try, so a fatal error (issue #61)
                // partway through still restores the store below.
                frame.heldVaults += v
                // Install a fresh root transaction for this store. Subsequent
                // mutate / update / suspendAction calls inside the body stage
                // into this root's pendingWrites. Installed inside the try,
                // whose finally restores what was there before: a throw
                // anywhere between the install and the recursion — the
                // progress epoch or the middleware session — leaves nothing
                // of this frame installed (issue 002 A6(i)).
                val root = Transaction.createForExternal(id, ownerThreadId, frameId = id)
                v.internalSetActiveTransaction(root)
                v.suspendingOwner = ownerKey
                // Observable progress for any blocking waiter on this store
                // (issue 003): the entry bumped, staged writes and the frame's
                // commit phase bump again.
                v.suspendingProgressEpoch++
                rootsAcquired += RootEntry(store = v, txn = root, session = v.internalFrameMiddlewareSession(root))
                acquireAndRun(
                    sorted = sorted,
                    newlyHeldSet = newlyHeldSet,
                    index = index + 1,
                    rootsAcquired = rootsAcquired,
                    drainOnExit = drainOnExit,
                    ownerKey = ownerKey,
                    caller = caller,
                    frame = frame,
                    marker = marker,
                    id = id,
                    ownerThreadId = ownerThreadId,
                    body = body,
                )
            } finally {
                // Restore prior active txn / suspending owner regardless of
                // outcome. Commit / rollback already happened in executeBody.
                v.internalSetActiveTransaction(priorActive)
                v.suspendingOwner = priorOwner
                frame.heldVaults -= v
            }
        } finally {
            // A fatal error (issue #61) the release throws propagates: the
            // mutex may still be locked, and a silent one would wedge the store.
            runCatching {
                serializer.suspendingHolderLabel = null
                serializer.mutex.unlock(ownerKey)
            }.internalRethrowFatal()
        }
    } else {
        // Held by the outer frame: no mutex acquire. Open a SAVEPOINT of the
        // outer root so this nested frame's commit merges into the outer and
        // its rollback discards only this frame's writes — never the outer's.
        val outerRoot =
            v.activeTransaction
                ?: error(
                    "Internal: suspendAtomic frame claims to hold store ${v::class.simpleName}, " +
                        "but its activeTransaction is null. Did the outer frame abort without unwinding?",
                )
        val savepoint = Transaction.createSavepointForExternal(id, ownerThreadId, outerRoot, frameId = id)
        try {
            // Installed inside this try, so a fatal error (issue #61) partway
            // through still restores the outer root below.
            v.internalSetActiveTransaction(savepoint)
            val session = v.internalFrameMiddlewareSession(savepoint)
            rootsAcquired += RootEntry(store = v, txn = savepoint, session = session)
            acquireAndRun(
                sorted = sorted,
                newlyHeldSet = newlyHeldSet,
                index = index + 1,
                rootsAcquired = rootsAcquired,
                drainOnExit = drainOnExit,
                ownerKey = ownerKey,
                caller = caller,
                frame = frame,
                marker = marker,
                id = id,
                ownerThreadId = ownerThreadId,
                body = body,
            )
        } finally {
            v.internalSetActiveTransaction(outerRoot)
        }
    }
}

/**
 * Take [v]'s serializer for this frame — `Mutex.lock(owner)`, non-reentrant by
 * the kotlinx Mutex contract; the newlyHeld filter guarantees we never
 * re-acquire a mutex we already hold via this frame's owner key — then wait
 * out a blocking holder that read it as not installed yet
 * ([awaitLockOnlyHolders]), before the caller installs a root. On
 * cancellation — or any other failure of the wait-out, a fatal error
 * included (issue #61) — releases what it took and owes [v] a drain
 * ([drainOnExit]).
 *
 * [caller] is the coroutine the whole [suspendAtomic] call was made in,
 * read before its settle handling: when it is the body coroutine of a
 * suspending entry already holding [v], taking the mutex would wait for
 * that very entry, so it is refused up front instead ([issue #38]; until it,
 * kotlinx's own same-owner refusal caught this — the entry's settle join
 * wrap is a new coroutine now, invisible to that check).
 */
private suspend fun takeParticipant(
    v: Store<*>,
    ownerKey: Any,
    caller: Any,
    drainOnExit: MutableList<Store<*>>,
): MutexSerializer {
    v.refuseOwnSuspendingOwner(caller)
    val serializer = ensureSerializer(v)
    try {
        serializer.mutex.lock(ownerKey)
    } catch (ce: CancellationException) {
        // Mutex.unlock hands the permit straight to the first waiter. If this
        // waiter is cancelled before it resumes, kotlinx's prompt-cancellation
        // handler has already released the permit again by the time lock()
        // throws — but while we held it, a recompute that found the store busy
        // may have been handed to us (Store.tryTopLevelAction), so we owe the
        // store a drain like any releasing holder. Not here, though: earlier
        // participants still have this frame's roots installed, and a drained
        // recompute's observers could write into them. Harmless when we were
        // never handed the permit: every queued task is a non-blocking attempt.
        drainOnExit += v
        throw ce
    }
    serializer.suspendingHolderLabel = "suspendAtomic"
    runCatching { v.awaitLockOnlyHolders() }.exceptionOrNull()?.let { failure ->
        // We held the serializer while we waited: release it and owe the
        // store a drain, for the same reason as above. On any failure, not
        // only a cancellation: a fatal error (issue #61) that left the
        // serializer locked would wedge the store for good. A fatal error is
        // thrown once that is done, the wait's own first.
        serializer.suspendingHolderLabel = null
        val fatal = FatalHold()
        fatal.holdIfFatal(failure)
        runCatching { serializer.mutex.unlock(ownerKey) }.onFailure { fatal.holdIfFatal(it) }
        drainOnExit += v
        fatal.rethrowHeld(alongside = failure)
        throw failure
    }
    return serializer
}

/**
 * Run the body (with the frame marker travelling across coroutine thread
 * hops), then commit (success) or roll every entry back in reverse lock order
 * (failure) under [NonCancellable]. The commit ([commitInLockOrder]) applies
 * every fresh root inside one write bracket and then merges savepoint entries
 * into their outer frame ([applyFrameCommit]), and only then fans each root
 * out in lock order (observers, then bridge publish, then event drain) via
 * [suspendingFanOut].
 */
private suspend fun <R> executeBody(
    roots: List<RootEntry>,
    frame: SuspendAtomicFrame,
    marker: FrameMarker,
    body: suspend () -> R,
): TransactionResult<R> {
    val observers = FrameObservers.snapshot()
    // Pick the LAST entry's txn for the TransactionResult's `transaction`
    // handle — a stable terminal-state reference.
    val resultTxn: Transaction = roots.last().txn
    val startedFatal = FatalHold()
    observers.forEach { observer ->
        runCatching { observer.onFrameStarted(marker.frameId, roots.map { r -> r.store }) }
            .onFailure { startedFatal.holdIfFatal(it) }
    }

    // Until the middleware `started` phase begins, the unwind fires no
    // `onTransactionError`: no middleware has seen the frame start (as atomic).
    var hooksStarted = false
    val value: R =
        try {
            // A fatal error an onFrameStarted callback threw (issue #61), once
            // every observer has been told: the frame rolls back before any
            // middleware or the body runs, with no middleware hook at all.
            startedFatal.rethrowHeld()
            // Per-store middleware `started`, in lock order — BEFORE the marker
            // installs, so middleware is never policed by enrollment enforcement.
            hooksStarted = true
            roots.forEach { it.session.fireStarted() }
            // The marker context installs the thread-local marker on every
            // resume and restores the previous value on suspend, so enrollment
            // enforcement follows the body across dispatcher hops. Enforcement
            // covers the BODY only — it is popped before commit fanout. The body
            // starts undispatched: the frame holds every participant by now.
            withFrameMarker(marker, frame) { body() }
        } catch (ce: CancellationException) {
            rollbackAll(roots, ce, observers, marker, errorHooks = hooksStarted)
            throw ce
        } catch (e: Throwable) {
            // Throws a fatal error (issue #61) once the frame has rolled back.
            rollbackAll(roots, e, observers, marker, errorHooks = hooksStarted)
            rethrowFrameContractViolation(e)
            return TransactionResult.Error(e, resultTxn)
        }

    // Body returned. Commit under NonCancellable so fanout completes even if
    // the surrounding scope cancels here.
    return withContext(NonCancellable) {
        // A fatal error an onFrameCommitted callback throws (issue #61) is
        // thrown past the unwind below, which would tell every observer that a
        // frame that committed rolled back.
        val committedFatal = FatalHold()
        val outcome: TransactionResult<R> =
            try {
                // ALL stores' `completed` hooks fire before ANY store commits, so a
                // validation middleware throwing on the last store still rolls
                // every store back.
                roots.forEach { it.session.fireCompleted() }
                // Commit-phase start is observable progress for blocking waiters
                // on every participant (issue 003).
                roots.forEach { it.store.suspendingProgressEpoch++ }
                commitInLockOrder(roots, observers, marker.frameId, committedFatal)
                TransactionResult.Success(resultTxn, value)
            } catch (e: Throwable) {
                // Commit/completed failure: unwind in reverse. Entries that already
                // committed stay committed — same in-memory-2PC limitation on
                // commit failure as sync `atomic`. A fatal error is thrown once
                // the unwind is done.
                rollbackAll(roots, e, observers, marker, activeOnly = true)
                rethrowFrameContractViolation(e)
                TransactionResult.Error(e, resultTxn)
            }
        committedFatal.rethrowHeld()
        outcome
    }
}

/**
 * Apply every entry inside one write bracket ([applyFrameCommit]), then fan
 * each applied root out in lock order — its observers, its awaited bridge
 * publishes, its suspending event drain — then notify the frame observers,
 * all of it marked as this frame's suspending commit ([inSuspendingCommitOf]).
 * Every participant stays installed, applied, with its serializer held, while
 * the others fan out, so a blocking `action`/`atomic` on any of them from an
 * observer (or from `onFrameCommitted`) must count as nested: it is refused
 * rather than left waiting for this very frame. A root whose fanout fails
 * leaves the others to fan out — each of them has applied — and the first
 * failure (an apply failure first) is thrown once they have. A
 * [CancellationException] from one root's fanout (a [SuspendingBridge] whose
 * `publishAwaited` times out, say) is no exception: the others still fan out,
 * and it is thrown after them, carrying any other failure as suppressed. A
 * fatal error (issue #61) outranks both: the first one, from the apply or a
 * fanout, is thrown once every applied root has fanned out, carrying the
 * rest. One an `onFrameCommitted` callback throws is held in
 * [committedFatal] for the caller, which throws it once it has decided the
 * frame committed; the remaining observers are still told.
 */
private suspend fun commitInLockOrder(
    roots: List<RootEntry>,
    observers: List<com.vynatix.holdfast.FrameObserver>,
    frameId: String,
    committedFatal: FatalHold,
) {
    inSuspendingCommitOf(roots.map { it.txn }) {
        val apply = applyFrameCommit(roots.map { it.txn })
        val failures = CommitFailures(apply.failure)
        for (txn in apply.applied) {
            // Under NonCancellable: a CancellationException here came from user
            // code (a bridge's publishAwaited using withTimeout, say), not from
            // this coroutine being cancelled, so every other applied root can,
            // and must, still fan out — its values are already assigned.
            runCatching { suspendingFanOut(txn) }.onFailure(failures::add)
        }
        failures.throwIfAny()
        observers.forEach { observer ->
            runCatching { observer.onFrameCommitted(frameId) }.onFailure { committedFatal.holdIfFatal(it) }
        }
    }
}

/**
 * What [commitInLockOrder]'s apply and fanouts failed with: the first fatal
 * error (issue #61), the first [CancellationException] and the first other
 * failure, each later one suppressed into the first of its kind.
 */
private class CommitFailures(
    applyFailure: Throwable?,
) {
    private val fatal = FatalHold()
    private var failure: Throwable? = applyFailure?.takeUnless(fatal::holdIfFatal)
    private var cancellation: CancellationException? = null

    fun add(fanoutFailure: Throwable) {
        if (fatal.holdIfFatal(fanoutFailure)) return
        if (fanoutFailure is CancellationException && cancellation == null) {
            cancellation = fanoutFailure
        } else {
            failure = failure?.apply { addSuppressed(fanoutFailure) } ?: fanoutFailure
        }
    }

    /** Throw the fatal error, else the cancellation, carrying the other failure, else that failure. */
    fun throwIfAny() {
        val thrown = cancellation?.also { ce -> failure?.let(ce::addSuppressed) } ?: failure
        fatal.rethrowHeld(alongside = thrown)
        if (thrown != null) throw thrown
    }
}

/**
 * Contract violations are programming errors — fail loud instead of folding
 * them into an ignorable [TransactionResult.Error].
 */
private fun rethrowFrameContractViolation(e: Throwable) {
    if (e is FrameContractException) throw e
}

/**
 * Reverse lock-order unwind of every frame entry under [NonCancellable] —
 * with [activeOnly] (a failed commit), of every entry still Active: one that
 * applied keeps its values and gets no error hook — per-store middleware
 * `onTransactionError` first, then rollback, then every observer's
 * `onFrameRolledBack` — each step isolated so one store's failure can't
 * strand another's pending writes. Without [errorHooks] (no `started` hook
 * ran yet) no middleware hook fires. A fatal error (issue #61) — [cause]
 * itself, or the first one a step throws — is not isolated: it is thrown
 * once every step has run, carrying [cause] as suppressed when it is not
 * [cause].
 */
private suspend fun rollbackAll(
    roots: List<RootEntry>,
    cause: Throwable,
    observers: List<com.vynatix.holdfast.FrameObserver>,
    marker: FrameMarker,
    activeOnly: Boolean = false,
    errorHooks: Boolean = true,
) {
    val fatal = FatalHold()
    fatal.holdIfFatal(cause)
    withContext(NonCancellable) {
        for (i in roots.indices.reversed()) {
            val entry = roots[i]
            if (activeOnly && entry.txn.status != TransactionStatus.Active) continue
            if (errorHooks) runCatching { entry.session.fireError(cause) }.onFailure { fatal.holdIfFatal(it) }
            runCatching { entry.txn.rollback() }.onFailure { fatal.holdIfFatal(it) }
        }
    }
    observers.forEach { observer ->
        runCatching { observer.onFrameRolledBack(marker.frameId, cause) }.onFailure { fatal.holdIfFatal(it) }
    }
    fatal.rethrowHeld(alongside = cause)
}

/**
 * One slot in the "roots-acquired" list: the store, the transaction opened
 * for it by THIS frame (a fresh root for newly-locked stores, a savepoint of
 * the outer frame's root for stores adopted from an enclosing frame), and the
 * store's pre-bound middleware session. Savepoint-ness is intrinsic to the
 * transaction (its parent pointer), so commit/rollback handle both uniformly.
 */
private class RootEntry(
    val store: Store<*>,
    val txn: Transaction,
    val session: FrameMiddlewareSession,
)

/**
 * Coroutine-context element installed at the outermost [suspendAtomic] call.
 * Carries the suspending owner key (typically `coroutineContext[Job]`) and
 * the set of stores currently locked by this frame. Nested suspendAtomic
 * calls in the same coroutine inspect this to skip re-acquiring already-held
 * stores' mutexes — kotlinx [kotlinx.coroutines.sync.Mutex] is not
 * owner-reentrant, so reentrancy is managed at the suspendAtomic level.
 *
 * Mutated under the suspending coroutine's serial execution: only one
 * suspendAtomic call in this coroutine progresses at a time, so the
 * `heldVaults` set needs no extra synchronization.
 */
internal class SuspendAtomicFrame(
    val owner: Any,
) : AbstractCoroutineContextElement(Key) {
    val heldVaults: MutableSet<Store<*>> = mutableSetOf()

    companion object Key : CoroutineContext.Key<SuspendAtomicFrame>
}

/** Owner sentinel for suspendAtomic calls that have no enclosing Job. */
private class SuspendAtomicFallbackOwner
