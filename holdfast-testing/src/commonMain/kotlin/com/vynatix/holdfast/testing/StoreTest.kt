package com.vynatix.holdfast.testing

import com.vynatix.holdfast.testing.internal.PrivilegedHooks
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Entry point for the store test DSL.
 *
 * Wraps [runTest] so the body executes on the virtual time scheduler and any
 * tracked store state is cleaned up at the end:
 * ```
 * @Test
 * fun readsInitialState() = storeTest {
 *     val ctr = track(CountStore())
 *     assertEquals(0, ctr.read { count.value })
 * }
 * ```
 *
 * On scope exit, [StoreTestScope.tearDown] aggregates every [com.vynatix.holdfast.TransactionResult.Error]
 * that was returned from a tracked handle's `action`/`suspendAction` and not
 * consumed by a `shouldBe*` matcher (or
 * [StoreHandle.consumeAllPendingErrors]). If any are still pending and the
 * body itself returned cleanly, the resulting [AssertionError] fails the
 * test. The check is suppressed when the body already threw so the
 * root-cause exception is what the runner reports.
 *
 * A fatal error (issue #61: a `StackOverflowError`, an `OutOfMemoryError`)
 * wins: one the body threw stays what the runner reports, a teardown failure
 * added to it as suppressed; one teardown threw replaces the body's failure,
 * carrying it as suppressed.
 *
 * The default [timeout] of 60 seconds matches `runTest`'s own default; pass a
 * larger value when exercising long virtual-time delays.
 */
fun storeTest(
    timeout: Duration = 60.seconds,
    body: suspend StoreTestScope.() -> Unit,
): TestResult =
    runTest(timeout = timeout) {
        val scope = StoreTestScope(this)
        var bodyFailure: Throwable? = null
        try {
            scope.body()
        } catch (t: Throwable) {
            bodyFailure = t
            throw t
        } finally {
            scope.tearDownAfter(bodyFailure)
        }
    }

/**
 * [StoreTestScope.tearDown] in [storeTest]'s `finally`, after a body that
 * threw [bodyFailure] (`null`: it returned). A teardown failure replaces the
 * body's, as a `finally` that throws does, unless the body's is a fatal error
 * (issue #61): that one stays in flight, carrying the teardown's as
 * suppressed. A fatal teardown failure carries the body's.
 */
private fun StoreTestScope.tearDownAfter(bodyFailure: Throwable?) {
    if (bodyFailure == null) {
        tearDown(bodyAlreadyFailed = false)
        return
    }
    val teardownFailure = runCatching { tearDown(bodyAlreadyFailed = true) }.exceptionOrNull()
    when {
        teardownFailure == null || teardownFailure === bodyFailure -> Unit
        PrivilegedHooks.isFatal(bodyFailure) -> bodyFailure.addSuppressed(teardownFailure)
        else -> {
            if (PrivilegedHooks.isFatal(teardownFailure)) teardownFailure.addSuppressed(bodyFailure)
            throw teardownFailure
        }
    }
}

/** Deprecated alias for [storeTest], kept for one minor release. */
@Deprecated(
    message = "Renamed to storeTest.",
    replaceWith = ReplaceWith("storeTest(timeout, body)"),
    level = DeprecationLevel.WARNING,
)
fun vaultTest(
    timeout: Duration = 60.seconds,
    body: suspend StoreTestScope.() -> Unit,
): TestResult = storeTest(timeout, body)
