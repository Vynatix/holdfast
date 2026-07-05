@file:OptIn(com.vynatix.holdfast.StoreInternalApi::class)

package com.vynatix.holdfast.middleware

import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreLock
import com.vynatix.holdfast.TransactionStatus
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * One profiled transaction, as delivered to [ProfilingMiddleware]'s `onSample`
 * callback and (for the slowest one seen) retained in [StoreProfile.slowest].
 *
 * [duration] is monotonic-clock time from `onTransactionStarted` to
 * `onTransactionCompleted`/`onTransactionError` — the action body plus any
 * inner middleware, but NOT the commit fanout (observers, bridge publish,
 * event drain), which happens after the middleware chain unwinds.
 *
 * [status] reports which hook produced the sample, not the final fate of the
 * writes: [TransactionStatus.Committed] means the profiler's completed hook
 * fired (the commit follows unless a LATER hook — an outer middleware, or
 * another participant of the enclosing `atomic` frame — throws afterwards);
 * [TransactionStatus.RolledBack] means the error hook fired. With the profiler
 * registered last (outermost, the recommended order) the attribution is exact
 * for plain sync actions.
 *
 * [modifiedStates] holds the property names of the states this transaction
 * staged writes for. For a savepoint sample, only the savepoint's own writes;
 * a committed savepoint's writes merge into the parent and so surface again in
 * the parent's sample. Empty when name attribution is unavailable (see
 * [ProfilingMiddleware] docs).
 */
data class TransactionSample(
    val transactionId: String,
    val frameId: String?,
    val isSavepoint: Boolean,
    val status: TransactionStatus,
    val duration: Duration,
    val modifiedStates: Set<String>,
)

/**
 * Immutable aggregate snapshot returned by [ProfilingMiddleware.profile].
 * Counters cover every transaction (top-level and savepoint) finished since
 * construction or the last [ProfilingMiddleware.reset].
 *
 * [stateWriteCounts] maps state property name → number of profiled
 * transactions that staged a write for it, regardless of outcome — it measures
 * mutation activity, not committed results (check [rolledBackCount] to see how
 * much activity was discarded).
 *
 * `derived()` recomputations run in their own post-commit transactions, so a
 * store with derived states shows extra samples (one per recompute) and
 * synthetic `__derived_N` entries here for the derived backing states. That is
 * real transaction activity, not a bug.
 */
data class StoreProfile(
    val transactionCount: Long,
    val committedCount: Long,
    val rolledBackCount: Long,
    val savepointCount: Long,
    val totalDuration: Duration,
    val slowest: TransactionSample?,
    val stateWriteCounts: Map<String, Long>,
) {
    /** Duration of the slowest profiled transaction, [Duration.ZERO] when none. */
    val maxDuration: Duration
        get() = slowest?.duration ?: Duration.ZERO

    /** Mean transaction duration, [Duration.ZERO] when no transactions were profiled. */
    val averageDuration: Duration
        get() = if (transactionCount == 0L) Duration.ZERO else totalDuration / transactionCount.toDouble()
}

/**
 * Drop-in middleware that profiles every transaction on a store: per-transaction
 * duration (monotonic clock), outcome, savepoint/frame identity, and which state
 * properties were written. Aggregates are read via [profile] and cleared via
 * [reset]; pass an [onSample] callback to also stream every finished
 * transaction (e.g. into a metrics pipeline or a test fixture).
 *
 * Purely observational: it never throws from its own bookkeeping, so attaching
 * it cannot change a transaction's outcome. An [onSample] callback that throws
 * is NOT isolated, and what happens depends on the action flavor: under sync
 * `action` it propagates and rolls the transaction back (like any middleware
 * hook), while under `:holdfast-coroutines.suspendAction` the per-hook
 * `runCatching` isolation swallows it and the commit proceeds. Keep the
 * callback passive either way.
 *
 * State-name attribution reads [com.vynatix.holdfast.Transaction.modifiedStates],
 * which is owner-thread-confined. Under `:holdfast-coroutines.suspendAction` a
 * body that resumes on a different dispatcher thread makes that read illegal;
 * the sample is still recorded but with empty [TransactionSample.modifiedStates]
 * rather than failing the transaction.
 *
 * [TransactionSample.status] is hook-level attribution: `Committed` means the
 * completed hook fired BEFORE the commit applied (see [TransactionSample]),
 * so a hook that throws later — an outer middleware, or another store of an
 * `atomic` frame vetoing the frame — leaves a durable `Committed` count for a
 * transaction that actually rolled back. Each transaction is recorded at most
 * once (the completed hook consumes the start mark, so a subsequent error
 * hook for the same transaction — sync re-fire or an `atomic` frame's error
 * fanout — does not double-count).
 *
 * Register LAST in `store.middlewares(...)` to profile the full chain
 * (last-registered is outermost, so its clock brackets inner middleware and
 * sync outcome attribution is exact); registering first profiles the bare
 * action body at the cost of that outcome accuracy.
 *
 * Example:
 * ```
 * val profiler = ProfilingMiddleware<AccountStore> { sample ->
 *     if (sample.duration > 100.milliseconds) log.warn("slow txn: $sample")
 * }
 * store.middlewares(profiler)
 * // ... exercise the store ...
 * val hot = profiler.profile().stateWriteCounts.maxByOrNull { it.value }
 * ```
 */
class ProfilingMiddleware<V : Store<V>>(
    private val onSample: ((TransactionSample) -> Unit)? = null,
) : Middleware<V>() {
    private val lock = StoreLock()
    private var transactionCount = 0L
    private var committedCount = 0L
    private var rolledBackCount = 0L
    private var savepointCount = 0L
    private var totalDuration = Duration.ZERO
    private var slowest: TransactionSample? = null
    private val stateWriteCounts = mutableMapOf<String, Long>()

    override fun onTransactionStarted(context: MiddlewareContext<V>) {
        context.metadata[KEY_START_MARK] = TimeSource.Monotonic.markNow()
    }

    override fun onTransactionCompleted(context: MiddlewareContext<V>) {
        record(context, TransactionStatus.Committed)
    }

    override fun onTransactionError(
        context: MiddlewareContext<V>,
        error: Throwable,
    ) {
        record(context, TransactionStatus.RolledBack)
    }

    /** Snapshot of everything profiled since construction or the last [reset]. */
    fun profile(): StoreProfile = lock.withLock { snapshotLocked() }

    /** Build an immutable snapshot of the aggregates. Callers must hold [lock]. */
    private fun snapshotLocked(): StoreProfile =
        StoreProfile(
            transactionCount = transactionCount,
            committedCount = committedCount,
            rolledBackCount = rolledBackCount,
            savepointCount = savepointCount,
            totalDuration = totalDuration,
            slowest = slowest,
            stateWriteCounts = stateWriteCounts.toMap(),
        )

    /**
     * Atomically zero all aggregates and return the final snapshot. Nothing
     * recorded between the snapshot and the zeroing can be lost, so periodic
     * scrape-into-a-metrics-pipeline collection is lossless. Does not detach
     * the middleware; profiling continues.
     */
    fun reset(): StoreProfile =
        lock.withLock {
            val drained = snapshotLocked()
            transactionCount = 0L
            committedCount = 0L
            rolledBackCount = 0L
            savepointCount = 0L
            totalDuration = Duration.ZERO
            slowest = null
            stateWriteCounts.clear()
            drained
        }

    private fun record(
        context: MiddlewareContext<V>,
        status: TransactionStatus,
    ) {
        // CONSUME the mark (remove, not read): the same context serves both the
        // completed and error hooks, and both can fire for one transaction — a
        // sync onSample throw re-enters via the error hook, and an atomic
        // frame's error fanout re-fires every hook. The second record() must
        // bail here or every aggregate double-counts.
        val mark = context.metadata.remove(KEY_START_MARK) as? TimeSource.Monotonic.ValueTimeMark ?: return
        val transaction = context.transaction
        val sample =
            TransactionSample(
                transactionId = transaction.id,
                frameId = transaction.frameId,
                isSavepoint = transaction.parent != null,
                status = status,
                duration = mark.elapsedNow(),
                modifiedStates = modifiedStateNames(context),
            )
        lock.withLock {
            transactionCount++
            when (status) {
                TransactionStatus.Committed -> committedCount++
                else -> rolledBackCount++
            }
            if (sample.isSavepoint) savepointCount++
            totalDuration += sample.duration
            // The null check must win even at Duration.ZERO: on coarse monotonic
            // clocks every duration can be exactly ZERO, and slowest must still
            // be non-null once anything was profiled.
            val prior = slowest
            if (prior == null || sample.duration > prior.duration) slowest = sample
            sample.modifiedStates.forEach { name ->
                stateWriteCounts[name] = (stateWriteCounts[name] ?: 0L) + 1L
            }
        }
        // Outside the lock: user code must never run under an internal lock.
        onSample?.invoke(sample)
    }

    private fun modifiedStateNames(context: MiddlewareContext<V>): Set<String> {
        // modifiedStates throws off the owner thread (possible after a
        // suspendAction thread hop); a profiler must degrade, never roll back.
        return runCatching {
            val states = context.transaction.modifiedStates
            if (states.isEmpty()) return@runCatching emptySet()
            val namesByState =
                context.store.properties.entries
                    .associate { (name, state) -> state to name }
            states.mapNotNullTo(mutableSetOf<String>()) { namesByState[it] }
        }.getOrElse { emptySet() }
    }

    private companion object {
        const val KEY_START_MARK = "ProfilingMiddleware.startMark"
    }
}
