package com.vynatix.holdfast.coroutines

import kotlinx.coroutines.delay
import kotlinx.coroutines.yield

/** The first backoff after a yield, in milliseconds; each retry doubles it up to [MAX_BACKOFF_MS]. */
private const val FIRST_BACKOFF_MS = 1L

/** The longest [backOffUntil] waits between two attempts, in milliseconds. */
private const val MAX_BACKOFF_MS = 32L

/**
 * Call [attempt] until it returns `true`, suspending between tries — a yield,
 * then delays doubling from 1 ms to 32 ms — so the wait never holds a thread
 * and never queues on what it waits for. A delay really suspends even under
 * `Dispatchers.Unconfined`, so a holder whose body this coroutine runs inline
 * on the same thread gets to go on. The waits run in the coroutine's own
 * time, never a store's clock: a fixed test clock cannot wedge them.
 *
 * @throws kotlinx.coroutines.CancellationException when the caller is
 *   cancelled while it waits, and whatever [attempt] throws.
 */
internal suspend fun backOffUntil(attempt: () -> Boolean) {
    var backoff = 0L
    while (!attempt()) {
        if (backoff == 0L) yield() else delay(backoff)
        backoff = (backoff * 2).coerceIn(FIRST_BACKOFF_MS, MAX_BACKOFF_MS)
    }
}
