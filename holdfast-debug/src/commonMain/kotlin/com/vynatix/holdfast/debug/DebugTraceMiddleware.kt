package com.vynatix.holdfast.debug

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.Transaction
import com.vynatix.holdfast.keyedEntryName
import kotlin.time.TimeSource

/**
 * Where trace lines go. Called from arbitrary threads (one line per event,
 * events from concurrent transactions interleave) — implementations must
 * synchronize their writes and must not throw: a throwing sink behaves like
 * any throwing middleware hook (it can roll the transaction back under sync
 * `action`). Keep sinks passive (append to a list under a lock, `println`,
 * a bounded channel feed).
 */
@ExperimentalStoreApi
fun interface TraceSink {
    fun write(line: String)

    companion object {
        /** One line per event to standard output, like [com.vynatix.holdfast.middleware.LoggingMiddleware]. */
        @OptIn(ExperimentalStoreApi::class)
        fun standardOutput(): TraceSink = TraceSink { line -> println(line) }
    }
}

/**
 * Middleware that traces every transaction of one store as versioned JSONL
 * events a [TraceSink] receives — one JSON object per line, ready for
 * `:holdfast-cli`'s `holdfast trace render|stats` or any line-oriented tool.
 *
 * Event kinds (`v` is the trace format version, always `1` for now):
 *  - `txn-started`: `{"v":1,"e":"txn-started","id":…,"label":…,"parent":idOrNull,
 *    "savepoint":bool,"frame":idOrNull}` — emitted before the action body.
 *  - `txn-ended`: `{"v":1,"e":"txn-ended","id":…,"label":…,"parent":idOrNull,
 *    "status":"committed"|"rolled-back","dur_ns":…,"states":[names],"err":strOrNull}` —
 *    emitted from the completed/error hook, exactly once per transaction.
 *
 * `dur_ns` is monotonic-clock time from `started` to the hook, like
 * [com.vynatix.holdfast.middleware.ProfilingMiddleware]: the action body
 * (plus inner middleware), NOT the commit fanout — observers, bridge
 * publishes and event drains happen after the chain unwinds. Register this
 * middleware LAST in `store.middlewares(…)` (last-registered is outermost)
 * so the duration brackets the whole chain.
 *
 * Secret-safety: `states` holds state NAMES only (a keyed family's entries
 * as `family[*]` — never a key), exactly like the profiler's
 * `modifiedStates`. The one place a value can reach the trace is `err`: an
 * exception YOUR code throws is recorded with the message you gave it, the
 * same documented stance as `LoggingMiddleware`. Library exceptions never
 * carry state values.
 *
 * `states` attribution reads [Transaction.modifiedStates], which is
 * owner-thread-confined: after a `suspendAction` body resumes on another
 * dispatcher thread the names degrade to an empty list rather than failing
 * the transaction. A transaction that started but never ended in the trace
 * (process death, or attribution lost to a thread hop) shows as an orphan
 * in the renderer.
 *
 * Example:
 * ```
 * store.middlewares(DebugTraceMiddleware("CounterStore", TraceSink.standardOutput()))
 * ```
 */
@OptIn(ExperimentalStoreApi::class)
@ExperimentalStoreApi
class DebugTraceMiddleware<V : Store<V>>(
    private val label: String,
    private val sink: TraceSink,
) : Middleware<V>() {
    override fun onTransactionStarted(context: Middleware.MiddlewareContext<V>) {
        context.metadata[KEY_START_MARK] = TimeSource.Monotonic.markNow()
        val transaction = context.transaction
        sink.write(
            traceLine(
                "e" to "txn-started",
                "id" to transaction.id,
                "label" to label,
                "parent" to transaction.traceParentId(),
                "savepoint" to (transaction.traceParentId() != null),
                "frame" to transaction.frameId,
            ),
        )
    }

    override fun onTransactionCompleted(context: Middleware.MiddlewareContext<V>) {
        end(context, committed = true, error = null)
    }

    override fun onTransactionError(
        context: Middleware.MiddlewareContext<V>,
        error: Throwable,
    ) {
        end(context, committed = false, error = error)
    }

    private fun end(
        context: Middleware.MiddlewareContext<V>,
        committed: Boolean,
        error: Throwable?,
    ) {
        // CONSUME the mark (remove, not read): the same context serves the
        // completed AND error hooks of one transaction — a hook that throws
        // re-enters via the error hook, and an atomic frame's error fanout
        // re-fires hooks — and the trace must show one end event per
        // transaction. The second end() bails here.
        val mark = context.metadata.remove(KEY_START_MARK) as? TimeSource.Monotonic.ValueTimeMark ?: return
        val transaction = context.transaction
        sink.write(
            traceLine(
                "e" to "txn-ended",
                "id" to transaction.id,
                "label" to label,
                "parent" to transaction.traceParentId(),
                "status" to if (committed) "committed" else "rolled-back",
                "dur_ns" to mark.elapsedNow().inWholeNanoseconds,
                "states" to modifiedStateNames(context).sorted(),
                "err" to error?.let { "${it::class.simpleName}: ${it.message}" },
            ),
        )
    }

    /** State names, keyed families as `family[*]`; degrades off the owner thread. */
    @OptIn(StoreInternalApi::class)
    private fun modifiedStateNames(context: Middleware.MiddlewareContext<V>): Set<String> =
        runCatching {
            val states = context.transaction.modifiedStates
            if (states.isEmpty()) return@runCatching emptySet()
            val namesByState =
                context.store.properties.entries
                    .associate { (name, state) -> state to name }
            states.mapNotNullTo(mutableSetOf<String>()) { state -> namesByState[state] ?: state.keyedEntryName() }
        }.getOrElse { emptySet() }

    @OptIn(StoreInternalApi::class)
    private fun Transaction.traceParentId(): String? = parent?.id

    private companion object {
        const val KEY_START_MARK = "DebugTraceMiddleware.startMark"
    }
}
