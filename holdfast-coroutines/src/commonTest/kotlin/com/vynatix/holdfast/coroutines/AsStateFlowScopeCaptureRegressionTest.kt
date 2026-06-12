package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

private class RegressionVault : Store<RegressionVault>() {
    val count by state { 0 }
}

/**
 * Issue 5 regression — `asStateFlow` must never capture an ambient
 * [CoroutineScope].
 *
 * The removed `context(scope: CoroutineScope)` overload made any zero-scope-arg
 * call *inside a coroutine body* resolve against the implicit scope receiver:
 * `runBlocking { v.count.asStateFlow(started = SharingStarted.Eagerly) }` attached
 * the eager sharing job to runBlocking's own scope, and runBlocking never returned.
 * With only the default-param form left, the same call shape must bind the upstream
 * subscription to the owning store's scope.
 */
class AsStateFlowScopeCaptureRegressionTest {
    @Test fun zeroScopeArgInsideRunBlockingUsesStoreScopeNotAmbientScope() {
        val vaultScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val v = RegressionVault()
        v.bindToScope(vaultScope)

        lateinit var sf: StateFlow<Int>
        runBlocking {
            // The exact call shape that used to hang: zero scope arg + Eagerly,
            // inside a coroutine body where an implicit CoroutineScope is available.
            sf = v.count.asStateFlow(started = SharingStarted.Eagerly)
            v action { count mutate 5 }
            assertEquals(5, withTimeout(2_000L) { sf.first { it == 5 } })
        }
        // Reaching this line IS the regression assertion: under the old overload the
        // sharing job was a never-completing child of runBlocking and we hung above.

        runBlocking {
            // And the upstream genuinely lives in the store's scope: cancel it, wait
            // for the sharing job to wind down, and verify sharing has stopped —
            // later commits no longer reach the StateFlow.
            vaultScope.cancel()
            withTimeout(2_000L) { vaultScope.coroutineContext.job.join() }
            v action { count mutate 6 }
            assertEquals(5, sf.value)
        }
    }
}
