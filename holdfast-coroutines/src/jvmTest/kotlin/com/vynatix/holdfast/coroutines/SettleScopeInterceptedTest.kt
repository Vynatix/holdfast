@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.SettleScope
import com.vynatix.holdfast.SettleScopes
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.derivedState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class InterceptedSource : Store<InterceptedSource>() {
    val n by state { 0 }
}

private class InterceptedOther : Store<InterceptedOther>() {
    val m by state { 0 }
}

private class InterceptedHost : Store<InterceptedHost>() {
    val y by state { 0 }
}

/**
 * The iOS/wasmJs carrier of a suspending entry's settle scope — exactly the
 * `withSlotIntercepted(scope, SettleScopes::install, SettleAmbientContext(scope))`
 * call their `withSettleScope` actuals make — exercised on the JVM, where it
 * is otherwise unused: those targets have no `ThreadContextElement`, and
 * neither runs its tests on this host (wasmJs never does). It must start the
 * entry without a dispatch with the scope installed, install it again on
 * every resumption, carry it in the context so a nested entry joins it, and
 * leave it installed nowhere else.
 */
class SettleScopeInterceptedTest {
    private val scope: SettleScope = SettleScopes.open()

    @AfterTest fun settleWhatIsLeft() {
        if (scope.isOpen) settleInstalled(scope)
    }

    private fun settleInstalled(scope: SettleScope) {
        val prior = SettleScopes.install(scope)
        try {
            scope.settle()
        } finally {
            SettleScopes.install(prior)
        }
    }

    private suspend fun <T> carried(block: suspend () -> T): T =
        withSlotIntercepted(scope, SettleScopes::install, SettleAmbientContext(scope), block)

    @Test fun theFirstSegmentRunsUndispatchedWithTheScopeInstalledAndCarried() =
        runBlocking {
            var siblingRan = false
            // Queued on runBlocking's event loop: a dispatch before the block
            // starts would let it run first.
            launch { siblingRan = true }
            var installed: SettleScope? = null
            var inContext: SettleScope? = null
            var startedBeforeSibling = false
            carried {
                installed = SettleScopes.current()
                inContext = currentCoroutineContext()[SettleAmbientContext]?.scope
                startedBeforeSibling = !siblingRan
            }
            assertSame(scope, installed)
            assertSame(scope, inContext)
            assertTrue(startedBeforeSibling, "the entry must start right away, not after a dispatch")
            assertNull(SettleScopes.current(), "the caller does not see the scope once the block returns")
        }

    @Test fun theScopeIsInstalledAgainAfterEveryResumption() =
        runBlocking {
            val seen = mutableListOf<SettleScope?>()
            carried {
                yield()
                seen += SettleScopes.current()
                yield()
                seen += SettleScopes.current()
            }
            assertEquals(listOf<SettleScope?>(scope, scope), seen)
            assertNull(SettleScopes.current())
        }

    /**
     * The carrier replaces the dispatcher as the context's interceptor, and
     * `delay` takes its timer from that interceptor: the carrier must forward
     * the dispatcher's, or a delay inside the entry runs on
     * kotlinx.coroutines' real-time default — a suspending body's `delay` no
     * longer advanced a test's virtual clock, and a hydration gate backing off
     * inside the entry waited real milliseconds while `advanceTimeBy` stood
     * still (HydrationOverlayTest on iOS).
     */
    @Test fun aDelayInsideTheEntryRunsOnTheDispatchersClock() =
        runTest {
            carried { delay(1_000) }
            assertEquals(1_000, currentTime, "the delay advanced the test dispatcher's virtual time")
        }

    /** `withTimeout` takes its timer the same way. */
    @Test fun aTimeoutInsideTheEntryRunsOnTheDispatchersClock() =
        runTest {
            val outcome = carried { withTimeoutOrNull(1_000) { awaitCancellation() } }
            assertNull(outcome, "the timeout fired")
            assertEquals(1_000, currentTime, "the timeout ran on the test dispatcher's virtual time")
        }

    /**
     * A nested entry joins the carried scope FOR SETTLING, under a view of it
     * with an identity of its own (#38 — this row used to pin the bare join:
     * the nested entry's slot held the carried scope itself, which is what
     * let the enclosing body's other coroutines read the nested entry's
     * uncommitted writes): the view settles nothing, and is open exactly
     * while the carried scope is — its lifecycle is the carried one's.
     */
    @Test fun aNestedEntryJoinsUnderAViewOfTheCarriedScopeAndLeavesItOpen() =
        runBlocking {
            var nestedSaw: SettleScope? = null
            carried {
                yield()
                settlingSuspended { nestedSaw = SettleScopes.current() }
                assertTrue(scope.isOpen, "the nested entry joined: it did not settle the scope")
            }
            val view = assertNotNull(nestedSaw)
            assertTrue(view !== scope, "the nested entry runs under a view of the carried scope (#38)")
            assertTrue(view.isOpen, "the carried scope is open, so the view is")
            settleInstalled(scope)
            assertFalse(view.isOpen, "the view is open exactly while the carried scope is")
        }

    @Test fun anotherCoroutineOnTheSameThreadNeverSeesTheScope() =
        runBlocking {
            val release = CompletableDeferred<Unit>()
            val entry = async { carried { release.await() } }
            yield()
            // runBlocking is single-threaded: the bystander runs on the same
            // thread while the entry is suspended.
            val bystanderSaw = SettleScopes.current()
            release.complete(Unit)
            entry.await()
            assertNull(bystanderSaw)
            assertNull(SettleScopes.current())
        }

    /** A blocking action on a resumption joins the carried scope: its derived states settle with the entry. */
    @Test fun aBlockingActionAfterAResumptionJoinsTheScope() {
        val source = InterceptedSource()
        val host = InterceptedHost()
        var computes = 0
        val doubled =
            host.derivedState(source.n) {
                computes++
                source.n.value * 2
            }
        try {
            runBlocking {
                carried {
                    yield()
                    source.action { n mutate 1 }.getOrThrow()
                }
            }
            assertEquals(1, computes, "joined the scope: nothing recomputes before it settles")
            assertEquals(0, doubled.value)

            settleInstalled(scope)

            assertEquals(2, computes)
            assertEquals(2, doubled.value)
        } finally {
            doubled.dispose()
        }
    }

    /**
     * A dispatcher switch inside the entry displaces the carrier, so a
     * nested `suspendAction` finds its carried scope NOT installed in the
     * slot. It re-wraps the switched dispatcher with it (`settlingSuspended`),
     * so its commit — fanning out right there — sees the scope again, as on
     * the JVM, where the `ThreadContextElement` survives the switch: the
     * recomputes its commits change queue into the carried scope, dedup by
     * identity, and run once, when that scope settles — not once per source
     * commit through the source store's queue.
     */
    @Test fun aNestedDispatcherSwitchIsReWrappedSoCommitsJoinTheCarriedScope() {
        val left = InterceptedSource()
        val right = InterceptedOther()
        val host = InterceptedHost()
        val computes = AtomicInteger()
        val sourceHeld = AtomicBoolean(false)
        val pair =
            host.derivedState(left.n, right.m) {
                computes.incrementAndGet()
                if (left.activeTransaction != null || right.activeTransaction != null) sourceHeld.set(true)
                left.n.value to right.m.value
            }
        try {
            var installedAfterSwitch: SettleScope? = null
            runBlocking {
                carried {
                    withContext(Dispatchers.Default) {
                        installedAfterSwitch = SettleScopes.current()
                        left.suspendAction { n mutate 1 }.getOrThrow()
                        right.suspendAction { m mutate 1 }.getOrThrow()
                    }
                }
            }
            assertNull(installedAfterSwitch, "the carrier is not installed after the switch")
            assertTrue(scope.isOpen, "the nested entries joined the carried scope without settling it")
            assertEquals(0 to 0, pair.value, "nothing recomputes before the carried scope settles")
            assertEquals(1, computes.get(), "only the initial compute ran")

            settleInstalled(scope)

            assertEquals(1 to 1, pair.value)
            assertEquals(2, computes.get(), "the two commits queued ONE deduped recompute into the carried scope")
            assertFalse(sourceHeld.get(), "the recompute ran after its source commits released the stores")
        } finally {
            pair.dispose()
        }
    }
}
