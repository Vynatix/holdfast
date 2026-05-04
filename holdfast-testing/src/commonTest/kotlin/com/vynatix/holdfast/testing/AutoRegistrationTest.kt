package com.vynatix.holdfast.testing

import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.Holdfast
import com.vynatix.holdfast.testing.matcher.shouldBeError
import com.vynatix.holdfast.testing.matcher.shouldBeSuccess
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class AutoRegVault : Holdfast<AutoRegVault>() {
    val n by state { 0 }
    val label by state { "init" }
}

/** Plain user middleware — used to verify the instance overload routes correctly. */
private class AutoRegNoopMiddleware : Middleware<AutoRegVault>()

/**
 * Self-tests for the [HoldfastAutoRegistration] member-extension surface that
 * lets tests skip explicit [HoldfastTestScope.track] and call action / read /
 * timeline / etc. directly on a [Holdfast] instance.
 */
class AutoRegistrationTest {

    @Test
    fun actionAutoRegistersAndCommits() = vaultTest {
        val v = AutoRegVault()
        val result = v.action { n mutate 5 }
        result.shouldBeSuccess()
        assertEquals(5, v.read { n.value })
    }

    @Test
    fun readAutoRegistersAndReturnsBlockResult() = vaultTest {
        val v = AutoRegVault()
        // First touch via read should auto-register the vault.
        val combined = v.read { "${label.value}-${n.value}" }
        assertEquals("init-0", combined)
    }

    @Test
    fun timelineReflectsAutoRegisteredAction() = vaultTest {
        val v = AutoRegVault()
        // Trigger auto-registration FIRST via a non-conflicting extension so
        // the recorder is in place when v.action runs. Holdfast.action is a
        // member function and shadows the V.action extension — the extension
        // never auto-registers; see HoldfastAutoRegistration's file KDoc.
        v.read { } // installs the recorder via auto-registration
        v.action { n mutate 5 }.shouldBeSuccess()

        // Auto-registered handle's timeline should reflect the action.
        assertTrue(v.timeline.isNotEmpty(), "expected timeline to record events, got empty")
        assertNotNull(v.lastTransaction)
    }

    @Test
    fun emissionsAutoRegistersAndFiltersByProperty() = vaultTest {
        val v = AutoRegVault()
        v.read { } // install recorder via auto-registration before the action
        v.action { n mutate 7 }.shouldBeSuccess()

        // emissions(::prop) should work on the auto-registered handle.
        val em = v.emissions(AutoRegVault::n)
        assertEquals(1, em.size)
        assertEquals(0, em.single().oldValue)
        assertEquals(7, em.single().newValue)

        // Untouched property: empty.
        assertTrue(v.emissions(AutoRegVault::label).isEmpty())
    }

    @Test
    fun transactionsAutoRegistersAndFiltersToTransactionEvents() = vaultTest {
        val v = AutoRegVault()
        v.read { } // install recorder via auto-registration before the actions
        v.action { n mutate 1 }.shouldBeSuccess()
        v.action { n mutate 2 }.shouldBeSuccess()

        // 2 starts + 2 commits = 4 transaction events.
        val tx = v.transactions
        assertEquals(4, tx.size, "expected 4 transaction events, got $tx")
        assertEquals(2, tx.filterIsInstance<TransactionStarted>().size)
        assertEquals(2, tx.filterIsInstance<TransactionCommitted>().size)
    }

    @Test
    fun bridgeEventsAutoRegistersAndIsEmptyInV1() = vaultTest {
        val v = AutoRegVault()
        v.read { } // install recorder via auto-registration before the action
        v.action { n mutate 1 }.shouldBeSuccess()
        // No bridge attached, so this view is empty for both states.
        assertTrue(v.bridgeEvents(AutoRegVault::n).isEmpty())
        assertTrue(v.bridgeEvents(AutoRegVault::label).isEmpty())
    }

    @Test
    fun middlewareEventsOfReifiedAutoRegisters() = vaultTest {
        val v = AutoRegVault()
        v.read { } // install recorder via auto-registration before the action
        v.action { n mutate 1 }.shouldBeSuccess()

        // The recorder pushes self-events for itself, so the typed view for any
        // user class is empty in v1 (see HoldfastEvent KDoc).
        assertEquals(0, v.middlewareEventsOf<AutoRegNoopMiddleware>().size)
    }

    @Test
    fun middlewareEventsOfInstanceAutoRegistersAndReturnsEmptyForUserInstance() = vaultTest {
        val v = AutoRegVault()
        val m = AutoRegNoopMiddleware()
        v.middlewares(m)
        v.read { } // install recorder via auto-registration before the action
        v.action { n mutate 1 }.shouldBeSuccess()

        // The instance overload returns empty for user-installed middlewares —
        // matches the v1 caveat documented on HoldfastHandle.middlewareEventsOf.
        assertEquals(0, v.middlewareEventsOf(m).size)
    }

    @Test
    fun suspendActionAutoRegisters() = vaultTest {
        val v = AutoRegVault()
        val result = v.suspendAction {
            delay(0)
            n mutate 9
            n.value
        }
        result.shouldBeSuccess()
        assertEquals(9, v.read { n.value })
    }

    @Test
    fun multipleAutoRegisteringCallsShareSameHandle() = vaultTest {
        val v = AutoRegVault()
        v.read { } // install recorder via auto-registration before the actions
        // Three different extension types — all should resolve through the same
        // handle, so the timeline accumulates across them.
        v.action { n mutate 1 }.shouldBeSuccess()
        v.action { n mutate 2 }.shouldBeSuccess()
        val readBack = v.read { n.value }
        assertEquals(2, readBack)

        // Both transactions must be in the auto-registered handle's timeline.
        val tx = v.transactions
        assertEquals(4, tx.size, "expected 4 transaction events across two actions, got $tx")
    }

    @Test
    fun explicitTrackThenImplicitShareSameHandle() = vaultTest {
        val v = AutoRegVault()
        // Explicit registration first — produces a handle and installs the recorder.
        val explicit = track(v)
        assertNull(explicit.lastTransaction, "no action ran yet")

        // Holdfast.action goes through the middleware chain (recorder is installed),
        // so events are captured even though Holdfast.action shadows the extension.
        v.action { n mutate 5 }.shouldBeSuccess()

        // The explicit handle should reflect the action.
        assertNotNull(explicit.lastTransaction)
        // Auto-reg view (v.lastTransaction) routes through the same handle.
        assertSame(explicit.lastTransaction, v.lastTransaction)
        // Both views point at the same recorder buffer.
        assertEquals(explicit.timeline, v.timeline)
    }

    @Test
    fun implicitThenExplicitTrackReturnsSameHandle() = vaultTest {
        val v = AutoRegVault()
        // Auto-register first via the read extension (installs recorder).
        v.read { }
        v.action { n mutate 3 }.shouldBeSuccess()

        // Now ask for the handle explicitly — registry should be idempotent.
        val explicit = track(v)
        // The explicit handle must already see the action recorded by the recorder.
        assertNotNull(explicit.lastTransaction)
        assertEquals(3, explicit.read { n.value })
    }

    @Test
    fun explicitTrackWithCaptureNoneIsRespectedByExtensions() = vaultTest {
        val v = AutoRegVault()
        // Pre-register with Capture.None — the registry's idempotency rule means
        // a later auto-reg call reuses this handle, so the capture mode sticks.
        track(v, Capture.None)
        v.action { n mutate 4 }.shouldBeSuccess()

        // Capture.None records nothing; verify via the auto-registered view too.
        assertTrue(v.timeline.isEmpty(), "expected empty timeline for Capture.None, got ${v.timeline}")
        assertNull(v.lastTransaction)
        assertNull(v.lastResult)
        // But the read still reflects the actual mutation — Capture.None disables
        // recording, not the underlying vault.
        assertEquals(4, v.read { n.value })
    }

    @Test
    fun autoRegisteredVaultIsDisposedAtScopeExit() {
        // Capture an auto-registered vault outside the vaultTest body — after
        // teardown its handle must be detached: a fresh vaultTest body that
        // touches the SAME vault instance should produce a fresh, empty handle
        // (no leaked timeline carries over).
        val outerVault = AutoRegVault()

        vaultTest {
            outerVault.read { } // installs recorder via auto-reg
            outerVault.action { n mutate 5 }.shouldBeSuccess()
            assertTrue(outerVault.timeline.isNotEmpty())
        }

        vaultTest {
            // After the previous scope tore down, asking for the timeline should
            // start empty — a brand-new handle in a brand-new registry.
            assertTrue(outerVault.timeline.isEmpty(), "expected empty timeline post-teardown, got ${outerVault.timeline}")
            assertNull(outerVault.lastTransaction)
            assertNull(outerVault.lastResult)
        }
    }

    @Test
    fun suspendActionErrorIsRecordedInPendingErrorsViaAutoRegistration() = vaultTest {
        val v = AutoRegVault()
        // suspendAction routes through the member-extension (Holdfast has no
        // matching member, so the extension wins), which calls
        // HoldfastHandle.suspendAction — feeding the per-handle pendingErrors list
        // and the lastResult slot. The shouldBeError matcher consumes the
        // pending mark so the scope-exit guard doesn't fire.
        val ise = IllegalStateException("boom")
        val result: TransactionResult<Unit> = v.suspendAction { throw ise }
        result.shouldBeError<IllegalStateException> { assertSame(ise, exception) }
    }

    @Test
    fun lastResultIsAvailableViaAutoRegistrationWhenRouteIsExplicit() = vaultTest {
        val v = AutoRegVault()
        // Use the explicit handle for the action so HoldfastHandle.action records
        // the result — Holdfast.action (the member, the shadowing function) does
        // not feed the handle's lastResult slot. The auto-reg view of
        // lastResult routes through the SAME handle, so it sees the same
        // result.
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
