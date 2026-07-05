package com.vynatix.holdfast

import kotlinx.atomicfu.atomic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private class CountingInitVault(
    private val onInit: () -> Unit,
) : Store<CountingInitVault>() {
    val n by state {
        onInit()
        0
    }
}

private class ThrowingInitVault : Store<ThrowingInitVault>() {
    val n by state<Int> { error("initializer fails") }
}

private class CrossReferenceInitVault : Store<CrossReferenceInitVault>() {
    val seed by state { 5 }
    val derived by state { seed.value * 2 }
}

private class ParallelInitVault : Store<ParallelInitVault>() {
    val callCount = atomic(0)
    val n by state {
        callCount.incrementAndGet()
        0
    }
}

/**
 * Eager registration contract (P1-lazy-registration): `by state { … }` registers
 * its backing state at construction via `provideDelegate`, running the
 * initializer once, in declaration order. A throwing initializer therefore fails
 * at construction (not lazily on first read), and there is no retry-on-re-read.
 */
class InitializerTest {
    @Test
    fun initializerIsCalledAtConstruction() {
        var calls = 0
        val v = CountingInitVault { calls++ }
        assertEquals(1, calls, "eager registration invokes the initializer at construction")
        v.n
        v.n
        assertEquals(1, calls, "subsequent accesses reuse the registered state")
    }

    @Test
    fun initializerIsCalledExactlyOncePerVaultInstance() {
        var calls = 0
        val v = CountingInitVault { calls++ }
        v.n
        v action { n mutate 5 }
        v.n
        v.n.value
        assertEquals(1, calls, "registered once at construction; accesses reuse the state")
    }

    @Test
    fun initializerThrowingPropagatesAtConstruction() {
        val ex = assertFailsWith<IllegalStateException> { ThrowingInitVault() }
        assertEquals("initializer fails", ex.message)
    }

    @Test
    fun initializerLambdaSeesEarlierDeclaredPropertiesAtConstruction() {
        // Declaration order matters under eager registration: `seed` is declared
        // before `derived`, so it is registered first and `derived`'s initializer
        // reads it successfully at construction.
        val v = CrossReferenceInitVault()
        assertEquals(10, v.derived.value)
    }

    @Test
    fun parallelAccessAfterConstructionSeesTheSingleRegisteredState() {
        val v = ParallelInitVault()
        // Registered eagerly at construction (single-threaded), so concurrent reads
        // never re-run the initializer.
        assertEquals(1, v.callCount.value)
        repeat(16) { v.n }
        assertEquals(1, v.callCount.value)
    }
}
