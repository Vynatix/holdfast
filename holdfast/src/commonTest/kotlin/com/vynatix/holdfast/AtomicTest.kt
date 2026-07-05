package com.vynatix.holdfast

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
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

class AtomicSuccessTest {
    @Test fun atomicWithTwoVaultsCommitsBothBodiesAtomically() {
        val a = AccountVault(initial = 100)
        val b = AccountVault(initial = 0)
        val r =
            atomic(a, b) {
                a.action { balance update { it - 30 } }
                b.action { balance update { it + 30 } }
            }
        assertIs<TransactionResult.Success<*>>(r)
        assertEquals(70L, a.balance.value)
        assertEquals(30L, b.balance.value)
    }

    @Test fun atomicReturnsBodyValueViaSuccess() {
        val a = AccountVault(initial = 50)
        val b = AccountVault(initial = 50)
        val r =
            atomic(a, b) {
                a.action { balance update { it - 10 } }
                b.action { balance update { it + 10 } }
                "transferred"
            }
        assertIs<TransactionResult.Success<String>>(r)
        assertEquals("transferred", r.value)
    }

    @Test fun atomicWithThreeVaults() {
        val a = AccountVault(initial = 1)
        val b = AccountVault(initial = 2)
        val c = AccountVault(initial = 3)
        val r =
            atomic(a, b, c) {
                a.action { balance update { it + 10 } }
                b.action { balance update { it + 20 } }
                c.action { balance update { it + 30 } }
            }
        assertIs<TransactionResult.Success<*>>(r)
        assertEquals(11L, a.balance.value)
        assertEquals(22L, b.balance.value)
        assertEquals(33L, c.balance.value)
    }
}

class AtomicRollbackTest {
    @Test fun atomicBodyThrowingRollsBackAllVaults() {
        val a = AccountVault(initial = 100)
        val b = AccountVault(initial = 0)
        val r =
            atomic(a, b) {
                a.action { balance update { it - 30 } }
                b.action { balance update { it + 30 } }
                error("simulated mid-transfer failure")
            }
        assertIs<TransactionResult.Error>(r)
        assertEquals(100L, a.balance.value, "store a rolled back")
        assertEquals(0L, b.balance.value, "store b rolled back")
    }

    @Test fun atomicErrorIsCarriedInTransactionResult() {
        val a = AccountVault()
        val b = AccountVault()
        val r = atomic(a, b) { error("specific message") }
        assertIs<TransactionResult.Error>(r)
        assertEquals("specific message", r.exception.message)
    }
}

class AtomicCrossVaultObserverTest {
    @Test fun observersFireForEachVaultAfterAtomicCommit() {
        val a = AccountVault(initial = 100)
        val b = AccountVault(initial = 0)
        val seenA = mutableListOf<Long>()
        val seenB = mutableListOf<Long>()
        val subA = a { balance effect { seenA.add(this) } }
        val subB = b { balance effect { seenB.add(this) } }
        seenA.clear()
        seenB.clear()

        atomic(a, b) {
            a.action { balance update { it - 25 } }
            b.action { balance update { it + 25 } }
        }

        assertEquals(listOf(75L), seenA)
        assertEquals(listOf(25L), seenB)
        subA.dispose()
        subB.dispose()
    }
}

class AtomicMixedTypesTest {
    @Test fun atomicCoordinatesDifferentVaultClasses() {
        val acct = AccountVault(initial = 1000)
        val ledger = LedgerVault()

        val r =
            atomic(acct, ledger) {
                acct.action { balance update { it - 100 } }
                ledger.action { entries update { it + "withdraw 100" } }
            }
        assertIs<TransactionResult.Success<*>>(r)
        assertEquals(900L, acct.balance.value)
        assertEquals(listOf("withdraw 100"), ledger.entries.value)
    }
}

@OptIn(StoreInternalApi::class)
class AtomicSerializerBracketTest {
    private class RecordingSerializer(
        private val label: String,
        private val log: MutableList<String>,
    ) : Store.AsyncSerializer {
        override fun blockingAcquire() {
            log += "acquire:$label"
        }

        override fun blockingRelease() {
            log += "release:$label"
        }
    }

    @Test fun atomicBracketsEachStoresSerializerInLockOrder() {
        val a = AccountVault()
        val b = AccountVault()
        val log = mutableListOf<String>()
        a.asyncSerializer = RecordingSerializer("a", log)
        b.asyncSerializer = RecordingSerializer("b", log)

        // Pass the stores out of order; acquisition must still follow lockOrderKey.
        val r = atomic(b, a) { log += "body" }
        assertIs<TransactionResult.Success<*>>(r)
        assertEquals(
            listOf("acquire:a", "acquire:b", "body", "release:b", "release:a"),
            log,
            "serializer acquire wraps the whole per-store lock scope, in lock order, released in reverse",
        )
    }

    @Test fun atomicReleasesSerializersOnRollbackToo() {
        val a = AccountVault()
        val log = mutableListOf<String>()
        a.asyncSerializer = RecordingSerializer("a", log)
        val r = atomic(a) { error("boom") }
        assertIs<TransactionResult.Error>(r)
        assertEquals(listOf("acquire:a", "release:a"), log)
    }
}

class AtomicConcurrencyTest {
    @Test fun concurrentAtomicTransfersHaveNoLostUpdates() =
        runBlocking {
            val a = AccountVault(initial = 0)
            val b = AccountVault(initial = 0)
            val workers = 4
            val perWorker = 50

            val jobs =
                List(workers) {
                    async(Dispatchers.Default) {
                        repeat(perWorker) {
                            atomic(a, b) {
                                a.action { balance update { it + 1 } }
                                b.action { balance update { it + 2 } }
                            }
                        }
                    }
                }
            jobs.awaitAll()
            assertEquals((workers * perWorker).toLong(), a.balance.value)
            assertEquals((workers * perWorker * 2L), b.balance.value)
        }
}

private class AtomicThrowingBridge<T : Any> : Bridge<T> {
    override fun observe(observer: (T) -> Unit): Disposable = Disposable { }

    override fun publish(value: T): Boolean = throw RuntimeException("bridge refused")
}

class AtomicCommitFailureNamingTest {
    @Test fun commitFailureNamesTheFailingStore() {
        // A store whose bridge publish throws makes that store's commit fail; the
        // frame's Error must name it even though `transaction` is roots.last (F7).
        val a = AccountVault(initial = 0)
        a { balance bridge AtomicThrowingBridge() }
        val r = atomic(a) { a.action { balance mutate 1L } }
        assertIs<TransactionResult.Error>(r)
        val message = r.exception.message ?: ""
        assertTrue(
            "Commit failed for ${a.frameIdentity()}" in message,
            "commit-failure error should name the failing store, was: $message",
        )
    }
}
