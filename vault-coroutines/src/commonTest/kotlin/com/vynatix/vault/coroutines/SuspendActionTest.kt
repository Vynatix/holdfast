package com.vynatix.vault.coroutines

import com.vynatix.vault.TransactionResult
import com.vynatix.vault.TransactionStatus
import com.vynatix.vault.Vault
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class SuspendVault : Vault<SuspendVault>() {
    val n by state { 0 }
    val s by state { "init" }
}

class SuspendActionBasicsTest {

    @Test fun suspendActionRunsBodyAndReturnsValue() = runBlocking {
        val v = SuspendVault()
        val r = v.suspendAction {
            n mutate 5
            "computed: ${n.value}"
        }
        assertIs<TransactionResult.Success<String>>(r)
        assertEquals("computed: 5", r.value)
        assertEquals(5, v.n.value)
    }

    @Test fun suspendActionCommitsMultiStateAtomically() = runBlocking {
        val v = SuspendVault()
        val r = v.suspendAction {
            n mutate 10
            s mutate "ten"
        }
        assertIs<TransactionResult.Success<*>>(r)
        assertEquals(10, v.n.value)
        assertEquals("ten", v.s.value)
    }

    @Test fun suspendActionThrowsRollbackPreservesState() = runBlocking {
        val v = SuspendVault()
        v.suspendAction {
            n mutate 1
            s mutate "before"
        }
        val r = v.suspendAction {
            n mutate 99
            s mutate "during"
            error("simulated")
        }
        assertIs<TransactionResult.Error>(r)
        assertEquals(TransactionStatus.RolledBack, r.transaction.status)
        assertEquals(1, v.n.value, "rollback restored prior n")
        assertEquals("before", v.s.value, "rollback restored prior s")
    }

    @Test fun suspendActionBodyMaySuspendBetweenMutations() = runBlocking {
        val v = SuspendVault()
        val r = v.suspendAction {
            n mutate 1
            delay(20) // suspend
            n mutate n.value + 10 // resume; reads-your-own-writes
            delay(20) // suspend
            n mutate n.value * 2 // resume
        }
        assertIs<TransactionResult.Success<*>>(r)
        // 0 → 1 → 11 → 22
        assertEquals(22, v.n.value)
    }

    @Test fun suspendActionFiresObserversOnceOnCommit() = runBlocking {
        val v = SuspendVault()
        val seen = mutableListOf<Int>()
        val sub = v { n effect { seen.add(this) } }
        seen.clear()
        v.suspendAction {
            n mutate 1
            delay(10)
            n mutate 2
            delay(10)
            n mutate 3
        }
        assertEquals(listOf(3), seen, "observer fires once with the final committed value")
        sub.dispose()
    }
}

class SuspendActionMutualExclusionTest {

    @Test fun suspendActionAndBlockingActionSerializeOnSameVault() = runBlocking {
        val v = SuspendVault()
        // Suspending action that holds the lock through a delay; blocking
        // action launched concurrently must wait, not interleave.
        val gate = CompletableDeferred<Unit>()
        val sentinel = mutableListOf<String>()

        val suspendingJob = async(Dispatchers.Default) {
            v.suspendAction {
                sentinel.add("susp-start")
                gate.complete(Unit)
                delay(80)
                n mutate 1
                sentinel.add("susp-end")
            }
        }
        gate.await()
        // Now launch a blocking action — it should NOT interleave with the suspending one.
        val blockingJob = async(Dispatchers.Default) {
            v action {
                sentinel.add("block-start")
                n mutate (n.value + 100)
                sentinel.add("block-end")
            }
        }
        suspendingJob.await()
        blockingJob.await()
        // Suspending must have ended before blocking started.
        val suspEndIdx = sentinel.indexOf("susp-end")
        val blockStartIdx = sentinel.indexOf("block-start")
        assertTrue(suspEndIdx < blockStartIdx, "blocking action started before suspending completed: $sentinel")
        // Final state: n=1 (suspending) then n=101 (blocking).
        assertEquals(101, v.n.value)
    }
}

class SuspendActionCancellationTest {

    @Test fun cancellationDuringBodyRollsBackTheTransaction() = runBlocking {
        val v = SuspendVault()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            v.suspendAction { n mutate 1 }
            assertEquals(1, v.n.value)

            val gate = CompletableDeferred<Unit>()
            val job = scope.launch {
                v.suspendAction {
                    n mutate 99
                    gate.complete(Unit)
                    delay(10_000) // long wait
                    s mutate "should-not-commit"
                }
            }
            gate.await()
            job.cancel()
            withTimeoutOrNull(2_000) { job.join() }

            // Body cancelled mid-flight → rollback.
            assertEquals(1, v.n.value, "n preserved across cancelled suspending action")
            assertEquals("init", v.s.value)
        } finally {
            scope.cancel()
        }
    }
}

class SuspendActionConcurrencyTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest fun cleanup() {
        scope.cancel()
    }

    @Test fun manyConcurrentSuspendingActionsAllCommitWithoutDataLoss() = runBlocking {
        val v = SuspendVault()
        val workers = 8
        val perWorker = 50
        val jobs = List(workers) {
            async(Dispatchers.Default) {
                repeat(perWorker) {
                    v.suspendAction {
                        n mutate n.value + 1
                    }
                }
            }
        }
        jobs.awaitAll()
        assertEquals(workers * perWorker, v.n.value, "no lost updates across concurrent suspending actions")
    }
}
