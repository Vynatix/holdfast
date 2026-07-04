package com.vynatix.holdfast.testing

import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.testing.matcher.shouldBeError
import com.vynatix.holdfast.testing.matcher.shouldBeSuccess
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class AutoRegVault : Store<AutoRegVault>() {
    val n by state { 0 }
    val label by state { "init" }
}

/** Plain user middleware — used to verify the instance overload routes correctly. */
private class AutoRegNoopMiddleware : Middleware<AutoRegVault>()

/**
 * Self-tests for the [StoreAutoRegistration] member-extension surface that
 * lets tests skip explicit [StoreTestScope.track] and call act / read /
 * timeline / etc. directly on a [Store] instance.
 */
class AutoRegistrationTest {
    @Test
    fun actAutoRegistersAndCommits() =
        storeTest {
            val v = AutoRegVault()
            val result = v.act { n mutate 5 }
            result.shouldBeSuccess()
            assertEquals(5, v.read { n.value })
        }

    @Test
    fun readAutoRegistersAndReturnsBlockResult() =
        storeTest {
            val v = AutoRegVault()
            // First touch via read should auto-register the store.
            val combined = v.read { "${label.value}-${n.value}" }
            assertEquals("init-0", combined)
        }

    @Test
    fun timelineReflectsActWithoutWarmUp() =
        storeTest {
            val v = AutoRegVault()
            // act auto-registers the store and routes through the handle, so no
            // warm-up read is needed — the recorder is installed before the
            // transaction runs.
            v.act { n mutate 5 }.shouldBeSuccess()

            assertTrue(v.timeline.isNotEmpty(), "expected timeline to record events, got empty")
            assertNotNull(v.lastTransaction)
        }

    @Test
    fun rawStoreActionOnUntrackedStoreBypassesTracking() =
        storeTest {
            // REGRESSION PIN for the member-shadow trap: Store.action is a member
            // infix function and always wins resolution, so calling it on an
            // UNTRACKED store cannot auto-register — nothing is recorded and its
            // errors bypass the pending-error guard. Use act/track/handle.action.
            val v = AutoRegVault()
            (v action { n mutate 5 }).shouldBeSuccess()

            // The store committed (production behavior is unchanged)...
            assertEquals(5, v.read { n.value })
            // ...but the action ran BEFORE tracking, so the timeline is empty and
            // no transaction/result was captured. (The v.timeline read itself
            // auto-registered the store just now — too late for the action.)
            assertTrue(v.timeline.none { it is TransactionEvent }, "expected no recorded transactions, got ${v.timeline}")
            assertNull(v.lastTransaction)
            assertNull(v.lastResult)
        }

    @Test
    fun emissionsAutoRegistersAndFiltersByProperty() =
        storeTest {
            val v = AutoRegVault()
            v.act { n mutate 7 }.shouldBeSuccess()

            // emissions(::prop) should work on the auto-registered handle.
            val em = v.emissions(AutoRegVault::n)
            assertEquals(1, em.size)
            assertEquals(0, em.single().oldValue)
            assertEquals(7, em.single().newValue)

            // Untouched property: empty.
            assertTrue(v.emissions(AutoRegVault::label).isEmpty())
        }

    @Test
    fun transactionsAutoRegistersAndFiltersToTransactionEvents() =
        storeTest {
            val v = AutoRegVault()
            v.act { n mutate 1 }.shouldBeSuccess()
            v.act { n mutate 2 }.shouldBeSuccess()

            // 2 starts + 2 commits = 4 transaction events.
            val tx = v.transactions
            assertEquals(4, tx.size, "expected 4 transaction events, got $tx")
            assertEquals(2, tx.filterIsInstance<TransactionStarted>().size)
            assertEquals(2, tx.filterIsInstance<TransactionCommitted>().size)
        }

    @Test
    fun bridgeEventsAutoRegistersAndIsEmptyInV1() =
        storeTest {
            val v = AutoRegVault()
            v.act { n mutate 1 }.shouldBeSuccess()
            // No bridge attached, so this view is empty for both states.
            assertTrue(v.bridgeEvents(AutoRegVault::n).isEmpty())
            assertTrue(v.bridgeEvents(AutoRegVault::label).isEmpty())
        }

    @Test
    fun middlewareEventsOfReifiedAutoRegisters() =
        storeTest {
            val v = AutoRegVault()
            v.act { n mutate 1 }.shouldBeSuccess()

            // The recorder pushes self-events for itself, so the typed view for any
            // user class is empty in v1 (see StoreEvent KDoc).
            assertEquals(0, v.middlewareEventsOf<AutoRegNoopMiddleware>().size)
        }

    @Test
    fun middlewareEventsOfInstanceAutoRegistersAndReturnsEmptyForUserInstance() =
        storeTest {
            val v = AutoRegVault()
            val m = AutoRegNoopMiddleware()
            v.middlewares(m)
            v.act { n mutate 1 }.shouldBeSuccess()

            // The instance overload returns empty for user-installed middlewares —
            // matches the v1 caveat documented on StoreHandle.middlewareEventsOf.
            assertEquals(0, v.middlewareEventsOf(m).size)
        }

    @Test
    fun suspendActionAutoRegisters() =
        storeTest {
            val v = AutoRegVault()
            val result =
                v.suspendAction {
                    delay(0)
                    n mutate 9
                    n.value
                }
            result.shouldBeSuccess()
            assertEquals(9, v.read { n.value })
        }

    @Test
    fun multipleAutoRegisteringCallsShareSameHandle() =
        storeTest {
            val v = AutoRegVault()
            // Three different extension types — all should resolve through the same
            // handle, so the timeline accumulates across them.
            v.act { n mutate 1 }.shouldBeSuccess()
            v.act { n mutate 2 }.shouldBeSuccess()
            val readBack = v.read { n.value }
            assertEquals(2, readBack)

            // Both transactions must be in the auto-registered handle's timeline.
            val tx = v.transactions
            assertEquals(4, tx.size, "expected 4 transaction events across two actions, got $tx")
        }

    @Test
    fun explicitTrackThenImplicitShareSameHandle() =
        storeTest {
            val v = AutoRegVault()
            // Explicit registration first — produces a handle and installs the recorder.
            val explicit = track(v)
            assertNull(explicit.lastTransaction, "no action ran yet")

            // Store.action goes through the middleware chain (recorder is installed),
            // so events are captured even though the member function bypasses the
            // handle's result bookkeeping.
            (v action { n mutate 5 }).shouldBeSuccess()

            // The explicit handle should reflect the action.
            assertNotNull(explicit.lastTransaction)
            // Auto-reg view (v.lastTransaction) routes through the same handle.
            assertSame(explicit.lastTransaction, v.lastTransaction)
            // Both views point at the same recorder buffer.
            assertEquals(explicit.timeline, v.timeline)
        }

    @Test
    fun implicitThenExplicitTrackReturnsSameHandle() =
        storeTest {
            val v = AutoRegVault()
            // Auto-register first via act (installs the recorder and runs the action).
            v.act { n mutate 3 }.shouldBeSuccess()

            // Now ask for the handle explicitly — registry should be idempotent.
            val explicit = track(v)
            // The explicit handle must already see the action recorded by the recorder.
            assertNotNull(explicit.lastTransaction)
            assertEquals(3, explicit.read { n.value })
        }

    @Test
    fun explicitTrackWithCaptureNoneIsRespectedByExtensions() =
        storeTest {
            val v = AutoRegVault()
            // Pre-register with Capture.None — the registry's idempotency rule means
            // a later auto-reg call reuses this handle, so the capture mode sticks.
            track(v, Capture.None)
            v.act { n mutate 4 }.shouldBeSuccess()

            // Capture.None records nothing; verify via the auto-registered view too.
            assertTrue(v.timeline.isEmpty(), "expected empty timeline for Capture.None, got ${v.timeline}")
            assertNull(v.lastTransaction)
            assertNull(v.lastResult)
            // But the read still reflects the actual mutation — Capture.None disables
            // recording, not the underlying store.
            assertEquals(4, v.read { n.value })
        }

    @Test
    fun autoRegisteredVaultIsDisposedAtScopeExit() {
        // Capture an auto-registered store outside the storeTest body — after
        // teardown its handle must be detached: a fresh storeTest body that
        // touches the SAME store instance should produce a fresh, empty handle
        // (no leaked timeline carries over).
        val outerVault = AutoRegVault()

        storeTest {
            outerVault.act { n mutate 5 }.shouldBeSuccess()
            assertTrue(outerVault.timeline.isNotEmpty())
        }

        storeTest {
            // After the previous scope tore down, asking for the timeline should
            // start empty — a brand-new handle in a brand-new registry.
            assertTrue(outerVault.timeline.isEmpty(), "expected empty timeline post-teardown, got ${outerVault.timeline}")
            assertNull(outerVault.lastTransaction)
            assertNull(outerVault.lastResult)
        }
    }

    @Test
    fun actErrorIsRecordedInPendingErrors() =
        storeTest {
            val v = AutoRegVault()
            // act routes through StoreHandle.action — feeding the per-handle
            // pendingErrors list and the lastResult slot. The shouldBeError
            // matcher consumes the pending mark so the scope-exit guard
            // doesn't fire.
            val ise = IllegalStateException("boom")
            val result: TransactionResult<Unit> = v.act { throw ise }
            assertSame(result as TransactionResult<*>, v.lastResult)
            result.shouldBeError<IllegalStateException> { assertSame(ise, exception) }
        }

    @Test
    fun suspendActionErrorIsRecordedInPendingErrorsViaAutoRegistration() =
        storeTest {
            val v = AutoRegVault()
            // suspendAction routes through the member-extension (Store has no
            // matching member, so the extension wins), which calls
            // StoreHandle.suspendAction — feeding the per-handle pendingErrors list
            // and the lastResult slot. The shouldBeError matcher consumes the
            // pending mark so the scope-exit guard doesn't fire.
            val ise = IllegalStateException("boom")
            val result: TransactionResult<Unit> = v.suspendAction { throw ise }
            result.shouldBeError<IllegalStateException> { assertSame(ise, exception) }
        }

    @Test
    fun lastResultIsAvailableViaAutoRegistrationWhenRouteIsExplicit() =
        storeTest {
            val v = AutoRegVault()
            // Use the explicit handle for the action so StoreHandle.action records
            // the result — Store.action (the member function) does not feed the
            // handle's lastResult slot. The auto-reg view of lastResult routes
            // through the SAME handle, so it sees the same result.
            val handle = track(v)
            val ok = handle.action { n mutate 1 }
            assertSame(ok as TransactionResult<*>, v.lastResult)

            val err = handle.action<Unit> { throw IllegalArgumentException("x") }
            v.read { } // intermediate read shouldn't disturb lastResult
            assertSame(err as TransactionResult<*>, v.lastResult)
            // Consume so the scope-exit guard doesn't fire.
            err.shouldBeError<IllegalArgumentException>()
        }
}
