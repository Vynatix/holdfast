package com.vynatix.holdfast

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
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

private class FlakeyInitVault : Store<FlakeyInitVault>() {
    var attemptCount = 0
    val n by state {
        attemptCount++
        if (attemptCount == 1) error("first attempt fails")
        42
    }
}

private class TwiceFlakeyInitVault : Store<TwiceFlakeyInitVault>() {
    var attemptCount = 0
    val n by state {
        attemptCount++
        if (attemptCount <= 2) error("attempt $attemptCount fails")
        7
    }
}

private class DifferentExceptionInitVault : Store<DifferentExceptionInitVault>() {
    var attemptCount = 0
    val n by state {
        attemptCount++
        when (attemptCount) {
            1 -> throw IllegalArgumentException("wrong-1")
            2 -> throw IllegalStateException("wrong-2")
            else -> 99
        }
    }
}

private class SideEffectInitVault : Store<SideEffectInitVault>() {
    val effects = mutableListOf<Int>()
    var attemptCount = 0
    val n by state {
        attemptCount++
        effects.add(attemptCount)
        if (attemptCount <= 2) error("attempt $attemptCount fails")
        100
    }
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

class InitializerTest {
    @Test
    fun initializerIsNotCalledUntilFirstStateAccess() {
        var calls = 0
        val v = CountingInitVault { calls++ }
        assertEquals(0, calls, "constructor must not invoke initializer")
        v.n
        assertEquals(1, calls, "first access invokes initializer")
    }

    @Test
    fun initializerIsCalledExactlyOncePerVaultInstance() {
        var calls = 0
        val v = CountingInitVault { calls++ }
        v.n
        v.n
        v action { n mutate 5 }
        v.n
        v.n.value
        assertEquals(1, calls, "subsequent accesses reuse the registered state")
    }

    @Test
    fun initializerThrowingOnFirstAccessPropagatesException() {
        val v = ThrowingInitVault()
        val ex = assertFailsWith<IllegalStateException> { v.n }
        assertEquals("initializer fails", ex.message)
    }

    @Test
    fun initializerThrowingOnceThenSucceedingOnRetryRegistersStateOnSecondAttempt() {
        val v = FlakeyInitVault()
        assertFailsWith<IllegalStateException> { v.n }
        assertEquals(42, v.n.value, "second attempt succeeds and value is the initializer's return")
        assertEquals(2, v.attemptCount, "initializer ran exactly twice")
    }

    @Test
    fun initializerThrowingTwiceThenSucceedingRegistersStateOnThirdAttempt() {
        val v = TwiceFlakeyInitVault()
        assertFailsWith<IllegalStateException> { v.n }
        assertFailsWith<IllegalStateException> { v.n }
        assertEquals(7, v.n.value)
        assertEquals(3, v.attemptCount)
    }

    @Test
    fun initializerThrowingWithDifferentExceptionEachAttemptPropagatesEachException() {
        val v = DifferentExceptionInitVault()
        assertFailsWith<IllegalArgumentException> { v.n }
        assertFailsWith<IllegalStateException> { v.n }
        assertEquals(99, v.n.value)
        assertEquals(3, v.attemptCount)
    }

    @Test
    fun initializerWithSideEffectCounterRunsExactlyAsManyTimesAsRetries() {
        val v = SideEffectInitVault()
        assertFailsWith<IllegalStateException> { v.n }
        assertFailsWith<IllegalStateException> { v.n }
        assertEquals(100, v.n.value)
        assertEquals(listOf(1, 2, 3), v.effects, "side-effect counter ran once per attempt")
        assertEquals(3, v.attemptCount)
    }

    @Test
    fun initializerLambdaSeesEnclosingVaultPropertiesAtCallTime() {
        val v = CrossReferenceInitVault()
        assertEquals(10, v.derived.value, "derived initializer reads seed.value lazily at first access")
    }

    @Test
    fun parallelFirstAccessOnSameStateInvokesInitializerAtMostOnce() =
        runBlocking {
            val v = ParallelInitVault()
            val workers = 16
            val gate = CompletableDeferred<Unit>()
            val jobs =
                List(workers) {
                    async(Dispatchers.Default) {
                        gate.await()
                        v.n
                    }
                }
            gate.complete(Unit)
            jobs.awaitAll()

            assertEquals(
                1,
                v.callCount.value,
                "propertiesLock must serialize first-access; initializer ran once across $workers parallel readers",
            )
        }
}
