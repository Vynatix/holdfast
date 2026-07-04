package com.vynatix.holdfast.testing.matcher

import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Assert that this synchronous block completes within [duration] of wall-clock
 * time, using [TimeSource.Monotonic] for measurement.
 *
 * Failure message: `"completed in Xms, expected ≤ Yms"`.
 *
 * Note: under `runTest` (which `storeTest` uses), `delay` is virtual. The
 * wall-clock measurement here does NOT capture virtual time — it captures the
 * real elapsed time of the body's non-suspended portions. If your body sleeps
 * via `delay`, the measurement may be near-zero even with seconds of virtual
 * delay. For real microbenchmarking, use kotlinx-benchmark, not this matcher.
 */
infix fun (() -> Unit).shouldCompleteWithin(duration: Duration) {
    val mark = TimeSource.Monotonic.markNow()
    this()
    val elapsed = mark.elapsedNow()
    if (elapsed > duration) {
        throw AssertionError(
            "completed in ${elapsed.inWholeMilliseconds}ms, expected ≤ ${duration.inWholeMilliseconds}ms",
        )
    }
}

/**
 * Suspending counterpart of [shouldCompleteWithin]. Records wall-clock start,
 * invokes the suspending block, then asserts elapsed time <= [duration].
 *
 * Note: under `runTest` (which `storeTest` uses), `delay` is virtual. The
 * wall-clock measurement here does NOT capture virtual time — it captures the
 * real elapsed time of the body's non-suspended portions. If your body sleeps
 * via `delay`, the measurement may be near-zero even with seconds of virtual
 * delay. For real microbenchmarking, use kotlinx-benchmark, not this matcher.
 */
suspend infix fun (suspend () -> Unit).shouldCompleteWithin(duration: Duration) {
    val mark = TimeSource.Monotonic.markNow()
    this()
    val elapsed = mark.elapsedNow()
    if (elapsed > duration) {
        throw AssertionError(
            "completed in ${elapsed.inWholeMilliseconds}ms, expected ≤ ${duration.inWholeMilliseconds}ms",
        )
    }
}
