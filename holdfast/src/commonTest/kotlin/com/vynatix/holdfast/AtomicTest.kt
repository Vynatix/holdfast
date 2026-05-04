package com.vynatix.holdfast

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

private class AccountVault(initial: Long = 0) : Holdfast<AccountVault>() {
    val balance by state { initial }
}

private class LedgerVault : Holdfast<LedgerVault>() {
    val entries by state { emptyList<String>() }
}

class AtomicSuccessTest {

    @Test fun atomicWithTwoVaultsCommitsBothBodiesAtomically() {
        val a = AccountVault(initial = 100)
        val b = AccountVault(initial = 0)
        val r = atomic(a, b) {
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
        val r = atomic(a, b) {
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
        val r = atomic(a, b, c) {
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
        val r = atomic(a, b) {
            a.action { balance update { it - 30 } }
            b.action { balance update { it + 30 } }
            error("simulated mid-transfer failure")
        }
        assertIs<TransactionResult.Error>(r)
        assertEquals(100L, a.balance.value, "vault a rolled back")
        assertEquals(0L, b.balance.value, "vault b rolled back")
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

        val r = atomic(acct, ledger) {
            acct.action { balance update { it - 100 } }
            ledger.action { entries update { it + "withdraw 100" } }
        }
        assertIs<TransactionResult.Success<*>>(r)
        assertEquals(900L, acct.balance.value)
        assertEquals(listOf("withdraw 100"), ledger.entries.value)
    }
}

class AtomicConcurrencyTest {

    @Test fun concurrentAtomicTransfersHaveNoLostUpdates() = runBlocking {
        val a = AccountVault(initial = 0)
        val b = AccountVault(initial = 0)
        val workers = 4
        val perWorker = 50

        val jobs = List(workers) {
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
