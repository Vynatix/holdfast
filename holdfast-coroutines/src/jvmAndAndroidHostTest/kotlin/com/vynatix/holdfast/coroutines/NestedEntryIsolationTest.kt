@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

private class NestedIsoA : Store<NestedIsoA>() {
    val n by state { 0 }
}

private class NestedIsoB : Store<NestedIsoB>() {
    val n by state { 0 }
}

private class NestedIsoC : Store<NestedIsoC>() {
    val n by state { 0 }
}

private class NestedIsoRollback : RuntimeException("boom")

/**
 * Issue #38: a suspending entry NESTED in another entry's body runs under a
 * settle-scope view of its own, so the outer body's other coroutines — which
 * carry the outer body's scope — read the nested entry's writes only once
 * committed. The nested body itself still reads its enclosing body's pending
 * writes (`SuspendingIsolationTest` in `:wasm-tests`, which also runs these
 * shapes on wasmJs), and a nested entry on the same store still fails fast
 * instead of waiting for itself (`SuspendSettleTest`).
 */
class NestedEntryIsolationTest {
    private val stores = mutableListOf<Store<*>>()

    @AfterTest fun cleanup() {
        stores.forEach { runCatching { it.dispose() } }
    }

    /** The issue's first reproduction: a rolled-back `5` must not be read. */
    @Test fun theOuterBodysOtherCoroutinesDoNotReadANestedSuspendActionsPendingWrite() =
        runTest {
            val a = NestedIsoA()
            val b = NestedIsoB()
            stores += listOf(a, b)
            val parked = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            var seen = -1

            a.suspendAction {
                coroutineScope {
                    launch {
                        b.suspendAction {
                            n mutate 5
                            parked.complete(Unit)
                            gate.await()
                            throw NestedIsoRollback()
                        }
                    }
                    launch {
                        parked.await()
                        seen = b.n.value
                        gate.complete(Unit)
                    }
                }
            }

            assertEquals(0, seen, "the outer body's other coroutine read the nested entry's uncommitted write")
            assertEquals(0, b.n.value, "the nested entry's write rolled back")
        }

    /** The issue's second reproduction: staged `7`/`8` must read as `(0, 0)`. */
    @Test fun theOuterBodysOtherCoroutinesDoNotReadANestedSuspendAtomicsPendingWrites() =
        runTest {
            val a = NestedIsoA()
            val b = NestedIsoB()
            val c = NestedIsoC()
            stores += listOf(a, b, c)
            val parked = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            var seen = -1 to -1

            a.suspendAction {
                coroutineScope {
                    launch {
                        runCatching {
                            suspendAtomic(b, c) {
                                b { n mutate 7 }
                                c { n mutate 8 }
                                parked.complete(Unit)
                                gate.await()
                                throw NestedIsoRollback()
                            }
                        }
                    }
                    launch {
                        parked.await()
                        seen = b.n.value to c.n.value
                        gate.complete(Unit)
                    }
                }
            }

            assertEquals(0 to 0, seen, "the outer body's other coroutine read the nested frame's uncommitted writes")
            assertEquals(0 to 0, b.n.value to c.n.value, "the nested frame's writes rolled back")
        }

    /**
     * The fail-fast of `SuspendSettleTest` must not over-fire: a suspending
     * entry on the same store from a coroutine the body LAUNCHED (a different
     * owner `Job` than the one the holder locked with) still waits for the
     * holder and runs once it has returned — its caller is not the holder's
     * own body coroutine.
     */
    @Test fun aSameStoreEntryFromACoroutineTheBodyLaunchedWaitsForTheHolder() =
        runTest {
            val s = NestedIsoA()
            stores += s
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val outer =
                launch {
                    assertIs<com.vynatix.holdfast.TransactionResult.Success<*>>(
                        s.suspendAction {
                            entered.complete(Unit)
                            release.await()
                            n mutate 5
                        },
                    )
                }
            entered.await()
            val inner =
                launch {
                    s.suspendAction { n mutate 7 }.getOrThrow()
                }
            release.complete(Unit)
            joinAll(outer, inner)

            assertEquals(7, s.n.value, "the launched entry waited for the holder and then ran")
        }
}
