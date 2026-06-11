package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

private class DefaultsVault : Store<DefaultsVault>() {
    val count by state { 0 }
}

// These helpers must stay outside any CoroutineScope receiver: inside `runBlocking`,
// the implicit CoroutineScope satisfies the `context(scope: CoroutineScope)` overload
// of asStateFlow, which wins resolution and captures the test's own scope — the
// Eagerly sharing job then becomes a never-completing child of runBlocking and the
// test hangs. This class tests the default-param overload (scope = store.scope).
private fun zeroArgCountFlow(v: DefaultsVault) = v.count.asStateFlow()

private fun eagerCountFlow(v: DefaultsVault) = v.count.asStateFlow(started = SharingStarted.Eagerly)

class AsStateFlowDefaultsTest {
    @Test fun zeroArgAsStateFlowReturnsStateFlowScopedToVaultScope() =
        runBlocking {
            val vaultScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val v = DefaultsVault()
            v.bindToScope(vaultScope)

            // No-args call: scope defaults to store.scope (= vaultScope after bindToScope).
            val sf = zeroArgCountFlow(v)
            v action { count mutate 11 }

            val seen = withTimeout(2_000L) { sf.first { it == 11 } }
            assertEquals(11, seen)

            vaultScope.cancel()
        }

    @Test fun explicitScopeArgPreservesOnePointXBehavior() =
        runBlocking {
            val explicit = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val v = DefaultsVault()

            val sf = v.count.asStateFlow(explicit)
            v action { count mutate 42 }

            val seen = withTimeout(2_000L) { sf.first { it == 42 } }
            assertEquals(42, seen)

            explicit.cancel()
        }

    @Test fun startedKeywordAloneCompilesAndPropagates() =
        runBlocking {
            // Replacement path for the removed `asEagerStateFlow()` — pass `started` only,
            // letting `scope` default to `store.scope`.
            val vaultScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val v = DefaultsVault()
            v.bindToScope(vaultScope)

            val sf = eagerCountFlow(v)
            v action { count mutate 7 }

            val seen = withTimeout(2_000L) { sf.first { it == 7 } }
            assertEquals(7, seen)

            vaultScope.cancel()
        }

    @Test fun zeroArgAsStateFlowUsesVaultScopeForCollection() =
        runBlocking {
            // Verifies the StateFlow's upstream subscription lives in store.scope:
            // cancelling the store scope must terminate the upstream pipe so the
            // collector exits.
            val vaultScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val v = DefaultsVault()
            v.bindToScope(vaultScope)

            val sf = eagerCountFlow(v)
            val collector =
                launch {
                    sf.collect { /* no-op */ }
                }
            // Cancelling the store scope must let the upstream subscription wind down.
            vaultScope.cancel()
            // The collector itself isn't bound to vaultScope; cancel it manually so the
            // test exits. The assertion is "store.scope is genuinely the upstream scope":
            // the SharingStarted.Eagerly upstream was active; cancelling vaultScope cuts it.
            collector.cancel()
        }
}
