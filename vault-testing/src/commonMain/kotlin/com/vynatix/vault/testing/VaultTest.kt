package com.vynatix.vault.testing

import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Entry point for the vault test DSL.
 *
 * Wraps [runTest] so the body executes on the virtual time scheduler and any
 * tracked vault state is cleaned up at the end:
 * ```
 * @Test
 * fun readsInitialState() = vaultTest {
 *     val ctr = track(CountVault())
 *     assertEquals(0, ctr.read { count.value })
 * }
 * ```
 *
 * The default [timeout] of 60 seconds matches `runTest`'s own default; pass a
 * larger value when exercising long virtual-time delays.
 */
fun vaultTest(timeout: Duration = 60.seconds, body: suspend VaultTestScope.() -> Unit): TestResult = runTest(timeout = timeout) {
    val scope = VaultTestScope(this)
    try {
        scope.body()
    } finally {
        scope.tearDown()
    }
}
