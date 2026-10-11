@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.testing

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.testing.internal.PrivilegedHooks
import kotlinx.coroutines.launch
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * A process-wide singleton store: it outlives every `storeTest` block, which is
 * exactly how a clock bound in one test could leak into the next.
 */
private object ClockSingletonStore : Store<ClockSingletonStore>() {
    val n by state { 0 }
}

private class ClockTeardownStore : Store<ClockTeardownStore>() {
    val n by state { 0 }
}

/** Resolution level 1: a getter override is not a binding, so teardown must not record it as one. */
private class OverrideClockTeardownStore(
    private val own: Clock,
) : Store<OverrideClockTeardownStore>() {
    override val clock: Clock get() = own
}

private class TeardownFixedClock(
    private val instant: Instant,
) : Clock {
    override fun now(): Instant = instant
}

/**
 * `storeTest` teardown restores every tracked store's clock binding
 * (`Store.bindClock`) to what it was when the store was first tracked (#20 R10).
 *
 * Each test runs a whole `storeTest` block and asserts after it returns: on the
 * harness's targets (JVM, Android host, iOS) `runTest` blocks until the block, its
 * teardown and every child coroutine the block left un-joined finish.
 *
 * Restoration is checked on the raw binding (`PrivilegedHooks.boundClock`) as well
 * as on `clock`: `clock` reads the same for "unbound" and "bound to `Clock.System`",
 * and teardown must put back the former.
 */
class StoreClockTeardownTest {
    private val fixed = TeardownFixedClock(Instant.fromEpochMilliseconds(1_700_000_000_000))

    @AfterTest
    fun unbindTheSingleton() {
        // Hygiene for the process-wide store, whatever a failed assertion left behind.
        ClockSingletonStore.bindClock(null)
    }

    @Test
    fun clockBoundOnATrackedStoreIsRestoredAfterTheScope() {
        storeTest {
            track(ClockSingletonStore)
            ClockSingletonStore.bindClock(fixed)
            assertSame(fixed, ClockSingletonStore.clock)
        }
        assertSame(Clock.System, ClockSingletonStore.clock, "the test's clock leaked past teardown")
        assertUnbound(ClockSingletonStore)
    }

    @Test
    fun clockBoundOnAnAutoTrackedStoreIsRestoredAfterTheScope() {
        storeTest {
            ClockSingletonStore.read { n.value } // auto-tracks
            ClockSingletonStore.bindClock(fixed)
        }
        assertSame(Clock.System, ClockSingletonStore.clock)
        assertUnbound(ClockSingletonStore)
    }

    @Test
    fun clockBoundBeforeTheTestIsKept() {
        val appClock = TeardownFixedClock(Instant.fromEpochMilliseconds(42))
        ClockSingletonStore.bindClock(appClock)
        storeTest {
            track(ClockSingletonStore)
            ClockSingletonStore.bindClock(fixed)
            assertSame(fixed, ClockSingletonStore.clock)
        }
        assertSame(appClock, ClockSingletonStore.clock, "teardown must restore the pre-test binding, not unbind")
        assertSame(appClock, PrivilegedHooks.boundClock(ClockSingletonStore))

        storeTest {
            track(ClockSingletonStore)
            ClockSingletonStore.bindClock(null)
            assertSame(Clock.System, ClockSingletonStore.clock)
        }
        assertSame(appClock, ClockSingletonStore.clock, "an unbind inside the test is restored too")
        assertSame(appClock, PrivilegedHooks.boundClock(ClockSingletonStore))
    }

    @Test
    fun clockBoundBeforeTheFirstTrackInsideTheTestIsKept() {
        // Pins the documented limit: tracking records the binding in force at
        // that moment as the pre-test one, so bind AFTER tracking.
        storeTest {
            ClockSingletonStore.bindClock(fixed)
            track(ClockSingletonStore)
        }
        assertSame(fixed, PrivilegedHooks.boundClock(ClockSingletonStore))
    }

    @Test
    fun clockIsRestoredWhenTheBodyFails() {
        assertFailsWith<IllegalStateException> {
            storeTest {
                track(ClockSingletonStore)
                ClockSingletonStore.bindClock(fixed)
                error("body failed")
            }
        }
        assertSame(Clock.System, ClockSingletonStore.clock)
        assertUnbound(ClockSingletonStore)
    }

    @Test
    fun clockBoundByAnUnjoinedChildIsRestored() {
        storeTest {
            track(ClockSingletonStore)
            // Queued, not run: runTest runs it after the body and its tearDown.
            launch { ClockSingletonStore.bindClock(fixed) }
        }
        assertSame(Clock.System, ClockSingletonStore.clock, "a late child's clock leaked past teardown")
        assertUnbound(ClockSingletonStore)
    }

    @Test
    fun clockBoundByAnUnjoinedChildThatTracksTheStoreIsRestored() {
        // A fresh store: the handle this child creates after tearDown keeps its
        // recorder installed, which must not reach the shared singleton.
        val store = ClockTeardownStore()
        storeTest {
            launch {
                track(store)
                store.bindClock(fixed)
            }
        }
        assertSame(Clock.System, store.clock)
        assertUnbound(store)
    }

    @Test
    fun anOverrideIsNotRecordedAsABinding() {
        val store = OverrideClockTeardownStore(fixed)
        storeTest { track(store) }
        assertNull(PrivilegedHooks.boundClock(store), "teardown pinned the override's clock as a binding")
        assertSame(fixed, store.clock)
    }

    @Test
    fun aStoreDisposedInsideTheTestDoesNotBreakTeardown() {
        val store = ClockTeardownStore()
        storeTest {
            track(store)
            store.bindClock(fixed)
            store.dispose()
        }
        // Skipped, not thrown: a disposed store rejects bindClock and has no next test.
        assertSame(fixed, store.clock)
    }

    private fun assertUnbound(store: Store<*>) {
        assertNull(PrivilegedHooks.boundClock(store), "teardown must restore the raw binding (unbound), not bind Clock.System")
    }
}
