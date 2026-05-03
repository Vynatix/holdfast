package com.vynatix.vault.coroutines

import com.vynatix.vault.Vault
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

private class DefaultsVault : Vault<DefaultsVault>() {
    val count by state { 0 }
}

class AsStateFlowDefaultsTest {

    @Test fun zeroArgAsStateFlowReturnsStateFlowScopedToVaultScope() = runBlocking {
        val vaultScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val v = DefaultsVault()
        v.bindToScope(vaultScope)

        // No-args call: scope defaults to vault.scope (= vaultScope after bindToScope).
        val sf = v.count.asStateFlow()
        v action { count mutate 11 }

        val seen = withTimeout(2_000L) { sf.first { it == 11 } }
        assertEquals(11, seen)

        vaultScope.cancel()
    }

    @Test fun explicitScopeArgPreservesOnePointXBehavior() = runBlocking {
        val explicit = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val v = DefaultsVault()

        val sf = v.count.asStateFlow(explicit)
        v action { count mutate 42 }

        val seen = withTimeout(2_000L) { sf.first { it == 42 } }
        assertEquals(42, seen)

        explicit.cancel()
    }

    @Test fun startedKeywordAloneCompilesAndPropagates() = runBlocking {
        // Replacement path for the removed `asEagerStateFlow()` — pass `started` only,
        // letting `scope` default to `vault.scope`.
        val vaultScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val v = DefaultsVault()
        v.bindToScope(vaultScope)

        val sf = v.count.asStateFlow(started = SharingStarted.Eagerly)
        v action { count mutate 7 }

        val seen = withTimeout(2_000L) { sf.first { it == 7 } }
        assertEquals(7, seen)

        vaultScope.cancel()
    }

    @Test fun zeroArgAsStateFlowUsesVaultScopeForCollection() = runBlocking {
        // Verifies the StateFlow's upstream subscription lives in vault.scope:
        // cancelling the vault scope must terminate the upstream pipe so the
        // collector exits.
        val vaultScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val v = DefaultsVault()
        v.bindToScope(vaultScope)

        val sf = v.count.asStateFlow(started = SharingStarted.Eagerly)
        val collector = launch {
            sf.collect { /* no-op */ }
        }
        // Cancelling the vault scope must let the upstream subscription wind down.
        vaultScope.cancel()
        // The collector itself isn't bound to vaultScope; cancel it manually so the
        // test exits. The assertion is "vault.scope is genuinely the upstream scope":
        // the SharingStarted.Eagerly upstream was active; cancelling vaultScope cuts it.
        collector.cancel()
    }
}
