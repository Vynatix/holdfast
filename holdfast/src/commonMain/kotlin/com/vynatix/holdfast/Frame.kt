@file:OptIn(StoreInternalApi::class)

package com.vynatix.holdfast

import com.vynatix.holdfast.platform.currentFrameLocal
import com.vynatix.holdfast.platform.setFrameLocal

/**
 * Per-frame behavior knobs for [atomic] and `:holdfast-coroutines.suspendAtomic`.
 *
 * The default ([Strict]) is the safe one: writes to stores that are not
 * enrolled in the frame throw [UnenrolledStoreException], and an inner
 * `action { }` / `suspendAction { }` that fails aborts the whole frame. Both
 * protections can be lifted per call site — the opt-outs exist so today's
 * looser behavior stays reachable, explicitly and greppably:
 *
 * ```
 * atomic(a, b, policy = FramePolicy.AllowUnenrolled) { … }
 * atomic(a, b, policy = FramePolicy.AllowUnenrolled + FramePolicy.TolerateInnerErrors) { … }
 * ```
 */
class FramePolicy private constructor(
    /**
     * When `true`, `action`/`mutate`/`update` on a store that is not in the
     * frame's participant list runs as an ordinary independent transaction
     * (it commits at its own exit and does NOT roll back with the frame).
     * When `false` (default), such writes throw [UnenrolledStoreException].
     */
    val allowUnenrolled: Boolean,
    /**
     * When `true`, an inner `action { }` on a participant store that returns
     * [TransactionResult.Error] does NOT abort the frame — the caller owns
     * checking the result. When `false` (default), the inner error is
     * escalated: the frame rolls back every participant and returns
     * [TransactionResult.Error] carrying the inner exception.
     */
    val tolerateInnerErrors: Boolean,
) {
    /**
     * Combine two policies; each opt-out is granted if EITHER operand grants
     * it. `Strict + x == x`; the operation is commutative and idempotent.
     */
    operator fun plus(other: FramePolicy): FramePolicy =
        of(
            allowUnenrolled = allowUnenrolled || other.allowUnenrolled,
            tolerateInnerErrors = tolerateInnerErrors || other.tolerateInnerErrors,
        )

    override fun toString(): String =
        "FramePolicy(allowUnenrolled=$allowUnenrolled, " +
            "tolerateInnerErrors=$tolerateInnerErrors)"

    companion object {
        /** Enrollment enforced, inner errors escalate. The default. */
        val Strict: FramePolicy = FramePolicy(allowUnenrolled = false, tolerateInnerErrors = false)

        /** Writes to unenrolled stores run as independent transactions (pre-0.3 behavior). */
        val AllowUnenrolled: FramePolicy = FramePolicy(allowUnenrolled = true, tolerateInnerErrors = false)

        /** Inner action errors do not abort the frame (pre-0.3 behavior); caller checks results. */
        val TolerateInnerErrors: FramePolicy = FramePolicy(allowUnenrolled = false, tolerateInnerErrors = true)

        private fun of(
            allowUnenrolled: Boolean,
            tolerateInnerErrors: Boolean,
        ): FramePolicy =
            when {
                !allowUnenrolled && !tolerateInnerErrors -> Strict
                allowUnenrolled && !tolerateInnerErrors -> AllowUnenrolled
                !allowUnenrolled -> TolerateInnerErrors
                else -> FramePolicy(allowUnenrolled = true, tolerateInnerErrors = true)
            }
    }
}

/**
 * Root of the frame-contract violation hierarchy. Unlike ordinary body
 * exceptions — which an atomic frame catches and folds into
 * [TransactionResult.Error] — frame-contract violations are programming
 * errors: the frame rolls every participant back and then RETHROWS the
 * exception out of `atomic`/`suspendAtomic`, so the mistake cannot be silenced
 * by an unobserved result.
 */
open class FrameContractException(
    message: String,
) : IllegalStateException(message)

/**
 * Thrown when a store that is not enrolled in the active atomic frame is
 * written to inside the frame's body (via `action`, `mutate`, or `update`).
 * Such writes would commit independently and would NOT roll back with the
 * frame — the exact silent partial commit the frame exists to prevent.
 *
 * Fix: add the store to the `atomic(...)`/`suspendAtomic(...)` participant
 * list. Enrolling happens at frame entry only — enrolling mid-frame would
 * acquire a lock outside the sorted global order and reintroduce the deadlock
 * class the lock-ordering design prevents. To deliberately run an independent
 * side-transaction inside a frame, pass `policy = FramePolicy.AllowUnenrolled`.
 */
class UnenrolledStoreException(
    message: String,
) : FrameContractException(message)

/**
 * Thrown when a nested `atomic`/`suspendAtomic` introduces a store whose
 * [Store.lockOrderKey] sorts BELOW a key already held by the enclosing frame.
 * Acquiring it would violate the global sorted-order lock discipline and could
 * deadlock against a concurrent frame; the check converts that latent deadlock
 * into an immediate, explainable failure. Fix: enroll the store in the
 * OUTERMOST frame instead of introducing it in a nested one.
 */
class FrameLockOrderException(
    message: String,
) : FrameContractException(message)

/**
 * Thrown when blocking and suspending transaction machinery are mixed inside
 * one frame in a way that would deadlock or corrupt transaction state instead
 * of composing — e.g. calling blocking `store.action { }` on a participant of
 * a `suspendAtomic` frame (it would deadlock on the store's suspend mutex).
 * The message names the working alternative for each case.
 */
class FrameInteropException(
    message: String,
) : FrameContractException(message)

/**
 * The active frame's identity, participants, and policy — installed in a
 * thread-local slot around the frame BODY (and only the body: the marker is
 * cleared before middleware `completed`, commit fanout, and observer dispatch,
 * so post-commit reactions to a frame are never policed by it).
 *
 * `:holdfast-coroutines.suspendAtomic` keeps the slot coherent across
 * coroutine thread hops via a `ThreadContextElement` that installs [parent]'s
 * chain on resume and restores the previous value on suspend.
 */
@StoreInternalApi
class FrameMarker(
    /** The frame id, shared by every participant's root transaction (`Transaction.frameId`). */
    val frameId: String,
    /** Stores enrolled by THIS frame (nested frames chain via [parent]). */
    val participants: Set<Store<*>>,
    /** Policy governing the body currently executing (the innermost frame's). */
    val policy: FramePolicy,
    /** Whether this frame is a `suspendAtomic` (true) or a blocking `atomic` (false). */
    val suspending: Boolean,
    /** Enclosing frame, when this frame is nested inside another. */
    val parent: FrameMarker?,
) {
    /**
     * Highest [Store.lockOrderKey] held by this frame or any enclosing one.
     * Nested frames may only introduce stores sorting ABOVE this key — an O(1)
     * comparison, cheap enough to be always-on.
     */
    val maxHeldLockOrderKey: Long =
        maxOf(
            participants.maxOf { it.lockOrderKey },
            parent?.maxHeldLockOrderKey ?: Long.MIN_VALUE,
        )

    /**
     * The frame in this chain (innermost-first) that enrolls [store], or
     * `null` when no frame in the chain does.
     */
    fun enrollingFrame(store: Store<*>): FrameMarker? =
        when {
            store in participants -> this
            else -> parent?.enrollingFrame(store)
        }

    /** Whether [store] is enrolled in this frame or any enclosing one. */
    fun isEnrolled(store: Store<*>): Boolean = enrollingFrame(store) != null

    /** Human-readable participant list for teaching exception messages. */
    fun describeParticipants(): String = participants.joinToString(prefix = "(", postfix = ")") { it.frameIdentity() }
}

/**
 * Stable human-readable store identity for frame diagnostics:
 * `SimpleName#<lockOrderKey>`. The key suffix distinguishes multiple
 * instances of one store class (e.g. `accountA` vs `accountB`) and ties the
 * message directly into the lock-order narrative of the frame contract.
 */
internal fun Store<*>.frameIdentity(): String = "${this::class.simpleName ?: "Store"}#$lockOrderKey"

/**
 * Accessors for the thread-local frame-marker slot. Cross-module surface for
 * `:holdfast-coroutines` (which keeps the slot coherent across coroutine
 * dispatch); application code has no reason to touch it.
 */
@StoreInternalApi
object FrameMarkers {
    /** The marker governing the current thread, or `null` outside any frame body. */
    fun current(): FrameMarker? = currentFrameLocal() as FrameMarker?

    /**
     * Install [marker] (or clear the slot when `null`) and return the previous
     * value so callers can restore it — install/restore must always pair.
     */
    fun install(marker: FrameMarker?): FrameMarker? {
        val prior = current()
        setFrameLocal(marker)
        return prior
    }
}

/**
 * Frame-level lifecycle observer for app-level audit and telemetry that wants
 * a multi-store frame as ONE event rather than N per-store transactions.
 * Per-store middleware still fires for every participant root (correlate via
 * [Transaction.frameId]); this interface adds the frame-scoped view.
 *
 * Callbacks are invoked synchronously on the frame's thread, each wrapped so a
 * throwing observer cannot abort the frame. Nested frames fire their own
 * started/committed/rolledBack triple.
 */
@ExperimentalStoreApi
interface FrameObserver {
    /** The frame acquired every participant lock and is about to run its body. */
    fun onFrameStarted(
        frameId: String,
        participants: List<Store<*>>,
    ) {
    }

    /** Every participant's root transaction committed. */
    fun onFrameCommitted(frameId: String) {}

    /** The frame rolled back; [cause] is what aborted it. */
    fun onFrameRolledBack(
        frameId: String,
        cause: Throwable,
    ) {
    }
}

/**
 * Process-global [FrameObserver] registry. Registration order is dispatch
 * order. Test hygiene: observers registered in a test must be unregistered
 * (or [clear]ed) in teardown — the registry is process-wide.
 */
@ExperimentalStoreApi
object FrameObservers {
    private val lock = StoreLock()
    private val observers = mutableListOf<FrameObserver>()

    /** Append [observer]; it sees every subsequent frame in the process. */
    fun register(observer: FrameObserver) {
        lock.withLock { observers.add(observer) }
    }

    /** Remove [observer] by identity; unknown observers are a no-op. */
    fun unregister(observer: FrameObserver) {
        lock.withLock { observers.removeAll { it === observer } }
    }

    /** Drop every registered observer. */
    fun clear() {
        lock.withLock { observers.clear() }
    }

    /**
     * Stable snapshot for one frame's dispatch — taken once at frame entry so
     * concurrent (un)registration does not tear a frame's started/finished pair.
     */
    @StoreInternalApi
    fun snapshot(): List<FrameObserver> = lock.withLock { observers.toList() }
}

/**
 * Shared entry validation for `atomic` and `suspendAtomic`: verifies that
 * nesting a new frame with [stores] under [enclosing] is safe.
 *
 *  - **Interop**: a store already enrolled in an enclosing frame of the OTHER
 *    flavor (blocking vs suspending) cannot be re-enrolled — the two lock
 *    disciplines don't compose; throws [FrameInteropException].
 *  - **Lock order**: a store NOT already held must sort above every held key,
 *    or acquiring it could deadlock against a concurrent frame; throws
 *    [FrameLockOrderException].
 *  - **Enrollment**: a store NOT enrolled anywhere in the enclosing chain may
 *    only be introduced when the enclosing frame's policy is
 *    [FramePolicy.allowUnenrolled] — an introduced store gets a FRESH root
 *    that commits at the nested frame's exit and does NOT roll back with the
 *    enclosing frame, the same escape a bare unenrolled write would be;
 *    throws [UnenrolledStoreException].
 */
@StoreInternalApi
@Suppress("ThrowsCount") // The gate's whole job is throwing one teaching exception per violation class.
fun verifyFrameNesting(
    enclosing: FrameMarker?,
    stores: List<Store<*>>,
    suspending: Boolean,
) {
    if (enclosing == null) return
    val newFrameName = if (suspending) "suspendAtomic" else "atomic"
    for (store in stores) {
        val name = store.frameIdentity()
        val enrolling = enclosing.enrollingFrame(store)
        if (enrolling != null) {
            if (enrolling.suspending != suspending) {
                val heldBy = if (enrolling.suspending) "suspendAtomic" else "atomic"
                throw FrameInteropException(
                    "$name is enrolled in an enclosing $heldBy frame ('${enrolling.frameId}') and cannot " +
                        "be re-enrolled by a nested $newFrameName: blocking and suspending frame lock " +
                        "disciplines do not compose. Keep the whole composition on one flavor — " +
                        "use ${if (enrolling.suspending) "suspendAtomic/suspendAction" else "atomic/action"} " +
                        "throughout.",
                )
            }
        } else if (store.lockOrderKey < enclosing.maxHeldLockOrderKey) {
            throw FrameLockOrderException(
                "Nested $newFrameName introduces $name, whose lockOrderKey sorts " +
                    "BELOW a key already held by enclosing frame '${enclosing.frameId}' " +
                    "(maxHeldLockOrderKey=${enclosing.maxHeldLockOrderKey}). Acquiring it here would " +
                    "violate the global lock order and could deadlock against a concurrent frame. " +
                    "Enroll $name in the outermost frame instead.",
            )
        } else if (!enclosing.policy.allowUnenrolled) {
            throw UnenrolledStoreException(
                "Nested $newFrameName introduces $name, which is not enrolled in the enclosing frame " +
                    "'${enclosing.frameId}' ${enclosing.describeParticipants()}. Its writes would commit " +
                    "at the nested frame's exit and would NOT roll back with the enclosing frame. " +
                    "Enroll $name in the OUTERMOST frame, or pass policy = FramePolicy.AllowUnenrolled " +
                    "on the enclosing frame to deliberately run the nested frame as an independent " +
                    "(REQUIRES_NEW-style) transaction.",
            )
        }
    }
}
