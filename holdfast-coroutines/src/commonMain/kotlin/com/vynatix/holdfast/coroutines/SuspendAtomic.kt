@file:OptIn(com.vynatix.holdfast.StoreInternalApi::class, com.vynatix.holdfast.ExperimentalStoreApi::class)

package com.vynatix.holdfast.coroutines

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
import com.vynatix.holdfast.platform.currentThreadId
import com.vynatix.holdfast.verifyFrameNesting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Run [body] as a cross-store atomic frame with a suspending body — the
 * suspending peer of [com.vynatix.holdfast.atomic]. Every enrolled store's
 * transaction commits or rolls back together.
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
 * instead of deadlocking on the store's suspend mutex. Nested frames that
 * introduce a store sorting below an already-held `lockOrderKey` throw
 * [com.vynatix.holdfast.FrameLockOrderException] at entry. Frame-contract
 * violations rethrow out of `suspendAtomic` after rollback — they are never
 * folded into an ignorable `Error` result.
 *
 * Locking: [stores] are sorted by [Store.lockOrderKey]; each store's
 * [Store.AsyncSerializer] coroutine `Mutex` (the same one [suspendAction]
 * uses) is acquired in that order — deadlock-safe global ordering, mutually
 * exclusive with blocking [Store.action] and per-store [suspendAction].
 *
 * Reentrancy: nested `suspendAtomic` calls within the same coroutine reuse
 * the outer call's locks. Stores already held by the outer frame get a
 * SAVEPOINT of the outer root — the nested frame's commit merges into the
 * outer frame; the nested frame's rollback discards only its own writes.
 * Only newly-introduced stores' mutexes are acquired (kotlinx `Mutex` is not
 * owner-reentrant, so reentrancy is managed at the frame level). Introducing
 * a store the enclosing frame does not enroll requires the ENCLOSING policy
 * to be [FramePolicy.AllowUnenrolled] — otherwise
 * [com.vynatix.holdfast.UnenrolledStoreException] fires at entry, because
 * the introduced store's fresh root commits at the nested frame's exit and
 * does NOT roll back with the enclosing frame.
 *
 * Commit fanout order (the cross-store consistency contract): per-store
 * middleware `onTransactionStarted` in lock order, then the body, then ALL
 * stores' `onTransactionCompleted` hooks (a throw rolls the whole frame
 * back — `completed` does not mean durably-committed for frames), then
 * per-store commits in lock order under `withContext(NonCancellable)`.
 * Per-store fanout is sequential: observers → bridge publish
 * ([SuspendingBridge.publishAwaited] awaited) → suspending event drain
 * honoring `BufferOverflow.SUSPEND` back-pressure. On body throw or
 * [CancellationException], roots roll back in REVERSE lock order under
 * `NonCancellable`, with per-store middleware `onTransactionError` first.
 * Every participant root shares one [Transaction.frameId].
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

    // Nested-frame safety (interop flavor + lock order), BEFORE any lock is
    // acquired. The enclosing marker is coherent on this thread because the
    // enclosing frame's ThreadContextElement re-installs it on every resume.
    val enclosingMarker = FrameMarkers.current()
    verifyFrameNesting(enclosingMarker, sorted, suspending = true)

    // Resolve the suspending owner: prefer the parent frame's owner so a
    // nested suspendAtomic in the same coroutine sees the same owner key.
    // Fall back to coroutineContext[Job], then a per-call sentinel.
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

    val result =
        acquireAndRun(
            sorted = sorted,
            newlyHeldSet = newlyHeld.toSet(),
            index = 0,
            rootsAcquired = mutableListOf(),
            ownerKey = owner,
            frame = frame,
            marker = marker,
            id = id,
            ownerThreadId = ownerThreadId,
            body = body,
        )
    // A nested frame is an inner unit of the enclosing frame's body: its
    // Error escalates like an inner action's, unless the ENCLOSING policy
    // tolerates inner errors. (Contract exceptions rethrow directly.)
    if (result is TransactionResult.Error && enclosingMarker != null && !enclosingMarker.policy.tolerateInnerErrors) {
        throw result.exception
    }
    return result
}

/**
 * Recursive lock acquisition mirroring sync [com.vynatix.holdfast.atomic]'s
 * `acquireAndRun`. Each step either:
 *  - acquires the next newly-held store's mutex via `mutex.lock(owner)` and
 *    opens a fresh frame root, OR
 *  - reuses a parent frame's already-held store by opening a SAVEPOINT of the
 *    outer frame's root (no mutex acquire) — the nested frame's commit merges
 *    into the outer, its rollback discards only nested writes.
 *
 * On unwind, newly-acquired locks release in reverse order. Deferred
 * post-commit work (derived recomputes) drains AFTER the mutex releases —
 * recompute actions are blocking and would deadlock on a mutex still held by
 * this frame.
 */
@OptIn(ExperimentalUuidApi::class)
@Suppress("LongParameterList", "LongMethod")
private suspend fun <R> acquireAndRun(
    sorted: List<Store<*>>,
    newlyHeldSet: Set<Store<*>>,
    index: Int,
    rootsAcquired: MutableList<RootEntry>,
    ownerKey: Any,
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
        val serializer = ensureSerializer(v)
        // Mutex.lock(owner) — non-reentrant by kotlinx Mutex contract; the
        // newlyHeld filter above guarantees we never re-acquire a mutex we
        // already hold via this frame's owner key.
        serializer.mutex.lock(ownerKey)
        var openedTopLevel = false
        try {
            frame.heldVaults += v
            val priorActive = v.activeTransaction
            val priorOwner = v.suspendingOwner
            openedTopLevel = priorActive == null
            // Install a fresh root transaction for this store. Subsequent
            // mutate / update / suspendAction calls inside the body stage into
            // this root's pendingWrites.
            val root = Transaction.createForExternal(id, ownerThreadId, frameId = id)
            v.internalSetActiveTransaction(root)
            v.suspendingOwner = ownerKey
            rootsAcquired += RootEntry(store = v, txn = root, session = v.internalFrameMiddlewareSession(root))
            try {
                acquireAndRun(
                    sorted = sorted,
                    newlyHeldSet = newlyHeldSet,
                    index = index + 1,
                    rootsAcquired = rootsAcquired,
                    ownerKey = ownerKey,
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
            runCatching { serializer.mutex.unlock(ownerKey) }
            // Drain deferred post-commit work (derived recomputes) only after
            // the mutex released: recomputes run blocking `action`s, which
            // would spin forever on a mutex this frame still holds.
            if (openedTopLevel) v.internalDrainPostCommitTasks()
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
        v.internalSetActiveTransaction(savepoint)
        rootsAcquired += RootEntry(store = v, txn = savepoint, session = v.internalFrameMiddlewareSession(savepoint))
        try {
            acquireAndRun(
                sorted = sorted,
                newlyHeldSet = newlyHeldSet,
                index = index + 1,
                rootsAcquired = rootsAcquired,
                ownerKey = ownerKey,
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
 * Run the body (with the frame marker travelling across coroutine thread
 * hops), then commit every entry in lock order (success) or roll every entry
 * back in reverse lock order (failure) under [NonCancellable]. Savepoint
 * entries merge into their outer frame on commit; fresh roots apply state and
 * run the per-store observer / bridge / event fanout via [suspendingCommit].
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
    observers.forEach { runCatching { it.onFrameStarted(marker.frameId, roots.map { r -> r.store }) } }

    val value: R =
        try {
            // Per-store middleware `started`, in lock order — BEFORE the marker
            // installs, so middleware is never policed by enrollment enforcement.
            roots.forEach { it.session.fireStarted() }
            // The marker context installs the thread-local marker on every
            // resume and restores the previous value on suspend, so enrollment
            // enforcement follows the body across dispatcher hops. Enforcement
            // covers the BODY only — it is popped before commit fanout.
            withContext(frame + frameMarkerContext(marker, coroutineContext[ContinuationInterceptor])) { body() }
        } catch (ce: CancellationException) {
            rollbackAll(roots, ce, observers, marker)
            throw ce
        } catch (e: Throwable) {
            rollbackAll(roots, e, observers, marker)
            rethrowFrameContractViolation(e)
            return TransactionResult.Error(e, resultTxn)
        }

    // Body returned. Commit under NonCancellable so fanout completes even if
    // the surrounding scope cancels here.
    return withContext(NonCancellable) {
        try {
            // ALL stores' `completed` hooks fire before ANY store commits, so a
            // validation middleware throwing on the last store still rolls
            // every store back.
            roots.forEach { it.session.fireCompleted() }
            for (entry in roots) {
                suspendingCommit(entry.txn)
            }
            observers.forEach { runCatching { it.onFrameCommitted(marker.frameId) } }
            TransactionResult.Success(resultTxn, value)
        } catch (e: Throwable) {
            // Commit/completed failure: unwind in reverse. Entries that already
            // committed stay committed — same in-memory-2PC limitation on
            // commit failure as sync `atomic`.
            for (i in roots.indices.reversed()) {
                val entry = roots[i]
                if (entry.txn.status == TransactionStatus.Active) {
                    runCatching { entry.session.fireError(e) }
                    runCatching { entry.txn.rollback() }
                }
            }
            observers.forEach { runCatching { it.onFrameRolledBack(marker.frameId, e) } }
            rethrowFrameContractViolation(e)
            TransactionResult.Error(e, resultTxn)
        }
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
 * Reverse lock-order unwind of every frame entry under [NonCancellable]:
 * per-store middleware `onTransactionError` first, then rollback — each step
 * isolated so one store's failure can't strand another's pending writes.
 */
private suspend fun rollbackAll(
    roots: List<RootEntry>,
    cause: Throwable,
    observers: List<com.vynatix.holdfast.FrameObserver>,
    marker: FrameMarker,
) {
    withContext(NonCancellable) {
        for (i in roots.indices.reversed()) {
            val entry = roots[i]
            runCatching { entry.session.fireError(cause) }
            runCatching { entry.txn.rollback() }
        }
    }
    observers.forEach { runCatching { it.onFrameRolledBack(marker.frameId, cause) } }
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
