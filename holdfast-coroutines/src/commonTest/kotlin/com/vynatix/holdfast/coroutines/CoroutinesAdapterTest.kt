package com.vynatix.vault.coroutines

import com.vynatix.vault.Vault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class FlowVault : Vault<FlowVault>() {
    val n by state { 0 }
    val s by state { "init" }
}

class AsFlowTest {

    @Test fun asFlowEmitsCurrentValueImmediately() = runBlocking {
        val v = FlowVault()
        v action { n mutate 42 }
        val first = v.n.asFlow().first()
        assertEquals(42, first)
    }

    @Test fun asFlowDeliversCommittedValuesWithBreathingRoom() = runBlocking {
        // Under the 2.0 lossless-conflated contract, asFlow guarantees the
        // latest value is always delivered; intermediate values may be
        // conflated under fast-emit / slow-collect. With small per-action
        // breathing room the collector keeps up, so all 4 values land in
        // order. See AsFlowLosslessConflatedTest for the contract under
        // contention.
        val v = FlowVault()
        val collector = async {
            v.n.asFlow().take(4).toList()
        }
        delay(50) // collector subscribes; replay slot delivers 0
        v action { n mutate 1 }
        delay(30)
        v action { n mutate 2 }
        delay(30)
        v action { n mutate 3 }
        val result = collector.await()
        assertEquals(listOf(0, 1, 2, 3), result)
    }

    @Test fun asFlowDisposesObserverOnCollectorCancel() = runBlocking {
        val v = FlowVault()
        val seen = mutableListOf<Int>()
        val job = launch {
            v.n.asFlow().toList(seen) // collects forever until cancelled
        }
        delay(50)
        v action { n mutate 1 }
        delay(50)
        job.cancel()
        delay(50)
        // After cancellation, mutations should not crash and should not be observed.
        val seenSnapshot = seen.toList()
        v action { n mutate 100 }
        assertEquals(100, v.n.value)
        // The collector saw initial 0 and the post-subscribe value 1, but not 100.
        assertTrue(0 in seenSnapshot)
        assertTrue(1 in seenSnapshot)
        assertTrue(100 !in seen, "no further values after cancel; cleanup ran")
    }
}

class AsStateFlowTest {

    @Test fun asStateFlowExposesCurrentAndSubsequentValues() = runBlocking {
        val v = FlowVault()
        v action { n mutate 5 }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            // Eagerly subscribed: upstream stays connected for the scope's lifetime,
            // which is what we want for a hot StateFlow that downstream code can poll.
            val sf = v.n.asStateFlow(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly)
            // Wait for the initial value to propagate.
            withTimeout(1000) { while (sf.value != 5) delay(10) }
            v action { n mutate 7 }
            withTimeout(1000) { while (sf.value != 7) delay(10) }
            assertEquals(7, sf.value)
        } finally {
            scope.cancel()
        }
    }
}

class FirstAndAwaitValueTest {

    @Test fun firstSuspendsUntilPredicateHolds() = runBlocking {
        val v = FlowVault()
        val collector = async {
            v.n.first { it >= 3 }
        }
        delay(50)
        v action { n mutate 1 }
        v action { n mutate 2 }
        v action { n mutate 3 }
        val matched = collector.await()
        assertTrue(matched >= 3)
        assertEquals(3, matched)
    }

    @Test fun awaitValueSuspendsUntilEquality() = runBlocking {
        val v = FlowVault()
        val collector = async {
            v.s.awaitValue("done")
        }
        delay(50)
        v action { s mutate "in-progress" }
        v action { s mutate "done" }
        assertEquals("done", collector.await())
    }
}
