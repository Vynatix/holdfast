package com.vynatix.holdfast

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Issue 01 — Holdfast.scope + Holdfast.Companion.defaultScope settable-once.
 *
 * `Holdfast.defaultScope` is a process-singleton settable at most once per process. Tests
 * here are written to be order-independent: whichever test "first sets" defaultScope wins,
 * and every subsequent set throws regardless of order.
 */
class VaultScopeTest {
    private class TestVault : Holdfast<TestVault>()

    @Test
    fun vault_scope_defaults_to_Vault_Companion_defaultScope() {
        val vault = TestVault()
        assertSame(Holdfast.defaultScope, vault.scope)
    }

    @Test
    fun multiple_unbound_vaults_share_the_same_default_scope() {
        val v1 = TestVault()
        val v2 = TestVault()
        assertSame(v1.scope, v2.scope)
    }

    @Test
    fun vault_scope_property_returns_a_non_null_CoroutineScope() {
        val vault = TestVault()
        // Property type is CoroutineScope (non-nullable). Just exercise the read.
        val scope: CoroutineScope = vault.scope
        assertNotNull(scope)
    }

    @Test
    fun assigning_defaultScope_a_second_time_throws_IllegalStateException() {
        val a = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("settable-once-test-a"))
        val b = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("settable-once-test-b"))

        // First assignment may succeed (if no prior test set defaultScope) or throw
        // (if a prior test already set it). Either is contract-compliant — the contract
        // is "at most one successful assignment per process".
        runCatching { Holdfast.defaultScope = a }

        // The very next assignment MUST throw, no matter what.
        val ex = assertFailsWith<IllegalStateException> {
            Holdfast.defaultScope = b
        }
        assertTrue(
            ex.message?.contains("defaultScope") == true,
            "Expected exception to mention 'defaultScope'; was: ${ex.message}",
        )
    }
}
