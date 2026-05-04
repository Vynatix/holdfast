package com.vynatix.holdfast

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.test.Test
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * Issue 02 — `Holdfast.bindToScope(scope)` rebindable per-vault scope binding.
 *
 * Resolution order per source doc §3.1 / Q3:
 *   per-call → per-vault override → bound (bindToScope) → process default.
 */
class VaultBindToScopeTest {
    private class PlainVault : Holdfast<PlainVault>()

    /** Per-vault subclass override (resolution level 2) — must beat any bound scope. */
    private class OverrideVault(private val s: CoroutineScope) : Holdfast<OverrideVault>() {
        override val scope: CoroutineScope get() = s
    }

    private fun newScope(name: String): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName(name))

    @Test
    fun unbound_vault_returns_defaultScope() {
        val v = PlainVault()
        assertSame(Holdfast.defaultScope, v.scope)
    }

    @Test
    fun bindToScope_makes_vault_scope_return_the_bound_scope() {
        val v = PlainVault()
        val s = newScope("bound-1")
        v.bindToScope(s)
        assertSame(s, v.scope)
        assertNotSame(Holdfast.defaultScope, v.scope)
    }

    @Test
    fun rebinding_replaces_the_previously_bound_scope() {
        val v = PlainVault()
        val s1 = newScope("bound-rebind-1")
        val s2 = newScope("bound-rebind-2")
        v.bindToScope(s1)
        assertSame(s1, v.scope)
        v.bindToScope(s2)
        assertSame(s2, v.scope)
    }

    @Test
    fun per_vault_override_takes_precedence_over_bindToScope() {
        // Resolution order: per-call → per-vault override → bound → default.
        // A subclass that `override val scope` MUST win over any bindToScope call.
        val overrideScope = newScope("override-scope")
        val boundScope = newScope("bound-scope")
        val v = OverrideVault(overrideScope)
        v.bindToScope(boundScope)
        assertSame(overrideScope, v.scope)
        assertNotSame(boundScope, v.scope)
    }

    @Test
    fun two_vaults_have_independent_bound_scopes() {
        val a = PlainVault()
        val b = PlainVault()
        val sa = newScope("a")
        val sb = newScope("b")
        a.bindToScope(sa)
        b.bindToScope(sb)
        assertSame(sa, a.scope)
        assertSame(sb, b.scope)
    }
}
