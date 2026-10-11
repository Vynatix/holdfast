@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Clock
import kotlin.time.Instant

/** A clock that always reads [instant] — the test clock R10 asks for. */
private class ClockTestFixedClock(
    private val instant: Instant,
) : Clock {
    override fun now(): Instant = instant
}

/** Stamps time inside an action, and seeds a state from the clock. */
private class ClockStampStore : Store<ClockStampStore>() {
    val stampedAt by state { Instant.DISTANT_PAST }
    val createdAt by state { clock.now() }

    fun stamp() = action { stampedAt mutate clock.now() }
}

/** Per-store getter override (resolution level 1) — must beat any bound clock. */
private class ClockOverrideStore(
    private val pinned: Clock,
) : Store<ClockOverrideStore>() {
    override val clock: Clock get() = pinned

    val createdAt by state { clock.now() }
}

/**
 * Issue #20 R10 — time as an input. `Store.clock` resolves per-store override →
 * `bindClock` binding → `Clock.System`, mirroring `scope`/`bindToScope`.
 */
class StoreClockTest {
    private val t1 = Instant.fromEpochMilliseconds(1_700_000_000_000)
    private val t2 = Instant.fromEpochMilliseconds(1_800_000_000_000)

    @Test
    fun unboundStoreReadsTheSystemClock() {
        val store = ClockStampStore()
        assertSame(Clock.System, store.clock)
    }

    @Test
    fun boundFixedClockMakesClockReadsInsideAnActionDeterministic() {
        // R10 acceptance: a store reading clock.now() inside an action is
        // deterministic under a fixed test clock.
        val store = ClockStampStore()
        store.bindClock(ClockTestFixedClock(t1))

        store.stamp().getOrThrow()
        assertEquals(t1, store.stampedAt.value)

        store action { stampedAt mutate Instant.DISTANT_PAST }
        store.stamp().getOrThrow()
        assertEquals(t1, store.stampedAt.value, "every read of the fixed clock is the same instant")
    }

    @Test
    fun rebindingReplacesTheBoundClock() {
        val store = ClockStampStore()
        store.bindClock(ClockTestFixedClock(t1))
        store.bindClock(ClockTestFixedClock(t2))
        store.stamp().getOrThrow()
        assertEquals(t2, store.stampedAt.value)
    }

    @Test
    fun bindingNullRestoresTheSystemClock() {
        val store = ClockStampStore()
        val fixed = ClockTestFixedClock(t1)
        store.bindClock(fixed)
        assertSame(fixed, store.clock)
        store.bindClock(null)
        assertSame(Clock.System, store.clock)
    }

    @Test
    fun getterOverrideBeatsBindClock() {
        val pinned = ClockTestFixedClock(t1)
        val store = ClockOverrideStore(pinned)
        store.bindClock(ClockTestFixedClock(t2))
        assertSame(pinned, store.clock)
        assertEquals(t1, store.createdAt.value, "an initializer resolves clock through the override too")
    }

    @Test
    fun initializerReadsTheClockBoundAtItsFirstRead() {
        val store = ClockStampStore()
        store.bindClock(ClockTestFixedClock(t1))
        // createdAt's initializer has not run yet: it runs on this first read.
        assertEquals(t1, store.createdAt.value)

        // A materialized state keeps its value: rebinding never re-runs the initializer.
        store.bindClock(ClockTestFixedClock(t2))
        assertEquals(t1, store.createdAt.value)
    }

    @Test
    fun twoStoresHaveIndependentBindings() {
        val a = ClockStampStore()
        val b = ClockStampStore()
        a.bindClock(ClockTestFixedClock(t1))
        b.bindClock(ClockTestFixedClock(t2))
        assertEquals(t1, a.clock.now())
        assertEquals(t2, b.clock.now())
    }

    @Test
    @OptIn(StoreInternalApi::class)
    fun internalBoundClockExposesTheRawBindingNotTheOverride() {
        val plain = ClockStampStore()
        assertNull(plain.internalBoundClock)
        val fixed = ClockTestFixedClock(t1)
        plain.bindClock(fixed)
        assertSame(fixed, plain.internalBoundClock)

        val overridden = ClockOverrideStore(ClockTestFixedClock(t2))
        assertNull(overridden.internalBoundClock, "an override is not a binding")
    }

    @Test
    fun disposeKeepsTheBoundClock() {
        // That bindClock throws and reading clock does not, once disposed, is
        // pinned by DisposedEntrypointTest; this pins the value that survives.
        val store = ClockStampStore()
        val fixed = ClockTestFixedClock(t1)
        store.bindClock(fixed)
        store.dispose()
        assertSame(fixed, store.clock)
    }
}
