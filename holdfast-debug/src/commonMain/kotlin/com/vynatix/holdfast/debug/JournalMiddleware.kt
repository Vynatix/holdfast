package com.vynatix.holdfast.debug

import com.vynatix.holdfast.Disposable
import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.MutableState
import com.vynatix.holdfast.State
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.TransactionStatus
import com.vynatix.holdfast.platform.currentThreadId
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

/**
 * Purely observational middleware that keeps a bounded ring buffer of what
 * happened on one store: every transaction that finished (committed or rolled
 * back, with the values it staged — for a rollback, the values it *tried* to
 * write), plus every value that reached a state through a bridge's inbound
 * path, which bypasses transactions and middleware entirely.
 *
 * The console's `journal`, `autopsy`, `stats`, `mark` and `diff` commands read
 * from here. [StoreRegistry.register] installs one per store; you rarely
 * construct it yourself.
 *
 * How the three sources are stitched together (and where they can be wrong):
 *  - **Writes** are read at the `completed`/`error` hook via
 *    `Transaction.modifiedStates` + `state.value` (read-your-own-writes on the
 *    owner thread). Both hooks run BEFORE commit/rollback, so the staged value
 *    is still visible. Off the owner thread (a `suspendAction` body that hopped
 *    dispatchers) the read is illegal; the entry is recorded with
 *    [TransactionEntry.writesAvailable] = `false` instead of a wrong value.
 *  - **Before values** are the last value the journal saw *committed* for that
 *    state, tracked through a per-state observer attached to every registered
 *    state (`MutableState.observe`), never through a per-transaction snapshot.
 *  - **Inbound** values are the same observer firing while the store has no
 *    active transaction. An inbound update that lands while another thread's
 *    transaction is active on the store is attributed to that transaction —
 *    an inherent ambiguity of core's unsynchronised `applyFromBridge`.
 *  - **Outcome** is read live from `Transaction.status`, so an entry recorded
 *    at `completed` shows `RolledBack` if an outer middleware or an `atomic`
 *    peer vetoed the commit afterwards.
 *
 * Never throws from its own bookkeeping: a failing `toString()` or an illegal
 * owner-thread read is rendered as a placeholder, never propagated into the
 * transaction. Value rendering (user code) always runs outside the journal's
 * lock.
 */
@ExperimentalStoreApi
@Suppress("TooManyFunctions") // Hooks + reads over one ring; splitting would scatter the single lock they share.
class JournalMiddleware<V : Store<V>> internal constructor(
    private val renderer: ValueRenderer,
    /** Maximum entries retained; the oldest is evicted first. */
    val capacity: Int,
) : Middleware<V>() {
    private class Open(
        val mark: TimeSource.Monotonic.ValueTimeMark,
        val threadId: Long,
        val depth: Int,
    )

    private class Mark(
        val seq: Long,
        val evicted: Long,
        val seen: Map<String, String>,
    )

    private val lock = SynchronizedObject()
    private val ring = ArrayDeque<JournalEntry>()
    private var nextSeq = 1L
    private var evictedTotal = 0L
    private val lastSeen = mutableMapOf<String, String>()
    private val lastWriter = mutableMapOf<String, String>()
    private val observed = mutableMapOf<String, Disposable>()
    private var seedingState: String? = null
    private var openDepth = 0
    private var mark: Mark? = null

    @Volatile
    private var active = true

    /** Whether this journal still records; `false` after the store was unregistered. */
    val isActive: Boolean get() = active

    // ---- lifecycle (registry-driven) -------------------------------------------------

    internal fun attach(store: Store<*>) {
        // Under the transaction lock so no commit fanout can interleave with the
        // seeding callbacks and be mistaken for an inbound update.
        store.runUnderLock { observeNewStates(store) }
    }

    internal fun detach() {
        active = false
        val toDispose = synchronized(lock) { observed.values.toList().also { observed.clear() } }
        toDispose.forEach { runCatching { it.dispose() } }
        clear()
    }

    /** Drop every entry and the mark. Last-seen values are kept so `before` stays meaningful. */
    fun clear() {
        synchronized(lock) {
            ring.clear()
            mark = null
        }
    }

    // ---- middleware hooks ------------------------------------------------------------

    override fun onTransactionStarted(context: MiddlewareContext<V>) {
        if (!active) return
        runCatching { observeNewStates(context.store) }
        val depth = synchronized(lock) { openDepth++ }
        context.metadata[KEY_OPEN] = Open(TimeSource.Monotonic.markNow(), currentThreadId(), depth)
    }

    override fun onTransactionCompleted(context: MiddlewareContext<V>) {
        record(context, error = null)
    }

    override fun onTransactionError(
        context: MiddlewareContext<V>,
        error: Throwable,
    ) {
        record(context, error = "${error::class.simpleName ?: "Throwable"}: ${error.message}")
    }

    // ---- reads ----------------------------------------------------------------------

    /** Oldest → newest copy of the ring. */
    fun entries(): List<JournalEntry> = synchronized(lock) { ring.toList() }

    /** Last committed value the journal saw for each observed state. */
    fun lastSeen(): Map<String, String> = synchronized(lock) { lastSeen.toMap() }

    /** Transaction id (or `"inbound"`) that last changed each observed state. */
    fun lastWriter(): Map<String, String> = synchronized(lock) { lastWriter.toMap() }

    /** Total entries evicted from the ring since construction. */
    val evicted: Long get() = synchronized(lock) { evictedTotal }

    /** Stamp the current position; returns the sequence number of the newest entry. */
    fun mark(): Long =
        synchronized(lock) {
            val seq = nextSeq - 1
            mark = Mark(seq, evictedTotal, lastSeen.toMap())
            seq
        }

    /** States whose committed value differs from the last [mark], or `null` when no mark was taken. */
    fun diff(): JournalDiff? =
        synchronized(lock) {
            val m = mark ?: return@synchronized null
            val names = (m.seen.keys + lastSeen.keys).sorted()
            val changes =
                names.mapNotNull { name ->
                    val then = m.seen[name]
                    val now = lastSeen[name]
                    if (then == now) {
                        null
                    } else {
                        DiffChange(name, then, now ?: "<removed>", lastWriter[name] ?: "?")
                    }
                }
            JournalDiff(m.seq, nextSeq - 1, changes, evictedTotal - m.evicted)
        }

    /** Aggregates over the entries currently in the ring. */
    fun stats(): JournalStats {
        val snapshot = entries()
        var transactions = 0
        var committed = 0
        var rolledBack = 0
        var savepoints = 0
        var inbound = 0
        var total = 0L
        var slowest = 0L
        var slowestId: String? = null
        val writeCounts = mutableMapOf<String, Int>()
        for (entry in snapshot) {
            entry.writes.forEach { writeCounts[it.state] = (writeCounts[it.state] ?: 0) + 1 }
            when (entry) {
                is InboundEntry -> inbound++
                is TransactionEntry -> {
                    transactions++
                    if (entry.depth > 0) savepoints++
                    when (entry.status) {
                        TransactionStatus.Committed -> committed++
                        TransactionStatus.RolledBack, TransactionStatus.Failed -> rolledBack++
                        TransactionStatus.Active -> Unit
                    }
                    total += entry.durationMicros
                    if (slowestId == null || entry.durationMicros > slowest) {
                        slowest = entry.durationMicros
                        slowestId = entry.transactionId
                    }
                }
            }
        }
        return JournalStats(
            entries = snapshot.size,
            transactions = transactions,
            committed = committed,
            rolledBack = rolledBack,
            savepoints = savepoints,
            inbound = inbound,
            totalMicros = total,
            slowestMicros = slowest,
            slowestTransactionId = slowestId,
            writeCounts = writeCounts,
        )
    }

    // ---- internals ------------------------------------------------------------------

    private fun record(
        context: MiddlewareContext<V>,
        error: String?,
    ) {
        // Consume (remove, not read): the same context serves both hooks and
        // both can fire for one transaction (an atomic frame's error fanout
        // re-fires every hook). The second record() must bail here.
        val open = context.metadata.remove(KEY_OPEN) as? Open ?: return
        val modified = modifiedStates(context)
        val writes =
            modified?.map { (name, state) ->
                val after =
                    runCatching { state.value }
                        .fold(
                            onSuccess = { renderer.render(name, it) },
                            onFailure = { "<read failed: ${it::class.simpleName}>" },
                        )
                val before = synchronized(lock) { lastSeen[name] }
                JournalWrite(name, before, after)
            } ?: emptyList()
        val micros = open.mark.elapsedNow().toLong(DurationUnit.MICROSECONDS)
        synchronized(lock) {
            if (openDepth > 0) openDepth--
            push(
                TransactionEntry(
                    seq = nextSeq++,
                    timestamp = nowMillis(),
                    transaction = context.transaction,
                    depth = open.depth,
                    threadId = open.threadId,
                    durationMicros = micros,
                    writes = writes,
                    writesAvailable = modified != null,
                    error = error,
                ),
            )
        }
    }

    /** `(name, state)` for every state this transaction staged, or `null` when unreadable off-owner-thread. */
    private fun modifiedStates(context: MiddlewareContext<V>): List<Pair<String, State<*>>>? =
        runCatching {
            val states = context.transaction.modifiedStates
            if (states.isEmpty()) return@runCatching emptyList()
            val byState =
                context.store.properties.entries
                    .associate { (name, state) -> state to name }
            states.mapNotNull { state -> byState[state]?.let { it to state } }.sortedBy { it.first }
        }.getOrNull()

    /**
     * Attach the per-state observer to every registered state that has none yet.
     * Callers hold the store's transaction lock, so the synchronous initial
     * callback of `observe` (the seed) cannot race a commit fanout.
     */
    private fun observeNewStates(store: Store<*>) {
        val properties = store.properties
        val fresh = synchronized(lock) { properties.filterKeys { it !in observed } }
        for ((name, state) in fresh) {
            @Suppress("UNCHECKED_CAST")
            val ms = state as MutableState<Any>
            synchronized(lock) { seedingState = name }
            val disposable =
                try {
                    ms.observe { value -> onObserved(store, name, value) }
                } finally {
                    synchronized(lock) { seedingState = null }
                }
            synchronized(lock) {
                if (active) observed[name] = disposable else disposable.dispose()
            }
        }
    }

    private fun onObserved(
        store: Store<*>,
        name: String,
        value: Any,
    ) {
        if (!active) return
        // User code (toString) runs before the lock is taken.
        val rendered = renderer.render(name, value)
        val activeTxn = runCatching { store.activeTransaction }.getOrNull()
        synchronized(lock) {
            val before = lastSeen[name]
            lastSeen[name] = rendered
            when {
                // Initial callback of `observe` during attach: just seed.
                seedingState == name -> Unit
                // Commit fanout of the store's own transaction.
                activeTxn != null -> lastWriter[name] = activeTxn.id
                else -> {
                    lastWriter[name] = INBOUND
                    push(InboundEntry(nextSeq++, nowMillis(), JournalWrite(name, before, rendered)))
                }
            }
        }
    }

    /** Callers hold [lock]. */
    private fun push(entry: JournalEntry) {
        ring.addLast(entry)
        while (ring.size > capacity) {
            ring.removeFirst()
            evictedTotal++
        }
    }

    private fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

    companion object {
        /** [lastWriter] value for a change that arrived through a bridge, not a transaction. */
        const val INBOUND = "inbound"
        private const val KEY_OPEN = "JournalMiddleware.open"
    }
}
