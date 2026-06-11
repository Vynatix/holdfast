package com.vynatix.holdfast

import com.vynatix.holdfast.platform.currentThreadId
import com.vynatix.holdfast.testing.concurrency.parallel
import com.vynatix.holdfast.testing.concurrency.transaction
import com.vynatix.holdfast.testing.matcher.shouldBeSuccess
import com.vynatix.holdfast.testing.vaultTest
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class ParallelVault : Store<ParallelVault>() {
    val count by state { 0 }
    val a by state { 0 }
    val b by state { 0 }
    val c by state { 0 }
    val text by state { "" }
}

private class ReentrancyVault : Store<ReentrancyVault>() {
    val count by state { 0 }
    val flag by state { "init" }
}

private class StressVault : Store<StressVault>() {
    val n by state { 0 }
    val s by state { "" }
    val list by state { listOf<Int>() }
}

private class IdentityCheckBridgeVault : Store<IdentityCheckBridgeVault>() {
    val count by state { 0 }
}

class ParallelActionTest {
    @Test
    fun concurrentReadModifyWriteActionsHaveNoLostUpdates() =
        runBlocking {
            val v = ParallelVault()
            val workers = 8
            val incrementsPerWorker = 200
            val jobs =
                List(workers) {
                    async(Dispatchers.Default) {
                        repeat(incrementsPerWorker) {
                            v action { count mutate count.value + 1 }
                        }
                    }
                }
            jobs.awaitAll()
            assertEquals(workers * incrementsPerWorker, v.count.value)
        }

    @Test
    fun concurrentMultiStateMutationsStayAtomicallyInLockstep() =
        runBlocking {
            val v = ParallelVault()
            val workers = 8
            val opsPerWorker = 100

            val jobs =
                List(workers) {
                    async(Dispatchers.Default) {
                        repeat(opsPerWorker) {
                            v action {
                                a mutate a.value + 1
                                b mutate b.value + 1
                            }
                        }
                    }
                }
            jobs.awaitAll()

            val expected = workers * opsPerWorker
            assertEquals(expected, v.a.value)
            assertEquals(expected, v.b.value)
            assertEquals(
                v.a.value,
                v.b.value,
                "a and b must stay in lockstep through concurrent transactions",
            )
        }

    @Test
    fun concurrentReadersObserveMonotonicValueUnderConcurrentWriters() =
        runBlocking {
            val v = ParallelVault()
            val readers = 4
            val writers = 4
            val opsPerWorker = 200
            val readJobs =
                List(readers) {
                    async(Dispatchers.Default) {
                        var lastSeen = 0
                        repeat(opsPerWorker) {
                            val current = v.count.value
                            assertTrue(
                                current >= lastSeen,
                                "count must be monotonic non-decreasing under writers; was $lastSeen, now $current",
                            )
                            lastSeen = current
                        }
                    }
                }
            val writeJobs =
                List(writers) {
                    async(Dispatchers.Default) {
                        repeat(opsPerWorker) {
                            v action { count mutate count.value + 1 }
                        }
                    }
                }
            (readJobs + writeJobs).awaitAll()
            assertEquals(writers * opsPerWorker, v.count.value)
        }

    @Test
    fun failingActionInOneWorkerDoesNotCorruptStateForOtherWorkers() =
        runBlocking {
            val v = ParallelVault()
            val workers = 8
            val opsPerWorker = 50

            val jobs =
                List(workers) { workerId ->
                    async(Dispatchers.Default) {
                        repeat(opsPerWorker) { i ->
                            if (workerId == 0 && i % 5 == 0) {
                                v action {
                                    count mutate count.value + 1
                                    error("worker-0 rollback")
                                }
                            } else {
                                v action { count mutate count.value + 1 }
                            }
                        }
                    }
                }
            jobs.awaitAll()

            val expected = 40 + 7 * opsPerWorker
            assertEquals(expected, v.count.value)
        }

    @Test
    fun activeTransactionIsClearedAfterEverySuccessfulAndFailedAction() =
        runBlocking {
            val v = ParallelVault()
            v action { count mutate 1 }
            assertNull(v.activeTransaction, "activeTransaction must clear after a successful action")

            v action {
                count mutate 5
                error("fail")
            }
            assertNull(v.activeTransaction, "activeTransaction must clear after a failed action")
        }

    @Test
    fun crossThreadMutateOnSameStateSerializesThroughTransactionLock() =
        runBlocking {
            val v = ParallelVault()
            val n = 1000
            val jobs =
                List(2) {
                    async(Dispatchers.Default) {
                        repeat(n) {
                            withContext(Dispatchers.Default) {
                                v action { count mutate count.value + 1 }
                            }
                        }
                    }
                }
            jobs.awaitAll()
            assertEquals(2 * n, v.count.value)
        }

    @Test
    fun parallelFailingActionsDoNotInterleaveOrLeakActiveTransaction() =
        runBlocking {
            val v = ParallelVault()
            val workers = 8
            val attempts = 100

            val jobs =
                List(workers) { workerId ->
                    async(Dispatchers.Default) {
                        var failures = 0
                        repeat(attempts) {
                            val r =
                                v action {
                                    count mutate workerId * 1000 + it
                                    error("$workerId-$it")
                                }
                            if (r is TransactionResult.Error) failures++
                        }
                        failures
                    }
                }
            val totalFailures = jobs.awaitAll().sum()

            assertEquals(workers * attempts, totalFailures)
            assertEquals(0, v.count.value, "after ${workers * attempts} failed actions, count must be 0")
            assertNull(v.activeTransaction)
        }

    @Test
    fun offThreadStateValueReadDuringActiveActionReturnsCommittedNotPending() =
        vaultTest {
            val v = track(ParallelVault())
            v.action { count mutate 5 }.shouldBeSuccess()
            val txn = transaction(on = v) { count mutate 999 }
            parallel(1) {
                assertEquals(5, v.read { count.value }, "off-owner-thread reads see committed (5), not pending (999)")
            }
            txn.commit().shouldBeSuccess()
        }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun offThreadStateValueReadIsLockFreeAndDoesNotBlockMutators() {
        val ownerCtx = newSingleThreadContext("owner-lockfree")
        val readerCtx = newSingleThreadContext("reader-lockfree")
        try {
            runBlocking {
                val v = ParallelVault()
                val mutationsCompleted = atomic(0)
                val readerActive = atomic(true)
                val targetMutations = 200

                val owner =
                    async(ownerCtx) {
                        repeat(targetMutations) {
                            v action { count mutate count.value + 1 }
                            mutationsCompleted.incrementAndGet()
                        }
                    }
                val reader =
                    async(readerCtx) {
                        while (readerActive.value) {
                            v.count.value // off-thread read; must not block the mutator
                        }
                    }

                owner.await()
                readerActive.value = false
                reader.await()

                assertEquals(targetMutations, mutationsCompleted.value)
                assertEquals(targetMutations, v.count.value)
            }
        } finally {
            ownerCtx.close()
            readerCtx.close()
        }
    }

    @Test
    fun concurrentBridgeAttachAndDetachDoesNotCorruptObserverList() =
        runBlocking {
            val v = IdentityCheckBridgeVault()
            val workers = 8
            val opsPerWorker = 50

            val jobs =
                List(workers) { workerId ->
                    async(Dispatchers.Default) {
                        repeat(opsPerWorker) {
                            when (workerId % 4) {
                                0 -> {
                                    val bridge =
                                        object : Bridge<Int> {
                                            override fun observe(observer: (Int) -> Unit): Disposable = Disposable { /* noop */ }

                                            override fun publish(value: Int): Boolean = true
                                        }
                                    v { count bridge bridge }
                                }
                                1 -> v action { count mutate it }
                                2 -> v.count.value
                                3 -> v.activeTransaction
                            }
                        }
                    }
                }
            jobs.awaitAll()

            // Final mutation works without error
            v action { count mutate -1 }
            assertEquals(-1, v.count.value)
        }

    @Test
    fun parallelActionsOnDifferentVaultsRunFullyInParallel() =
        runBlocking {
            val vaults = List(4) { ParallelVault() }
            val opsPerVault = 500

            val jobs =
                vaults.map { v ->
                    async(Dispatchers.Default) {
                        repeat(opsPerVault) {
                            v action { count mutate count.value + 1 }
                        }
                    }
                }
            jobs.awaitAll()

            vaults.forEach { v ->
                assertEquals(opsPerVault, v.count.value)
            }
        }

    @Test
    fun concurrentRemoveStateAndMutateBehavesPredictably() =
        runBlocking {
            val v = ParallelVault()
            val workers = 6
            val opsPerWorker = 100
            val crashed = atomic(false)

            val jobs =
                List(workers) { workerId ->
                    async(Dispatchers.Default) {
                        try {
                            repeat(opsPerWorker) {
                                when (workerId % 3) {
                                    0 -> runCatching { v.removeState("count") }
                                    1 -> runCatching { v action { count mutate it } }
                                    2 -> runCatching { v.count.value }
                                }
                            }
                        } catch (e: Throwable) {
                            crashed.value = true
                            throw e
                        }
                    }
                }
            jobs.awaitAll()

            assertEquals(false, crashed.value, "no crash under concurrent remove/mutate/read")
            // Final state is some int; just verify the store still works
            v action { count mutate 0 }
            assertEquals(0, v.count.value)
        }
}

class ObserverDeadlockTest {
    /**
     * Originally a regression for a lock-order hazard: observe() acquired
     * observersLock→stateLock while updateState() acquired stateLock→observersLock.
     * The fix uses a snapshot-then-notify pattern so neither path nests both locks.
     */
    @Test
    fun concurrentObserveAndMutateDoNotDeadlock() {
        val finished =
            runBlocking {
                val v = ReentrancyVault()
                val iterations = 1000
                withTimeoutOrNull(15_000) {
                    repeat(iterations) {
                        val subscribers =
                            List(4) {
                                async(Dispatchers.Default) {
                                    val d = v { count effect { /* observe noop */ } }
                                    d.dispose()
                                }
                            }
                        val mutators =
                            List(4) {
                                async(Dispatchers.Default) {
                                    v action { count mutate count.value + 1 }
                                }
                            }
                        (subscribers + mutators).awaitAll()
                    }
                    true
                }
            }
        assertNotNull(finished, "deadlock under concurrent observe + mutate; lock-order regression")
    }

    @Test
    fun stressedObserveAndMutateStormDoesNotDeadlock() {
        val finished =
            runBlocking {
                val v = ReentrancyVault()
                val warmup = v { count effect { /* warmup */ } }
                val result =
                    withTimeoutOrNull(15_000) {
                        val gate = CompletableDeferred<Unit>()
                        val jobs =
                            List(16) { workerId ->
                                async(Dispatchers.Default) {
                                    gate.await()
                                    repeat(200) {
                                        if (workerId % 2 == 0) {
                                            val d = v { count effect { /* noop */ } }
                                            d.dispose()
                                        } else {
                                            v action { count mutate count.value + 1 }
                                        }
                                    }
                                }
                            }
                        gate.complete(Unit)
                        jobs.awaitAll()
                        true
                    }
                warmup.dispose()
                result
            }
        assertNotNull(finished, "deadlock under simultaneous observe/mutate storm")
    }

    @Test
    fun concurrentObserveDisposeAndMutateDoNotDeadlock() {
        val finished =
            runBlocking {
                val v = ReentrancyVault()
                withTimeoutOrNull(15_000) {
                    val jobs =
                        List(12) { workerId ->
                            async(Dispatchers.Default) {
                                repeat(300) {
                                    when (workerId % 3) {
                                        0 -> {
                                            val d = v { count effect { /* noop */ } }
                                            d.dispose()
                                        }
                                        1 -> v action { count mutate count.value + 1 }
                                        2 -> {
                                            val d = v { count effect { /* noop */ } }
                                            // Hold briefly, then dispose.
                                            delay(1)
                                            d.dispose()
                                        }
                                    }
                                }
                            }
                        }
                    jobs.awaitAll()
                    true
                }
            }
        assertNotNull(finished, "deadlock under observe/dispose/mutate trio")
    }
}

class SameThreadReentrancyTest {
    @Test
    fun sameThreadVaultInvokeInsideActionDoesNotDeadlock() {
        val v = ReentrancyVault()
        v action {
            count mutate 1
            this@action {
                count mutate count.value + 10
            }
        }
        assertEquals(11, v.count.value)
    }

    @Test
    fun stateValueReadInsideEffectCallbackDoesNotDeadlock() {
        val v = ReentrancyVault()
        var seenInsideEffect: Int? = null
        val disposable =
            v {
                count effect {
                    seenInsideEffect = v { count.value }
                }
            }
        v action { count mutate 5 }
        assertEquals(5, seenInsideEffect)
        disposable.dispose()
    }

    @Test
    fun mutateInsideEffectCallbackTriggersImplicitNestedTransactionWithoutDeadlock() {
        val v = ReentrancyVault()
        // Subscribing to one state, then mutating ANOTHER state from inside the
        // observer callback. This is the implicit-nested-transaction scenario.
        val v2 = ReentrancyVault()
        val v2Seen = mutableListOf<Int>()
        val d2 = v2 { count effect { v2Seen.add(this) } }
        v2Seen.clear()

        val d1 =
            v {
                count effect {
                    if (this == 5) v2 action { count mutate this@effect }
                }
            }

        v action { count mutate 5 }

        assertEquals(5, v2.count.value, "subscriber's nested action on v2 succeeded")
        assertTrue(5 in v2Seen)
        d1.dispose()
        d2.dispose()
    }

    @Test
    fun recursiveActionFromInsideMiddlewareDoesNotDeadlock() {
        // Middleware that, in onTransactionStarted, invokes an action on a different
        // store. Reentrant on the OUTER store's transactionLock isn't even tested —
        // we want to verify a middleware can invoke its own work without crashing.
        val v1 = ReentrancyVault()
        val v2 = ReentrancyVault()
        v1.middlewares(
            object : Middleware<ReentrancyVault>() {
                override fun onTransactionStarted(context: MiddlewareContext<ReentrancyVault>) {
                    v2 action { count mutate 99 }
                }
            },
        )

        v1 action { count mutate 1 }
        assertEquals(1, v1.count.value)
        assertEquals(99, v2.count.value, "middleware's nested action on different store committed")
    }
}

class PlatformThreadIdentityTest {
    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun currentThreadIdReturnsDistinctValuesForDistinctOsThreads() {
        val threadAReady = atomic(false)
        val threadBReady = atomic(false)
        val idA = atomic(0L)
        val idB = atomic(0L)
        val ctxA = newSingleThreadContext("identity-a")
        val ctxB = newSingleThreadContext("identity-b")
        try {
            runBlocking {
                val a =
                    async(ctxA) {
                        idA.value = currentThreadId()
                        threadAReady.value = true
                        while (!threadBReady.value) { /* spin */ }
                    }
                val b =
                    async(ctxB) {
                        while (!threadAReady.value) { /* spin */ }
                        idB.value = currentThreadId()
                        threadBReady.value = true
                    }
                a.await()
                b.await()
            }
            assertTrue(
                idA.value != 0L && idB.value != 0L,
                "thread ids must be non-zero; idA=${idA.value}, idB=${idB.value}",
            )
            assertTrue(
                idA.value != idB.value,
                "different OS threads must have different ids; both got ${idA.value}",
            )
        } finally {
            ctxA.close()
            ctxB.close()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun currentThreadIdReturnsConsistentValueForRepeatedCallsOnSameThread() {
        val ctx = newSingleThreadContext("identity-stable")
        try {
            runBlocking {
                val ids =
                    async(ctx) {
                        List(50) { currentThreadId() }
                    }.await()
                assertEquals(1, ids.toSet().size, "same thread must have stable id; ids=${ids.toSet()}")
                assertTrue(ids.first() != 0L)
            }
        } finally {
            ctx.close()
        }
    }
}

class StressTest {
    @Test
    fun tenThousandActionsAcrossEightWorkersAllAccountedFor() =
        runBlocking {
            val v = StressVault()
            val workers = 8
            val opsPerWorker = 1250 // 8 × 1250 = 10 000 ops
            val jobs =
                List(workers) {
                    async(Dispatchers.Default) {
                        repeat(opsPerWorker) {
                            v action { n mutate n.value + 1 }
                        }
                    }
                }
            jobs.awaitAll()
            assertEquals(workers * opsPerWorker, v.n.value)
        }

    @Test
    fun oneHundredThousandActionsAcrossSixteenWorkersStayConsistent() =
        runBlocking {
            val v = StressVault()
            val workers = 16
            val opsPerWorker = 6250 // 16 × 6250 = 100 000 ops
            val finished =
                withTimeoutOrNull(180_000) {
                    val jobs =
                        List(workers) {
                            async(Dispatchers.Default) {
                                repeat(opsPerWorker) {
                                    v action { n mutate n.value + 1 }
                                }
                            }
                        }
                    jobs.awaitAll()
                    v.n.value
                }
            assertNotNull(finished, "100k actions did not complete within 3 min")
            assertEquals(workers * opsPerWorker, finished)
        }

    @Test
    fun randomMixOfMutationsRollbacksAndReadsLeavesStateConsistent() =
        runBlocking {
            val v = StressVault()
            val workers = 8
            val opsPerWorker = 500
            val rng = Random(42) // deterministic seed for reproducibility

            val jobs =
                List(workers) {
                    async(Dispatchers.Default) {
                        repeat(opsPerWorker) {
                            val choice = rng.nextInt(0, 4)
                            when (choice) {
                                0 -> v action { n mutate n.value + 1 }
                                1 ->
                                    v action {
                                        n mutate n.value + 1
                                        error("rollback")
                                    }
                                2 -> v.n.value
                                3 ->
                                    v action {
                                        n mutate n.value + 1
                                        s mutate "step:${n.value}"
                                    }
                            }
                        }
                    }
                }
            jobs.awaitAll()
            // No exact assertion on final value (random mix), but store must still work.
            v action { n mutate -1 }
            assertEquals(-1, v.n.value)
        }

    @Test
    fun manyShortLivedSubscribersOnHighFrequencyMutationsDoNotLeakOrDeadlock() =
        runBlocking {
            val v = StressVault()
            val finished =
                withTimeoutOrNull(30_000) {
                    val mutators =
                        List(2) {
                            async(Dispatchers.Default) {
                                repeat(2_000) {
                                    v action { n mutate n.value + 1 }
                                }
                            }
                        }
                    val subscribers =
                        List(8) {
                            async(Dispatchers.Default) {
                                repeat(500) {
                                    val d = v { n effect { /* noop */ } }
                                    d.dispose()
                                }
                            }
                        }
                    (mutators + subscribers).awaitAll()
                    true
                }
            assertNotNull(finished, "deadlock or hang under high-frequency mutate + short-lived subscribers")
            assertEquals(2 * 2_000, v.n.value, "mutators completed all increments")
        }

    @Test
    fun multiStateMultiVaultStressDoesNotInterleave() =
        runBlocking {
            val vaults = List(4) { StressVault() }
            val workers = 16
            val opsPerWorker = 250

            val jobs =
                List(workers) { workerId ->
                    async(Dispatchers.Default) {
                        val v = vaults[workerId % vaults.size]
                        repeat(opsPerWorker) {
                            v action {
                                n mutate n.value + 1
                                s mutate "w$workerId-${n.value}"
                            }
                        }
                    }
                }
            jobs.awaitAll()

            // Each store was hit by 4 workers × 250 ops = 1000 ops
            vaults.forEach { v ->
                assertEquals(1000, v.n.value)
            }
        }

    @Test
    fun mixedMiddlewareTransformerEffectStressOnSingleVaultStaysConsistent() =
        runBlocking {
            val v = StressVault()
            val mwInvocations = atomic(0)
            v.middlewares(
                object : Middleware<StressVault>() {
                    override fun onTransactionStarted(context: MiddlewareContext<StressVault>) {
                        mwInvocations.incrementAndGet()
                    }
                },
            )
            val effectInvocations = atomic(0)
            val d = v { n effect { effectInvocations.incrementAndGet() } }

            val workers = 8
            val opsPerWorker = 500

            val jobs =
                List(workers) {
                    async(Dispatchers.Default) {
                        repeat(opsPerWorker) {
                            v action { n mutate n.value + 1 }
                        }
                    }
                }
            jobs.awaitAll()
            d.dispose()

            val totalOps = workers * opsPerWorker
            assertEquals(totalOps, v.n.value)
            assertEquals(totalOps, mwInvocations.value, "middleware fired exactly once per action")
            // Effect: 1 initial + N successful commits
            assertEquals(totalOps + 1, effectInvocations.value)
        }
}
