package com.vynatix.holdfast.testing

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
 * The default [timeout] of 60 seconds matches `runTest`'s own default; pass a
 * larger value when exercising long virtual-time delays.
 */
fun storeTest(
    timeout: Duration = 60.seconds,
    body: suspend StoreTestScope.() -> Unit,
): TestResult =
    runTest(timeout = timeout) {
        val scope = StoreTestScope(this)
        var bodyFailed = false
        try {
            scope.body()
        } catch (t: Throwable) {
            bodyFailed = true
            throw t
        } finally {
            scope.tearDown(bodyFailed)
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
