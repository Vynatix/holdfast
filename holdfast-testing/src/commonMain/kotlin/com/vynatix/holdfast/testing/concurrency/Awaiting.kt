package com.vynatix.holdfast.testing.concurrency

import com.vynatix.holdfast.testing.HoldfastEvent
import com.vynatix.holdfast.testing.HoldfastTestScope
import com.vynatix.holdfast.testing.internal.awaitingsRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Suspend until a [HoldfastEvent] from any tracked vault matches [predicate], or
 * until [timeout] elapses.
 *
 * Subscribes to every tracked vault's recorder timeline as a single fan-in.
 * The subscription is **atomic with respect to past events**: at the moment of
 * subscribe, the recorder copies its current event buffer into a replay list
 * and registers the new subscriber under the same lock. So an event that
 * occurred BEFORE the `awaiting` call is checked first (replay), and any event
 * that occurs AFTER is delivered via the channel — never both, never neither.
 *
 * On match, returns the matching event. On [timeout], throws an
 * [AwaitingTimeoutException] (a [CancellationException] subclass — see the
 * "Why a custom exception" note below) carrying the augmented message
 * `"awaiting: no event matched within Xms (saw N events: ...)"` listing the
 * most recent five events across all tracked timelines for diagnosis.
 *
 * Both [timeout] and the resulting suspension participate in the test
 * scheduler's virtual time: under `holdfastTest`, a 200 ms timeout completes in
 * near-zero wall time when no match arrives.
 *
 * Example — wait for the next [com.vynatix.holdfast.testing.TransactionCommitted]:
 * ```
 * val ctr = track(MyVault())
 * backgroundScope.launch {
 *     delay(50.milliseconds)
 *     ctr.action { count mutate 1 }.shouldBeSuccess()
 * }
 * val event = awaiting(timeout = 200.milliseconds) { it is TransactionCommitted }
 * assertIs<TransactionCommitted>(event)
 * ```
 *
 * Cleanup: every `awaiting` call registers its subscriber channel with the
 * hosting [HoldfastTestScope]. If the test body returns while a coroutine is
 * suspended in `awaiting`, the scope's `tearDown` closes the channel — the
 * suspended `receive()` resumes with a [ClosedReceiveChannelException] and
 * the awaiting body's `try/finally` runs the unsubscribe path. So a forgotten
 * `awaiting` never leaks past the test.
 *
 * **Why a custom exception**: the spec calls for [TimeoutCancellationException]
 * with an augmented message, but kotlinx.coroutines defines TCE with an
 * `internal` constructor — external code cannot construct one with a custom
 * message and the class is `final` (cannot be subclassed). [AwaitingTimeoutException]
 * sits at the closest reachable point: a [CancellationException] subclass so
 * structured concurrency treats it as a normal cancellation, with the
 * augmented message available on `err.message`. Tests that want
 * a typed assertion should use `assertFailsWith<AwaitingTimeoutException>` or
 * the parent `assertFailsWith<CancellationException>`.
 *
 * @param timeout total wait budget. On expiry an [AwaitingTimeoutException] is thrown.
 * @param predicate run against each event (replay first, then live deliveries).
 *   First `true` return wins; this function returns the matched event.
 * @return the first event for which [predicate] returned `true`.
 */
suspend fun HoldfastTestScope.awaiting(timeout: Duration = 1.seconds, predicate: (HoldfastEvent) -> Boolean): HoldfastEvent {
    val channel = Channel<HoldfastEvent>(Channel.UNLIMITED)
    val replays = mutableListOf<HoldfastEvent>()

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
        // withTimeoutOrNull returns null on expiry; on cancel-by-channel-close
        // (scope teardown) the receive throws ClosedReceiveChannelException
        // which is caught and surfaced as the same timeout error so the
        // caller sees a uniform failure mode.
        val match = withTimeoutOrNull(timeout) {
            try {
                while (true) {
                    val event = channel.receive()
                    if (predicate(event)) return@withTimeoutOrNull event
                }
                @Suppress("UNREACHABLE_CODE")
                null
            } catch (_: ClosedReceiveChannelException) {
                null
            }
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

private fun buildTimeoutMessage(timeout: Duration, handles: List<com.vynatix.holdfast.testing.HoldfastHandle<*>>): String {
    val recent = handles.flatMap { it.timeline }.takeLast(RECENT_TAIL_COUNT)
    return "awaiting: no event matched within ${timeout.inWholeMilliseconds}ms " +
        "(saw ${recent.size} events: $recent)"
}

private const val RECENT_TAIL_COUNT = 5

/**
 * Thrown by [awaiting] on timeout. Subclass of [CancellationException] so
 * structured concurrency treats it as a normal cancellation: cancelling the
 * surrounding coroutine if uncaught, and not interfering with sibling
 * coroutines. Carries the augmented `"awaiting: no event matched within
 * Xms (saw N events: ...)"` message.
 *
 * **Why not [TimeoutCancellationException]**: kotlinx.coroutines defines TCE
 * with `internal` constructors and as `final`, so external code cannot
 * construct one with a custom message and cannot subclass it.
 * [AwaitingTimeoutException] is the closest reachable equivalent within the
 * structured-concurrency hierarchy.
 */
class AwaitingTimeoutException internal constructor(message: String) : CancellationException(message)
