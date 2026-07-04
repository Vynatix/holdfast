package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.EventfulStore
import com.vynatix.holdfast.FramePolicy
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.TransactionStatus
import com.vynatix.holdfast.effect
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

private class AccountVault(
    initial: Long = 0,
) : Store<AccountVault>() {
    val balance by state { initial }
}

private class LedgerVault : Store<LedgerVault>() {
    val entries by state { emptyList<String>() }
}

private sealed class TransferEvent {
    data class Posted(
        val amount: Long,
    ) : TransferEvent()
}

private class EventingAccountVault(
    initial: Long = 0,
) : EventfulStore<EventingAccountVault, TransferEvent>() {
    val balance by state { initial }
}

class SuspendAtomicSuccessTest {
    @Test fun transferBetweenTwoVaultsCommitsBoth() =
        runBlocking {
            val a = AccountVault(initial = 100)
            val b = AccountVault(initial = 0)
            val seenA = mutableListOf<Long>()
            val seenB = mutableListOf<Long>()
            val subA = a { balance effect { seenA.add(this) } }
            val subB = b { balance effect { seenB.add(this) } }
            seenA.clear()
            seenB.clear()

            val r =
                suspendAtomic(a, b) {
                    a { balance update { it - 30 } }
                    b { balance update { it + 30 } }
                }

            assertIs<TransactionResult.Success<*>>(r)
            assertEquals(70L, a.balance.value)
            assertEquals(30L, b.balance.value)
            // Each store's observer fires with its post-commit value. Per-store
            // sequential fanout in lock order — a's observer fires before b's.
            assertEquals(listOf(70L), seenA)
            assertEquals(listOf(30L), seenB)
            subA.dispose()
            subB.dispose()
        }

    @Test fun suspendAtomicReturnsBodyValueViaSuccess() =
        runBlocking {
            val a = AccountVault(initial = 50)
            val b = AccountVault(initial = 50)
            val r =
                suspendAtomic(a, b) {
                    a { balance update { it - 10 } }
                    b { balance update { it + 10 } }
                    "transferred"
                }
            assertIs<TransactionResult.Success<String>>(r)
            assertEquals("transferred", r.value)
        }

    @Test fun suspendAtomicWithDifferentVaultClasses() =
        runBlocking {
            val acct = AccountVault(initial = 1000)
            val ledger = LedgerVault()
            val r =
                suspendAtomic(acct, ledger) {
                    acct { balance update { it - 100 } }
                    ledger { entries update { it + "withdraw 100" } }
                }
            assertIs<TransactionResult.Success<*>>(r)
            assertEquals(900L, acct.balance.value)
            assertEquals(listOf("withdraw 100"), ledger.entries.value)
        }

    @Test fun suspendAtomicBodyMaySuspendBetweenMutations() =
        runBlocking {
            val a = AccountVault(initial = 0)
            val b = AccountVault(initial = 0)
            val r =
                suspendAtomic(a, b) {
                    a { balance update { it + 1 } }
                    delay(20) // suspend between mutations
                    b { balance update { it + 2 } }
                    delay(20)
                    a { balance update { it + 10 } } // read-your-own-writes inside body
                }
            assertIs<TransactionResult.Success<*>>(r)
            assertEquals(11L, a.balance.value)
            assertEquals(2L, b.balance.value)
        }
}

class SuspendAtomicRollbackTest {
    @Test fun bodyThrowRollsBackBoth() =
        runBlocking {
            val a = AccountVault(initial = 100)
            val b = AccountVault(initial = 0)
            val r =
                suspendAtomic(a, b) {
                    a { balance update { it - 30 } }
                    b { balance update { it + 30 } }
                    error("simulated mid-transfer failure")
                }
            assertIs<TransactionResult.Error>(r)
            // Neither store sees the partial state.
            assertEquals(100L, a.balance.value)
            assertEquals(0L, b.balance.value)
        }

    @Test fun bodyThrowDoesNotFireObservers() =
        runBlocking {
            val a = AccountVault(initial = 100)
            val b = AccountVault(initial = 0)
            val seenA = mutableListOf<Long>()
            val seenB = mutableListOf<Long>()
            val subA = a { balance effect { seenA.add(this) } }
            val subB = b { balance effect { seenB.add(this) } }
            seenA.clear()
            seenB.clear()

            suspendAtomic(a, b) {
                a { balance update { it - 30 } }
                b { balance update { it + 30 } }
                error("rollback")
            }

            assertTrue(seenA.isEmpty(), "store a observer must not fire on rollback; saw $seenA")
            assertTrue(seenB.isEmpty(), "store b observer must not fire on rollback; saw $seenB")
            subA.dispose()
            subB.dispose()
        }
}

class SuspendAtomicCancellationTest {
    @Test fun bodyCancelledRollsBackBoth() =
        runBlocking {
            val a = AccountVault(initial = 100)
            val b = AccountVault(initial = 0)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val gate = CompletableDeferred<Unit>()
                val job =
                    scope.launch {
                        suspendAtomic(a, b) {
                            a { balance update { it - 30 } }
                            b { balance update { it + 30 } }
                            gate.complete(Unit)
                            delay(10_000) // long wait to be cancelled
                            a { balance update { it - 999 } } // never reached
                        }
                    }
                gate.await()
                job.cancel()
                withTimeoutOrNull(2_000) { job.join() }

                // Both vaults rolled back.
                assertEquals(100L, a.balance.value, "a preserved across cancellation")
                assertEquals(0L, b.balance.value, "b preserved across cancellation")
            } finally {
                scope.cancel()
            }
        }
}

class SuspendAtomicDeadlockPreventionTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest fun cleanup() {
        scope.cancel()
    }

    @Test fun deadlockPreventionViaLockOrderKey() =
        runBlocking {
            // Two coroutines call suspendAtomic with vaults in REVERSE order.
            // Without lockOrderKey sorting they could deadlock; with it, both
            // serialize and complete.
            val a = AccountVault(initial = 0)
            val b = AccountVault(initial = 0)

            val workers = 4
            val perWorker = 25
            val jobs =
                List(workers) { workerIdx ->
                    scope.async {
                        repeat(perWorker) {
                            if (workerIdx % 2 == 0) {
                                suspendAtomic(a, b) {
                                    a { balance update { it + 1 } }
                                    b { balance update { it + 2 } }
                                }
                            } else {
                                suspendAtomic(b, a) {
                                    a { balance update { it + 1 } }
                                    b { balance update { it + 2 } }
                                }
                            }
                        }
                    }
                }
            // If deadlocked, this would hang; the test framework's timeout would
            // kill it. We add a soft timeout for diagnostics.
            val ok =
                withTimeoutOrNull(30_000) {
                    jobs.awaitAll()
                    true
                } ?: false
            assertTrue(ok, "suspendAtomic with reverse-ordered vaults deadlocked")
            assertEquals((workers * perWorker).toLong(), a.balance.value)
            assertEquals((workers * perWorker * 2L), b.balance.value)
        }
}

class SuspendAtomicNestedTest {
    @Test fun nestedSuspendAtomicReusesParentLocksForOverlappingVaults() =
        runBlocking {
            val a = AccountVault(initial = 0)
            val b = AccountVault(initial = 0)
            val c = AccountVault(initial = 0)

            // Outer covers [a, b]. Inner adds [b, c]. The inner call's `b` is
            // already held; only `c` should be newly acquired. Introducing `c`
            // requires AllowUnenrolled on the OUTER frame since 0.3 (F2): c's
            // fresh root commits at the inner frame's exit and does not roll
            // back with the outer frame.
            val r =
                suspendAtomic(a, b, policy = FramePolicy.AllowUnenrolled) {
                    a { balance update { it + 1 } }
                    suspendAtomic(b, c) {
                        b { balance update { it + 10 } }
                        c { balance update { it + 100 } }
                    }
                    // Mutates after the inner call still target the outer's roots.
                    a { balance update { it + 1 } }
                }
            assertIs<TransactionResult.Success<*>>(r)
            // a receives +1 then +1 = 2
            assertEquals(2L, a.balance.value)
            // b receives +10 inside inner; merged into outer's root via the
            // adopted activeTransaction
            assertEquals(10L, b.balance.value)
            // c is committed at the inner call's end (under the outer scope's
            // commit phase, c would have been committed already since
            // suspendAtomic returns before the outer's commit; c's commit happens
            // at inner's exit because c was newly acquired by inner).
            assertEquals(100L, c.balance.value)
        }

    @Test fun nestedSuspendAtomicOnSameVaultsIsTrivial() =
        runBlocking {
            // Inner's vaults are a strict subset of outer's. Inner should not
            // re-acquire any mutex; the body should run as if it were inline.
            val a = AccountVault(initial = 0)
            val b = AccountVault(initial = 0)
            val r =
                suspendAtomic(a, b) {
                    a { balance update { it + 1 } }
                    suspendAtomic(a) {
                        a { balance update { it + 10 } }
                    }
                    b { balance update { it + 100 } }
                }
            assertIs<TransactionResult.Success<*>>(r)
            assertEquals(11L, a.balance.value)
            assertEquals(100L, b.balance.value)
        }
}

class SuspendAtomicEventsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest fun cleanup() {
        scope.coroutineContext[Job]?.cancel()
    }

    @Test fun eventsFireAfterCommitInLockOrder() =
        runBlocking {
            val a = EventingAccountVault(initial = 100)
            val b = EventingAccountVault(initial = 0)
            val timeline = mutableListOf<String>()

            val collectorA =
                scope.launch {
                    a.events.collect { ev ->
                        timeline += "a-event"
                    }
                }
            val collectorB =
                scope.launch {
                    b.events.collect { ev ->
                        timeline += "b-event"
                    }
                }
            val balanceObserverA = a { balance effect { if (this != 100L) timeline += "a-state=$this" } }
            val balanceObserverB = b { balance effect { if (this != 0L) timeline += "b-state=$this" } }
            // Allow collectors to subscribe.
            delay(100)

            val r =
                suspendAtomic(a, b) {
                    a {
                        balance update { it - 30 }
                        emit(TransferEvent.Posted(-30))
                    }
                    b {
                        balance update { it + 30 }
                        emit(TransferEvent.Posted(30))
                    }
                }
            assertIs<TransactionResult.Success<*>>(r)

            // Wait for events to land.
            withTimeoutOrNull(2_000) {
                while (!(timeline.contains("a-event") && timeline.contains("b-event"))) {
                    delay(20)
                }
            }
            collectorA.cancel()
            collectorB.cancel()
            balanceObserverA.dispose()
            balanceObserverB.dispose()

            // Per-store: state observer fires before that store's events.
            // Across vaults: a (lower lockOrderKey) commits before b.
            val aStateIdx = timeline.indexOf("a-state=70")
            val aEventIdx = timeline.indexOf("a-event")
            val bStateIdx = timeline.indexOf("b-state=30")
            val bEventIdx = timeline.indexOf("b-event")

            assertTrue(aStateIdx >= 0, "a state observer did not fire; saw $timeline")
            assertTrue(aEventIdx >= 0, "a event did not fire; saw $timeline")
            assertTrue(bStateIdx >= 0, "b state observer did not fire; saw $timeline")
            assertTrue(bEventIdx >= 0, "b event did not fire; saw $timeline")
            // State observers run synchronously on the commit thread — their order
            // reflects per-store and cross-store commit ordering directly.
            assertTrue(
                bStateIdx < aEventIdx || aStateIdx < bStateIdx,
                "state ordering must reflect lock order; saw $timeline",
            )
            // Cross-store state ordering: a (lower lockOrderKey) commits before b.
            assertTrue(aStateIdx < bStateIdx, "a's state observer must fire before b's; saw $timeline")
            // Events are dispatched to collectors asynchronously, but their
            // emission order to the SharedFlow is the lock order.
            assertTrue(aEventIdx < bEventIdx, "a's event must arrive before b's; saw $timeline")
        }

    @Test fun rollbackDiscardsEvents() =
        runBlocking {
            val a = EventingAccountVault(initial = 100)
            val b = EventingAccountVault(initial = 0)
            val received = mutableListOf<TransferEvent>()
            val collectorA = scope.launch { a.events.collect { received += it } }
            val collectorB = scope.launch { b.events.collect { received += it } }
            delay(100)

            val r =
                suspendAtomic(a, b) {
                    a {
                        balance update { it - 30 }
                        emit(TransferEvent.Posted(-30))
                    }
                    b {
                        balance update { it + 30 }
                        emit(TransferEvent.Posted(30))
                    }
                    error("rolled back")
                }
            assertIs<TransactionResult.Error>(r)
            delay(100)
            collectorA.cancel()
            collectorB.cancel()
            assertTrue(received.isEmpty(), "rollback must discard all events; got $received")
        }
}

class SuspendAtomicMutualExclusionTest {
    @Test fun concurrentSuspendAtomicSerializesOnSharedMutex() =
        runBlocking {
            // Two suspendAtomic calls on overlapping store sets serialize
            // via the shared AsyncSerializer mutex on each store.
            val a = AccountVault(initial = 0)
            val b = AccountVault(initial = 0)
            val sentinel = mutableListOf<String>()
            val sentinelLock = object : kotlinx.atomicfu.locks.SynchronizedObject() {}

            fun record(s: String) = kotlinx.atomicfu.locks.synchronized(sentinelLock) { sentinel += s }

            val gate1 = CompletableDeferred<Unit>()
            val first =
                async(Dispatchers.Default) {
                    suspendAtomic(a, b) {
                        record("first-start")
                        gate1.complete(Unit)
                        delay(80)
                        a { balance update { it + 1 } }
                        record("first-end")
                    }
                }
            gate1.await()
            // Second call enters while first is suspended in delay(). Should
            // block on the shared mutex.
            val second =
                async(Dispatchers.Default) {
                    suspendAtomic(a, b) {
                        record("second-start")
                        a { balance update { it + 100 } }
                        record("second-end")
                    }
                }
            first.await()
            second.await()

            val firstEndIdx = sentinel.indexOf("first-end")
            val secondStartIdx = sentinel.indexOf("second-start")
            assertTrue(
                firstEndIdx < secondStartIdx,
                "second suspendAtomic ran before first completed: $sentinel",
            )
            assertEquals(101L, a.balance.value)
        }

    @Test fun suspendAtomicSerializesWithSuspendActionOnSharedVault() =
        runBlocking {
            // suspendAction on store `a` and suspendAtomic(a, b) must serialize
            // since they share the same per-store Mutex.
            val a = AccountVault(initial = 0)
            val b = AccountVault(initial = 0)
            val sentinel = mutableListOf<String>()
            val gate = CompletableDeferred<Unit>()

            val actionJob =
                async(Dispatchers.Default) {
                    a.suspendAction {
                        sentinel += "action-start"
                        gate.complete(Unit)
                        delay(80)
                        balance update { it + 5 }
                        sentinel += "action-end"
                    }
                }
            gate.await()
            val atomicJob =
                async(Dispatchers.Default) {
                    suspendAtomic(a, b) {
                        sentinel += "atomic-start"
                        a { balance update { it + 100 } }
                        b { balance update { it + 1 } }
                        sentinel += "atomic-end"
                    }
                }
            actionJob.await()
            atomicJob.await()

            val actionEndIdx = sentinel.indexOf("action-end")
            val atomicStartIdx = sentinel.indexOf("atomic-start")
            assertTrue(
                actionEndIdx < atomicStartIdx,
                "suspendAtomic ran before suspendAction completed: $sentinel",
            )
            assertEquals(105L, a.balance.value)
            assertEquals(1L, b.balance.value)
        }
}

class SuspendAtomicConcurrencyTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest fun cleanup() {
        scope.cancel()
    }

    @Test fun manyConcurrentSuspendAtomicAllCommitWithoutDataLoss() =
        runBlocking {
            val a = AccountVault(initial = 0)
            val b = AccountVault(initial = 0)
            val workers = 4
            val perWorker = 50
            val jobs =
                List(workers) {
                    async(Dispatchers.Default) {
                        repeat(perWorker) {
                            suspendAtomic(a, b) {
                                a { balance update { it + 1 } }
                                b { balance update { it + 2 } }
                            }
                        }
                    }
                }
            jobs.awaitAll()
            assertEquals((workers * perWorker).toLong(), a.balance.value)
            assertEquals((workers * perWorker * 2L), b.balance.value)
        }
}

class SuspendAtomicRequireVaultTest {
    @Test fun emptyVaultsThrowsIllegalArgument() =
        runBlocking {
            try {
                suspendAtomic { /* no vaults */ }
                kotlin.test.fail("expected IllegalArgumentException")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message?.contains("at least one store") == true)
            }
        }

    @Test fun singleVaultSuspendAtomicWorksLikeMiniSuspendAction() =
        runBlocking {
            val a = AccountVault(initial = 0)
            val r =
                suspendAtomic(a) {
                    a { balance update { it + 7 } }
                }
            assertIs<TransactionResult.Success<*>>(r)
            assertEquals(7L, a.balance.value)
            assertEquals(TransactionStatus.Committed, r.transaction.status)
        }
}
