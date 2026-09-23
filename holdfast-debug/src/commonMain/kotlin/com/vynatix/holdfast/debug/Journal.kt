package com.vynatix.holdfast.debug

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Transaction
import com.vynatix.holdfast.TransactionStatus

/**
 * One state written by a journaled transaction (or by a bridge inbound update).
 *
 * [before] is the last value the journal saw committed for [state] — `null`
 * when the state was never observed before (registered lazily inside the
 * transaction). [after] is the value the transaction staged: for a committed
 * transaction the value that landed; for a rolled-back one the value that was
 * discarded (the "autopsy").
 *
 * Both are rendered strings (see [ValueRenderer]), never live references.
 */
@ExperimentalStoreApi
data class JournalWrite(
    val state: String,
    val before: String?,
    val after: String,
)

/** A record in a store's [JournalMiddleware] ring buffer. */
@ExperimentalStoreApi
sealed interface JournalEntry {
    /** Monotonic per-journal sequence number; `mark`/`diff` compare against it. */
    val seq: Long

    /** Epoch milliseconds at which the entry was recorded. */
    val timestamp: Long

    /** States touched by this entry. */
    val writes: List<JournalWrite>
}

/**
 * A transaction the middleware saw finish (its `completed` or `error` hook).
 *
 * [status] is read live from the [Transaction], so an entry recorded at the
 * `completed` hook reports `RolledBack` if an outer middleware or another
 * participant of an `atomic` frame vetoed the commit afterwards, and `Active`
 * while the commit is still applying.
 *
 * [writesAvailable] is `false` when `Transaction.modifiedStates` could not be
 * read — it is owner-thread-confined, and a `suspendAction` body that resumed
 * on another dispatcher thread makes the read illegal. The entry is still
 * recorded, with an empty [writes] list, rather than lying about what changed.
 */
@ExperimentalStoreApi
data class TransactionEntry(
    override val seq: Long,
    override val timestamp: Long,
    val transaction: Transaction,
    /** 0 for a top-level action, 1 for a savepoint nested in it, and so on. */
    val depth: Int,
    val threadId: Long,
    val durationMicros: Long,
    override val writes: List<JournalWrite>,
    val writesAvailable: Boolean,
    /** `Class: message` of the throwable that ended the body, or `null` on success. */
    val error: String?,
) : JournalEntry {
    val transactionId: String get() = transaction.id
    val frameId: String? get() = transaction.frameId
    val status: TransactionStatus get() = transaction.status

    /** Whether the body threw (as opposed to being vetoed after completing). */
    val bodyThrew: Boolean get() = error != null
}

/**
 * A value that reached a state outside any transaction on this store — a
 * bridge's inbound `observe` callback or [com.vynatix.holdfast.Store.observeFrom].
 * Those paths bypass transactions and middleware entirely, so the journal
 * catches them through a per-state observer instead.
 */
@ExperimentalStoreApi
data class InboundEntry(
    override val seq: Long,
    override val timestamp: Long,
    val write: JournalWrite,
) : JournalEntry {
    override val writes: List<JournalWrite> get() = listOf(write)
}

/** Result of `diff`: what changed since the last `mark`. */
@ExperimentalStoreApi
data class JournalDiff(
    val markSeq: Long,
    val currentSeq: Long,
    /** state name → (value at mark, value now, id of the transaction that last wrote it). */
    val changes: List<DiffChange>,
    /** Entries that were evicted from the ring between the mark and now. */
    val evicted: Long,
)

@ExperimentalStoreApi
data class DiffChange(
    val state: String,
    val atMark: String?,
    val now: String,
    val lastWriter: String,
)

/** Aggregates derived from the current ring contents (`stats`). */
@ExperimentalStoreApi
data class JournalStats(
    val entries: Int,
    val transactions: Int,
    val committed: Int,
    val rolledBack: Int,
    val savepoints: Int,
    val inbound: Int,
    val totalMicros: Long,
    val slowestMicros: Long,
    val slowestTransactionId: String?,
    val writeCounts: Map<String, Int>,
)
