package com.vynatix.holdfast.testing.concurrency

import com.vynatix.holdfast.testing.StoreEvent
import com.vynatix.holdfast.testing.StoreTestScope
import com.vynatix.holdfast.testing.internal.awaitingsRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Suspend until a [StoreEvent] from any tracked store matches [predicate], or
 * until [timeout] elapses.
 *
 * Subscribes to every tracked store's recorder timeline as a single fan-in.
 * The subscription is **atomic with respect to past events**: at the moment of
 * subscribe, the recorder copies its current event buffer into a replay list
 * and registers the new subscriber under the same lock. So an event that
 * occurred BEFORE the `awaiting` call is checked first (replay), and any event
 * that occurs AFTER is delivered via the channel — never both, never neither.
 *
 * On match, returns the matching event. On [timeout], throws an
 * [AwaitingTimeoutException] (an [AssertionError] subclass — see "Why an
 * AssertionError" below) carrying the augmented message
 * `"awaiting: no event matched within Xms (saw N events, last M: ...)"` with
 * the total event count and the most recent five events across all tracked
 * timelines for diagnosis.
 *
 * Both [timeout] and the resulting suspension participate in the test
 * scheduler's virtual time: under `storeTest`, a 200 ms timeout completes in
 * near-zero wall time when no match arrives.
 *
 * Example — wait for the next [com.vynatix.holdfast.testing.TransactionCommitted]:
 * ```
 * val ctr = track(MyStore())
 * backgroundScope.launch {
 *     delay(50.milliseconds)
 *     ctr.action { count mutate 1 }.shouldBeSuccess()
 * }
 * val event = awaiting(timeout = 200.milliseconds) { it is TransactionCommitted }
 * assertIs<TransactionCommitted>(event)
 * ```
 *
 * Cleanup: every `awaiting` call registers its subscriber channel with the
 * hosting [StoreTestScope]. If the test body returns while a coroutine is
 * suspended in `awaiting`, the scope's `tearDown` closes the channel — the
 * suspended `receive()` resumes with a [ClosedReceiveChannelException], which
 * `awaiting` converts into a plain [CancellationException] ("storeTest scope
 * tore down while awaiting") so the coroutine unwinds quietly and the
 * `try/finally` runs the unsubscribe path. So a forgotten `awaiting` never
 * leaks past the test and never fails it retroactively; only a genuine
 * [timeout] expiry produces the loud [AwaitingTimeoutException].
 *
 * **Why an AssertionError**: a timed-out `awaiting` is a failed expectation,
 * so it must fail the test even when thrown inside `launch { }` — a
 * [CancellationException] subclass there would be swallowed as benign
 * cancellation and the test could pass green. As an [AssertionError] it also
 * composes with [eventually], whose retry loop deliberately rethrows
 * cancellation but retries assertion failures — so
 * `eventually { awaiting(...) { ... } }` polls as expected. (The spec's
 * [kotlinx.coroutines.TimeoutCancellationException] was never an option:
 * kotlinx.coroutines keeps its constructor `internal` and the class `final`.)
 *
 * @param timeout total wait budget. On expiry an [AwaitingTimeoutException] is thrown.
 * @param predicate run against each event (replay first, then live deliveries).
 *   First `true` return wins; this function returns the matched event.
 * @return the first event for which [predicate] returned `true`.
 */
suspend fun StoreTestScope.awaiting(
    timeout: Duration = 1.seconds,
    predicate: (StoreEvent) -> Boolean,
): StoreEvent {
    val channel = Channel<StoreEvent>(Channel.UNLIMITED)
    val replays = mutableListOf<StoreEvent>()

    // Snapshot timelines AND subscribe atomically — per-recorder, the snapshot
    // and subscribe both run under the recorder's buffer lock, so no event can
    // land between them.
    val handles = allTrackedHandles()
    val subscribedRecorders = handles.mapNotNull { it.recorder }
    for (recorder in subscribedRecorders) {
        recorder.snapshotAndSubscribe(channel, replays)
    }
    awaitingsRegistry().add(channel)

    try {
        // Past events first — if a matching event already happened before the
        // call, return it without suspending.
        val past = replays.firstOrNull(predicate)
        if (past != null) return past

        // Live events — drain the channel, applying the predicate to each.
        // withTimeoutOrNull returns null on expiry (the loud AssertionError
        // path). Cancel-by-channel-close (scope teardown) surfaces as
        // ClosedReceiveChannelException and is converted to a quiet
        // CancellationException so a forgotten awaiting unwinds without
        // failing the test.
        val match =
            try {
                withTimeoutOrNull(timeout) {
                    while (true) {
                        val event = channel.receive()
                        if (predicate(event)) return@withTimeoutOrNull event
                    }
                    @Suppress("UNREACHABLE_CODE")
                    null
                }
            } catch (_: ClosedReceiveChannelException) {
                throw CancellationException("storeTest scope tore down while awaiting")
            }
        if (match != null) return match

        throw AwaitingTimeoutException(buildTimeoutMessage(timeout, allTrackedHandles()))
    } finally {
        for (recorder in subscribedRecorders) {
            recorder.unsubscribe(channel)
        }
        awaitingsRegistry().remove(channel)
        channel.close()
    }
}

private fun buildTimeoutMessage(
    timeout: Duration,
    handles: List<com.vynatix.holdfast.testing.StoreHandle<*>>,
): String {
    val all = handles.flatMap { it.timeline }
    val recent = all.takeLast(RECENT_TAIL_COUNT)
    return "awaiting: no event matched within ${timeout.inWholeMilliseconds}ms " +
        "(saw ${all.size} events, last ${recent.size}: $recent)"
}

private const val RECENT_TAIL_COUNT = 5

/**
 * Thrown by [awaiting] on timeout. Subclass of [AssertionError] — matching
 * [eventually]'s failure convention — so a timed-out expectation:
 *  - fails a coroutine launched in the test scope loudly instead of being
 *    swallowed as benign cancellation (which a [CancellationException]
 *    subclass would be);
 *  - is reported by test runners as a *failure* (broken expectation), not an
 *    error;
 *  - is retryable inside [eventually], whose loop rethrows cancellation but
 *    catches assertion failures.
 *
 * Carries the augmented `"awaiting: no event matched within Xms (saw N
 * events, last M: ...)"` message.
 *
 * Scope teardown does NOT throw this: when `storeTest` tears down while a
 * coroutine is suspended in [awaiting], the subscriber channel closes and
 * `awaiting` unwinds with a plain [CancellationException] instead, so a
 * forgotten background `awaiting` stays quiet.
 *
 * **Why not [kotlinx.coroutines.TimeoutCancellationException]**:
 * kotlinx.coroutines defines TCE with `internal` constructors and as `final`,
 * so external code cannot construct one with a custom message and cannot
 * subclass it — it was never a reachable option.
 */
class AwaitingTimeoutException internal constructor(
    message: String,
) : AssertionError(message)
