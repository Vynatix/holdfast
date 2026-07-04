package com.vynatix.holdfast.testing.matcher

import com.vynatix.holdfast.testing.BridgeObserved
import com.vynatix.holdfast.testing.BridgePublished
import com.vynatix.holdfast.testing.EmissionEvent
import com.vynatix.holdfast.testing.MiddlewareCompleted
import com.vynatix.holdfast.testing.MiddlewareErrored
import com.vynatix.holdfast.testing.MiddlewareStarted
import com.vynatix.holdfast.testing.StoreEvent
import com.vynatix.holdfast.testing.TransactionCommitted
import com.vynatix.holdfast.testing.TransactionErrored
import com.vynatix.holdfast.testing.TransactionRolledBack
import com.vynatix.holdfast.testing.TransactionStarted

// ============================================================================
// Failure-message timeline formatting — shared by every combinator runner in
// [TimelineMatcherRunner] so a failed assertion always shows WHAT actually
// fired, not just which predicate missed.
// ============================================================================

/**
 * Append the formatted timeline to [message] and throw. Every combinator
 * failure goes through here so the user always sees the recorded events
 * alongside the unmatched expectation.
 */
internal fun List<StoreEvent>.failWithTimeline(message: String): Nothing {
    val detail = formatTimeline(this)
    throw AssertionError(message + "\n" + detail)
}

/**
 * One event per line, indexed in push order, with the discriminating fields
 * (transaction id, values, middleware class) inlined. An empty timeline
 * additionally prints the known capture gaps so the "0 events" case is
 * self-diagnosing.
 */
internal fun formatTimeline(events: List<StoreEvent>): String =
    buildString {
        append("Timeline (").append(events.size).append(" events):")
        if (events.isEmpty()) {
            append(" <empty>")
            append("\nAn empty timeline usually means one of:")
            append("\n  - the handle was tracked with Capture.None (nothing is recorded);")
            append("\n  - Store.action was called directly on an untracked store — the member function")
            append("\n    bypasses auto-tracking; use store.act { }, track(store), or handle.action { };")
            append("\n  - the events happened before track(store) installed the recorder.")
        } else {
            events.forEachIndexed { i, e ->
                append("\n  [").append(i).append("] ").append(describeEvent(e))
            }
        }
    }

@Suppress("CyclomaticComplexMethod")
private fun describeEvent(e: StoreEvent): String =
    when (e) {
        is TransactionStarted -> "TransactionStarted(txn '${e.transaction.id}')"
        is TransactionCommitted -> "TransactionCommitted(txn '${e.transaction.id}')"
        is TransactionRolledBack -> "TransactionRolledBack(txn '${e.transaction.id}')"
        is TransactionErrored ->
            "TransactionErrored(txn '${e.transaction.id}', cause=${e.cause::class.simpleName}: ${e.cause.message})"
        is EmissionEvent -> "EmissionEvent(${e.oldValue} -> ${e.newValue})"
        is BridgePublished -> "BridgePublished(value=${e.value})"
        is BridgeObserved -> "BridgeObserved(value=${e.value})"
        is MiddlewareStarted -> "MiddlewareStarted(${e.middleware::class.simpleName}, txn '${e.transaction.id}')"
        is MiddlewareCompleted -> "MiddlewareCompleted(${e.middleware::class.simpleName}, txn '${e.transaction.id}')"
        is MiddlewareErrored -> "MiddlewareErrored(${e.middleware::class.simpleName}, txn '${e.transaction.id}')"
    }
