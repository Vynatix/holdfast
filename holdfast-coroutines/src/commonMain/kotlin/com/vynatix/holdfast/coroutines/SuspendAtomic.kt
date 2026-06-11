@file:OptIn(com.vynatix.holdfast.StoreInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.Store
import com.vynatix.holdfast.Transaction
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.TransactionStatus
import com.vynatix.holdfast.platform.currentThreadId
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
 * Run [body] in a way that brackets multiple vaults' transactions so they
 * commit-or-rollback together — the suspending peer of [com.vynatix.holdfast.atomic].
 *
 * Locking: vaults are sorted by [Store.lockOrderKey] before lock acquisition,
 * giving deadlock-safe global ordering across any combination of vaults. Each
 * store's [Store.AsyncSerializer] coroutine `Mutex` (the same one [suspendAction]
 * uses) is acquired in lock order. Mutually exclusive with blocking
 * [Store.action] and per-store [suspendAction] on the same store.
 *
 * Reentrancy: nested `suspendAtomic` calls within the same coroutine reuse
 * the outer call's locks. A nested call passing vaults already held by the
 * outer frame skips re-acquiring those vaults' mutexes; only newly-introduced
 * vaults are locked. Tracking is via a [CoroutineContext.Element] keyed on
 * the suspending session — kotlinx [kotlinx.coroutines.sync.Mutex] is NOT
 * owner-reentrant (re-acquiring with the same owner throws), so reentrancy is
 * managed at the suspendAtomic level.
 *
 * Body runs in caller's coroutine context. No store-scope dependency. Inside
 * the body, `mutate`/`update` on any of the [vaults] stages into that store's
 * root transaction's `pendingWrites`. `store.suspendAction { … }` becomes a
 * savepoint of the store's root transaction.
 *
 * On body return: under `withContext(NonCancellable)`, each newly-acquired
 * store's root transaction commits in lock order. Per-store sequential
 * observer / bridge / event fanout — same machinery as [suspendAction]. Cross-store
 * sequential too (NOT parallel — same as sync [com.vynatix.holdfast.atomic],
 * per design §10). [SuspendingBridge.publishAwaited] is awaited; sync
 * [com.vynatix.holdfast.Bridge.publish] is fire-and-forget. Events drain via
 * suspending `emit` honoring `BufferOverflow.SUSPEND` back-pressure.
 *
 * On body throw or [CancellationException]: under `withContext(NonCancellable)`,
 * each newly-acquired store's root transaction rolls back in REVERSE lock
 * order. Locks release on the way out. The throwable rethrows
 * (`CancellationException`) or surfaces as [TransactionResult.Error].
 *
 * Limitation (2.0): blocking `store.action { }` calls inside the body are NOT
 * supported on a store that participates in this `suspendAtomic` — they would
 * deadlock on the AsyncSerializer mutex (kotlinx Mutex is not owner-reentrant
 * even via `tryLock`). Use `mutate` (via the receiver-DSL
 * `store { state mutate value }`) or stage via [suspendAction] inside the
 * body. This may be relaxed in a future minor.
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
    vararg vaults: Store<*>,
    body: suspend () -> R,
): TransactionResult<R> {
    require(vaults.isNotEmpty()) { "suspendAtomic requires at least one store" }
    // De-duplicate by identity and sort by global lock order key.
    val sorted = vaults.toSet().sortedBy { it.lockOrderKey }

    // Resolve the suspending owner: prefer the parent frame's owner so a
    // nested suspendAtomic in the same coroutine sees the same owner key.
    // Fall back to coroutineContext[Job], then a per-call sentinel.
    val parentFrame = coroutineContext[SuspendAtomicFrame.Key]
    val owner: Any = parentFrame?.owner ?: coroutineContext[Job] ?: SuspendAtomicFallbackOwner()
    val frame = parentFrame ?: SuspendAtomicFrame(owner)

    // Already-held vaults from an outer frame have their existing root
    // adopted as a savepoint receptacle; only newly-introduced vaults get a
    // fresh root and a freshly-acquired mutex.
    val parentHeld: Set<Store<*>> = parentFrame?.heldVaults ?: emptySet()
    val newlyHeld = sorted.filter { it !in parentHeld }

    val id = "suspendAtomic-${Uuid.random()}"
    val ownerThreadId = currentThreadId()

    return acquireAndRun(
        sorted = sorted,
        newlyHeldSet = newlyHeld.toSet(),
        index = 0,
        rootsAcquired = mutableListOf(),
        ownerKey = owner,
        frame = frame,
        installFrame = parentFrame == null,
        id = id,
        ownerThreadId = ownerThreadId,
        body = body,
    )
}

/**
 * Recursive lock acquisition mirroring sync [com.vynatix.holdfast.atomic]'s
 * `acquireAndRun`. Each step either:
 *  - reuses a parent frame's already-held store (no mutex acquire, adopt
 *    outer's active transaction so mutates merge into the outer's pendingWrites),
 *    OR
 *  - acquires the next newly-held store's mutex via `mutex.lock(owner)` and
 *    opens a new root transaction.
 *
 * On unwind, the order is reverse: newly-acquired locks release in reverse,
 * and each store's transaction is committed (success path) or rolled back
 * (failure path) under `withContext(NonCancellable)` before release.
 *
 * The frame element is installed via `withContext` only at the OUTERMOST
 * suspendAtomic call (when [installFrame] is true); nested calls are already
 * inside the parent's frame.
 */
@OptIn(ExperimentalUuidApi::class)
@Suppress("LongParameterList")
private suspend fun <R> acquireAndRun(
    sorted: List<Store<*>>,
    newlyHeldSet: Set<Store<*>>,
    index: Int,
    rootsAcquired: MutableList<RootEntry>,
    ownerKey: Any,
    frame: SuspendAtomicFrame,
    installFrame: Boolean,
    id: String,
    ownerThreadId: Long,
    body: suspend () -> R,
): TransactionResult<R> {
    if (index == sorted.size) {
        return if (installFrame) {
            withContext(frame) { executeBody(rootsAcquired, body) }
        } else {
            executeBody(rootsAcquired, body)
        }
    }

    val v = sorted[index]
    val isNewlyHeld = v in newlyHeldSet

    return if (isNewlyHeld) {
        val serializer = ensureSerializer(v)
        // Mutex.lock(owner) — non-reentrant by kotlinx Mutex contract; the
        // newlyHeld filter above guarantees we never re-acquire a mutex we
        // already hold via this frame's owner key.
        serializer.mutex.lock(ownerKey)
        try {
            frame.heldVaults += v
            val priorActive = v.activeTransaction
            val priorOwner = v.suspendingOwner
            // Install a fresh root transaction for this store. Subsequent
            // mutate / update / suspendAction calls inside the body stage into
            // this root's pendingWrites.
            val root = Transaction.createForExternal(id, ownerThreadId)
            v.internalSetActiveTransaction(root)
            v.suspendingOwner = ownerKey
            rootsAcquired +=
                RootEntry(
                    store = v,
                    txn = root,
                    priorActive = priorActive,
                    priorOwner = priorOwner,
                    wasNewlyAcquired = true,
                )
            try {
                acquireAndRun(
                    sorted = sorted,
                    newlyHeldSet = newlyHeldSet,
                    index = index + 1,
                    rootsAcquired = rootsAcquired,
                    ownerKey = ownerKey,
                    frame = frame,
                    installFrame = installFrame,
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
        }
    } else {
        // Adopted from outer frame: do not acquire the mutex, do not change
        // activeTransaction. Mutates inside the body stage into the outer's
        // root via the existing activeTransaction reference (savepoint reuse).
        val adoptedRoot =
            v.activeTransaction
                ?: error(
                    "Internal: suspendAtomic frame claims to hold store ${v::class.simpleName}, " +
                        "but its activeTransaction is null. Did the outer frame abort without unwinding?",
                )
        rootsAcquired +=
            RootEntry(
                store = v,
                txn = adoptedRoot,
                priorActive = adoptedRoot,
                priorOwner = v.suspendingOwner,
                wasNewlyAcquired = false,
            )
        acquireAndRun(
            sorted = sorted,
            newlyHeldSet = newlyHeldSet,
            index = index + 1,
            rootsAcquired = rootsAcquired,
            ownerKey = ownerKey,
            frame = frame,
            installFrame = installFrame,
            id = id,
            ownerThreadId = ownerThreadId,
            body = body,
        )
    }
}

/**
 * Run the body, then commit (success) or rollback (failure) each
 * newly-acquired store's root transaction in the appropriate order under
 * [NonCancellable]. Adopted (reused) roots are NOT committed or rolled back
 * here — their lifecycle belongs to the outer frame.
 */
private suspend fun <R> executeBody(
    roots: List<RootEntry>,
    body: suspend () -> R,
): TransactionResult<R> {
    // Pick the OUTERMOST (highest lock-order index) newly-held store's root
    // for the TransactionResult's `transaction` handle — gives the user a
    // stable terminal-state reference. If everything was adopted (a trivial
    // nested case with no new vaults), fall back to the last adopted root.
    val resultTxn: Transaction =
        roots.lastOrNull { it.wasNewlyAcquired }?.txn
            ?: roots.last().txn

    val value: R =
        try {
            body()
        } catch (ce: CancellationException) {
            withContext(NonCancellable) {
                // Reverse lock-order rollback so vaults un-stage in the inverse
                // of how they were locked. Adopted entries are skipped — their
                // rollback is the outer frame's job.
                for (i in roots.indices.reversed()) {
                    val entry = roots[i]
                    if (entry.wasNewlyAcquired) {
                        runCatching { entry.txn.rollback() }
                        entry.store.internalDrainPostCommitTasks()
                    }
                }
            }
            throw ce
        } catch (e: Throwable) {
            withContext(NonCancellable) {
                for (i in roots.indices.reversed()) {
                    val entry = roots[i]
                    if (entry.wasNewlyAcquired) {
                        runCatching { entry.txn.rollback() }
                        entry.store.internalDrainPostCommitTasks()
                    }
                }
            }
            return TransactionResult.Error(e, resultTxn)
        }

    // Body returned. Commit each newly-acquired store's root in lock order
    // under NonCancellable. Per-store sequential observer / bridge / event
    // fanout via the shared suspendingCommit machinery.
    return withContext(NonCancellable) {
        try {
            for (entry in roots) {
                if (entry.wasNewlyAcquired) {
                    suspendingCommit(entry.txn)
                    entry.store.internalDrainPostCommitTasks()
                }
                // Adopted vaults: nothing to commit; their writes already
                // merged into the outer frame's pendingWrites via the shared
                // activeTransaction reference.
            }
            TransactionResult.Success(resultTxn, value)
        } catch (e: Throwable) {
            // Commit failure on one store: rollback any not-yet-committed
            // newly-acquired roots. Already-committed vaults' state changes
            // ARE visible — same partial-commit risk on commit failure as
            // sync `atomic`.
            for (i in roots.indices.reversed()) {
                val entry = roots[i]
                if (entry.wasNewlyAcquired && entry.txn.status == TransactionStatus.Active) {
                    runCatching { entry.txn.rollback() }
                    entry.store.internalDrainPostCommitTasks()
                }
            }
            TransactionResult.Error(e, resultTxn)
        }
    }
}

/**
 * One slot in the "roots-acquired" list, tracking whether this slot was
 * newly acquired by the current suspendAtomic frame (committed/rolled back
 * here) or adopted from an outer frame (lifecycle owned by the outer).
 */
private class RootEntry(
    val store: Store<*>,
    val txn: Transaction,
    @Suppress("unused") val priorActive: Transaction?,
    @Suppress("unused") val priorOwner: Any?,
    val wasNewlyAcquired: Boolean,
)

/**
 * Coroutine-context element installed at the outermost [suspendAtomic] call.
 * Carries the suspending owner key (typically `coroutineContext[Job]`) and
 * the set of vaults currently locked by this frame. Nested suspendAtomic
 * calls in the same coroutine inspect this to skip re-acquiring already-held
 * vaults' mutexes — kotlinx [kotlinx.coroutines.sync.Mutex] is not
 * owner-reentrant, so we manage reentrancy at the suspendAtomic level.
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
