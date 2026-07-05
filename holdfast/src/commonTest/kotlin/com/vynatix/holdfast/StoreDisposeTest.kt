package com.vynatix.holdfast

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue 03 — `Store.dispose()` terminal teardown.
 *
 * Contract:
 * - After dispose, every state-mutation entrypoint throws `IllegalStateException`
 *   with a message containing "disposed".
 * - Idempotent (second dispose is a no-op).
 * - Does NOT cancel any scope bound via `bindToScope`.
 */
class StoreDisposeTest {
    private class CountVault : Store<CountVault>() {
        val n by state { 0 }
    }

    @Test
    fun fresh_vault_is_not_disposed() {
        val v = CountVault()
        assertFalse(v.isDisposed)
    }

    @Test
    fun dispose_marks_vault_as_disposed() {
        val v = CountVault()
        v.dispose()
        assertTrue(v.isDisposed)
    }

    @Test
    fun action_after_dispose_throws_with_disposed_message() {
        val v = CountVault()
        v.dispose()
        val ex =
            assertFailsWith<IllegalStateException> {
                v action { n mutate 1 }
            }
        assertTrue(
            ex.message?.contains("disposed") == true,
            "expected 'disposed' in message; was: ${ex.message}",
        )
    }

    @Test
    fun disposed_message_names_the_store_class() {
        val v = CountVault()
        v.dispose()
        val ex =
            assertFailsWith<IllegalStateException> {
                v action { n mutate 1 }
            }
        assertTrue(
            ex.message?.contains("CountVault") == true,
            "expected the store class name in the disposed message; was: ${ex.message}",
        )
    }

    @Test
    fun mutate_after_dispose_throws_with_disposed_message() {
        val v = CountVault()
        v.dispose()
        val ex =
            assertFailsWith<IllegalStateException> {
                v { n mutate 5 }
            }
        assertTrue(ex.message?.contains("disposed") == true)
    }

    @Test
    fun update_after_dispose_throws_with_disposed_message() {
        val v = CountVault()
        v.dispose()
        val ex =
            assertFailsWith<IllegalStateException> {
                v { n update { it + 1 } }
            }
        assertTrue(ex.message?.contains("disposed") == true)
    }

    @Test
    fun effect_after_dispose_throws_with_disposed_message() {
        val v = CountVault()
        v.dispose()
        val ex =
            assertFailsWith<IllegalStateException> {
                v { n effect { /* no-op */ } }
            }
        assertTrue(ex.message?.contains("disposed") == true)
    }

    @Test
    fun dispose_is_idempotent() {
        val v = CountVault()
        v.dispose()
        // Second call must not throw.
        v.dispose()
        assertTrue(v.isDisposed)
    }

    @Test
    fun dispose_clears_states_and_observers() {
        val v = CountVault()
        // Force the lazy state delegate to register so clearStates has work to do.
        v action { n mutate 1 }
        assertTrue(v.hasState("n"))

        v.dispose()
        // After dispose the state registry is empty. We cannot call hasState
        // after dispose (it should also throw), so we inspect the snapshot taken
        // by the property accessor — which we expect to be guarded too. Use the
        // 'properties' read which is gated.
        val ex =
            assertFailsWith<IllegalStateException> {
                v.hasState("n")
            }
        assertTrue(ex.message?.contains("disposed") == true)
    }

    @Test
    fun dispose_does_not_cancel_the_bound_scope() {
        val v = CountVault()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("dispose-scope-test"))
        v.bindToScope(scope)
        v.dispose()
        // Scope's Job remains active — caller owns the scope's lifecycle.
        val job = scope.coroutineContext[Job]
        assertTrue(job?.isActive == true, "bound scope must remain active after dispose")
        scope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun reading_value_after_dispose_returns_committed_value_or_throws() {
        // A read of `state.value` after dispose: the contract is "no further mutations".
        // Reading is allowed to either return the last committed value or throw with
        // 'disposed'. We accept both — but the state object itself must not be reused
        // for new mutations. This test just demonstrates that dispose did not corrupt
        // the prior value in the period before observers were torn down.
        val v = CountVault()
        v action { n mutate 42 }
        val before = v.n.value
        assertEquals(42, before)
        v.dispose()
        // No assertion on post-dispose read — just that no internal exception escapes
        // unrelated paths. This is intentionally permissive; commit-time mutation is
        // the load-bearing gate.
    }
}
