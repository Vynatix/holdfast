package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.Disposable
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.effect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Issue 17 — `suspendDerived(sources, compute)` for suspending derived state.
 *
 * Derive a `State<T>` from one or more sources via a suspending compute. Each
 * source change schedules `store.scope.launch { compute() }`; the result is
 * staged via internal `suspendAction`. Cancellation of `store.scope` cancels
 * in-flight compute. Disposing the returned `Disposable` cancels the source
 * subscription and any outstanding job.
 *
 * "Later result wins" semantics under rapid source changes: each source change
 * triggers a new launched compute. Multiple in-flight computes race; the LAST
 * to commit becomes the visible value via the standard staged-write semantics
 * of `suspendAction`. Tests below assert eventual convergence to the
 * latest-source-driven result.
 */
private class SuspendDerivedVault(
    scope: CoroutineScope,
) : Store<SuspendDerivedVault>() {
    val n by state { 0 }
    val factor by state { 1 }

    init {
        bindToScope(scope)
    }
}

class SuspendDerivedTest {
    private val disposables = mutableListOf<Disposable>()
    private val scopes = mutableListOf<CoroutineScope>()

    @AfterTest fun cleanup() {
        disposables.forEach { runCatching { it.dispose() } }
        scopes.forEach { runCatching { it.cancel() } }
    }

    private fun newScope(): CoroutineScope = CoroutineScope(SupervisorJob()).also { scopes.add(it) }

    @Test
    fun suspendDerived_on_disposed_store_throws() {
        // P1-disposed-gaps: suspendDerived checks disposed at entry.
        val scope = newScope()
        val v = SuspendDerivedVault(scope)
        val source = v.n
        v.dispose()
        assertFailsWith<IllegalStateException> {
            v.suspendDerived(source, initial = 0) { n.value + 1 }
        }
    }

    @Test
    fun source_change_triggers_recompute_via_suspending_compute() =
        runBlocking {
            val scope = newScope()
            val v = SuspendDerivedVault(scope)
            val (derived, d) =
                v.suspendDerived(v.n) {
                    delay(20)
                    n.value + 1
                }
            disposables += d

            // Initial sync compute is invoked synchronously without suspension; the
            // contract is "initial value reflects the suspending compute's result on
            // construction-time sources." Since the compute body suspends, the
            // initial value at registration is whatever the eager call produces — we
            // accept any value here and only assert eventual post-mutation convergence.

            v action { n mutate 41 }
            // Eventually the launched recompute commits 42.
            withTimeout(2_000) {
                while (derived.value != 42) delay(10)
            }
            assertEquals(42, derived.value)
        }

    @Test
    fun cancelling_vault_scope_cancels_in_flight_compute() =
        runBlocking {
            val scope = newScope()
            val v = SuspendDerivedVault(scope)

            val started = kotlinx.coroutines.CompletableDeferred<Unit>()
            val finished = kotlinx.coroutines.CompletableDeferred<Unit>()
            val (derived, d) =
                v.suspendDerived(v.n) {
                    if (n.value != 0) {
                        started.complete(Unit)
                        try {
                            delay(5_000) // long enough that scope-cancel will hit it
                            finished.complete(Unit)
                            n.value + 100
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            // Cancellation: rethrow so the launched job is recorded as cancelled.
                            throw e
                        }
                    } else {
                        0
                    }
                }
            disposables += d

            v action { n mutate 7 }
            withTimeout(2_000) { started.await() }

            // Cancel the store scope. The in-flight launch must be cancelled.
            scope.cancel()

            // The compute body must NOT reach completion.
            delay(200)
            assertTrue(!finished.isCompleted, "compute body should be cancelled mid-flight")
            // Derived state must NOT have observed the would-have-been 107 result.
            assertNotEquals(107, derived.value, "no stale post-cancel result lands")
        }

    @Test
    fun disposing_returned_disposable_stops_recomputes() =
        runBlocking {
            val scope = newScope()
            val v = SuspendDerivedVault(scope)

            val (derived, d) =
                v.suspendDerived(v.n) {
                    delay(10)
                    n.value * 10
                }

            v action { n mutate 5 }
            withTimeout(2_000) {
                while (derived.value != 50) delay(10)
            }
            assertEquals(50, derived.value)

            d.dispose()

            // After dispose, mutating the source should NOT trigger a recompute.
            v action { n mutate 99 }
            delay(200) // give any (not-)scheduled launch ample time
            assertEquals(50, derived.value, "after dispose, derived no longer recomputes")
        }

    @Test
    fun multi_source_recompute_uses_latest_value_of_each() =
        runBlocking {
            val scope = newScope()
            val v = SuspendDerivedVault(scope)

            val (derived, d) =
                v.suspendDerived(v.n, v.factor) {
                    delay(15)
                    n.value * factor.value
                }
            disposables += d

            v action { n mutate 4 }
            withTimeout(2_000) { while (derived.value != 4) delay(10) }

            v action { factor mutate 5 }
            withTimeout(2_000) { while (derived.value != 20) delay(10) }
            assertEquals(20, derived.value)
        }

    @Test
    fun rapid_source_changes_eventually_converge_to_latest() =
        runBlocking {
            val scope = newScope()
            val v = SuspendDerivedVault(scope)

            val (derived, d) =
                v.suspendDerived(v.n) {
                    delay(5)
                    n.value + 1000
                }
            disposables += d

            // Burst-update the source many times. Multiple launches will race; the
            // final committed value should reflect the latest source value.
            repeat(20) { i ->
                v action { n mutate (i + 1) }
            }
            // Eventually derived.value == 1020 (last source = 20, +1000).
            withTimeout(5_000) {
                while (derived.value != 1020) delay(20)
            }
            assertEquals(1020, derived.value, "latest source value drives the final committed result")
        }

    @Test
    fun derived_state_fires_observers_on_recompute() =
        runBlocking {
            val scope = newScope()
            val v = SuspendDerivedVault(scope)
            val (derived, d) =
                v.suspendDerived(v.n) {
                    delay(5)
                    n.value + 1
                }
            disposables += d

            val seen = mutableListOf<Int>()
            val sub = derived effect { seen.add(this) }

            v action { n mutate 10 }
            withTimeout(2_000) {
                while (!seen.contains(11)) delay(10)
            }

            assertTrue(seen.contains(11), "observer should see the recomputed value 11; saw=$seen")
            sub.dispose()
        }

    // --- F16: the `initial`-seeded overload (no runBlocking) ---

    @Test
    fun initial_overload_shows_seed_before_first_compute_then_computes() =
        runBlocking {
            val scope = newScope()
            val v = SuspendDerivedVault(scope)
            val (derived, d) =
                v.suspendDerived(v.n, initial = -1) {
                    delay(30)
                    n.value + 100
                }
            disposables += d

            // Before the first async compute lands, the caller's seed is visible —
            // no runBlocking, no wasmJs hazard.
            assertEquals(-1, derived.value, "seed visible before first async compute")

            // First compute lands (n = 0 -> 100).
            withTimeout(2_000) { while (derived.value != 100) delay(10) }
            // Source change recomputes, same as the seedless overload.
            v action { n mutate 5 }
            withTimeout(2_000) { while (derived.value != 105) delay(10) }
            assertEquals(105, derived.value)
        }

    @Test
    fun disposing_initial_overload_unregisters_backing_state() =
        runBlocking {
            val scope = newScope()
            val v = SuspendDerivedVault(scope)
            val (derived, d) =
                v.suspendDerived(v.n, initial = 0) {
                    delay(10)
                    n.value + 1
                }
            withTimeout(2_000) { while (derived.value != 1) delay(10) }

            // A synthetic backing state is registered while the derived is live.
            assertTrue(
                v.properties.keys.any { it.startsWith("__suspendDerived_") },
                "backing state registered while live; keys=${v.properties.keys}",
            )

            d.dispose()

            // After dispose it's unregistered — no synthetic entry leaks.
            assertTrue(
                v.properties.keys.none { it.startsWith("__suspendDerived_") },
                "backing state unregistered on dispose; keys=${v.properties.keys}",
            )
        }

    @Test
    fun disposing_after_store_dispose_does_not_throw() =
        runBlocking {
            val scope = newScope()
            val v = SuspendDerivedVault(scope)
            val (_, d) =
                v.suspendDerived(v.n, initial = 0) {
                    delay(10)
                    n.value + 1
                }

            v.dispose()

            // removeState throws once the store is disposed; dispose swallows it.
            d.dispose()
        }
}
