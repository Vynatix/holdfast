package com.vynatix.vault.testing.concurrency

import com.vynatix.vault.testing.VaultTestScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Run [block] repeatedly until it returns without throwing or [within] elapses.
 *
 * On a throw, the failure is captured, the call sleeps for [every], and tries
 * again. On a non-throwing return, the call returns immediately.
 *
 * If [within] expires before any run succeeds, an [AssertionError] is thrown
 * with the wrapper message
 * `"eventually: gave up after Xms (last: <inner-message>)"` and the most recent
 * failure as its cause.
 *
 * Both [within] and [every] are virtual-time durations under `vaultTest`:
 * `delay` and `withTimeoutOrNull` participate in the test scheduler, so a poll
 * over `1.seconds` resolves in near-zero wall time. Real-time waiting only
 * happens when called outside `runTest`.
 *
 * @param within total budget for the polling loop.
 * @param every interval between retries on failure.
 * @param block assertion or check to retry.
 */
@Suppress("TooGenericExceptionCaught")
suspend fun VaultTestScope.eventually(within: Duration = 1.seconds, every: Duration = 10.milliseconds, block: suspend () -> Unit) {
    var lastError: Throwable? = null
    val outcome = withTimeoutOrNull(within) {
        while (true) {
            try {
                block()
                return@withTimeoutOrNull Unit
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                lastError = t
                delay(every)
            }
        }
        @Suppress("UNREACHABLE_CODE")
        Unit
    }
    if (outcome == null) {
        val tailMessage = lastError?.message ?: "<no exception>"
        throw AssertionError(
            "eventually: gave up after ${within.inWholeMilliseconds}ms (last: $tailMessage)",
            lastError,
        )
    }
}
