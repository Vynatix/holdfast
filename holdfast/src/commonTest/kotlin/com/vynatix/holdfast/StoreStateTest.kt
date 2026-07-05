package com.vynatix.holdfast

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class StateTestVault : Store<StateTestVault>() {
    val count by state { 0 }
    val label by state { "initial" }
}

private class StateOwnerA : Store<StateOwnerA>() {
    val a by state { 0 }
}

private class StateOwnerB : Store<StateOwnerB>() {
    val b by state { 0 }
}

private class MixedTypeStateVault : Store<MixedTypeStateVault>() {
    val intState by state { 1 }
    val stringState by state { "two" }
    val listState by state { listOf(3, 4) }
}

private class TwoZeroVault : Store<TwoZeroVault>() {
    val a by state { 0 }
    val b by state { 0 }
}

class StateBasicsTest {
    @Test
    fun initialStateValueMatchesInitializer() {
        val v = StateTestVault()
        assertEquals(0, v.count.value)
        assertEquals("initial", v.label.value)
    }

    @Test
    fun actionMutatesSingleStateAndReturnsCommittedSuccess() {
        val v = StateTestVault()
        val result = v action { count mutate 42 }
        assertIs<TransactionResult.Success<*>>(result)
        assertEquals(42, v.count.value)
        assertEquals(TransactionStatus.Committed, result.transaction.status)
    }

    @Test
    fun actionAtomicallyMutatesMultipleStatesInOneTransaction() {
        val v = StateTestVault()
        v action {
            count mutate 7
            label mutate "seven"
        }
        assertEquals(7, v.count.value)
        assertEquals("seven", v.label.value)
    }
}

class StateDeclarationTest {
    @Test
    fun statePropertyNameDeterminesInternalKey() {
        val v = StateTestVault()
        val direct = v.count
        val byName = v.getState("count")
        assertSame(direct, byName, "the property name 'count' is the internal key for the state")
    }

    @Test
    fun multipleStateDeclarationsCoexistWithDifferentTypes() {
        val v = MixedTypeStateVault()
        assertEquals(1, v.intState.value)
        assertEquals("two", v.stringState.value)
        assertEquals(listOf(3, 4), v.listState.value)

        v action {
            intState mutate 10
            stringState mutate "twenty"
            listState mutate listOf(30, 40)
        }

        assertEquals(10, v.intState.value)
        assertEquals("twenty", v.stringState.value)
        assertEquals(listOf(30, 40), v.listState.value)
    }

    @Test
    fun stateValueGetterIsObservableFromOutsideAnyAction() {
        val v = StateTestVault()
        assertEquals(0, v.count.value, "outside action: read returns committed _value")
        v action { count mutate 5 }
        assertEquals(5, v.count.value, "after commit, getter reflects new value")
    }

    @Test
    fun stateValueGetterReturnsCommittedAfterSuccessfulAction() {
        val v = StateTestVault()
        val before = v.count.value
        v action { count mutate before + 100 }
        val after = v.count.value
        assertEquals(before + 100, after)
    }

    @Test
    fun stateValueGetterReturnsPreActionValueAfterRollback() {
        val v = StateTestVault()
        v action { count mutate 5 }
        val before = v.count.value

        v action {
            count mutate 99
            error("rollback")
        }

        assertEquals(before, v.count.value, "rollback restores pre-action committed value")
    }

    @Test
    fun multipleStatesWithSameInitialValueAreDistinctInstances() {
        val v = TwoZeroVault()
        assertNotSame(v.a, v.b, "two states with the same initial value are still distinct State instances")
        v action { a mutate 1 }
        assertEquals(1, v.a.value)
        assertEquals(0, v.b.value, "mutating a does not affect b")
    }
}

class VaultInstanceIsolationTest {
    @Test
    fun separateVaultInstancesHaveIndependentState() {
        val a = StateTestVault()
        val b = StateTestVault()
        a action { count mutate 100 }
        assertEquals(100, a.count.value)
        assertEquals(0, b.count.value)
    }

    @Test
    fun statePropertyDelegateReturnsIdenticalReferenceOnRepeatedAccess() {
        val v = StateTestVault()
        val first = v.count
        val second = v.count
        assertTrue(
            first === second,
            "delegate must return identity, not a fresh State each access",
        )
    }
}

class CrossVaultMutationTest {
    @Test
    fun mutatingStateOwnedByDifferentVaultIsRejected() {
        val vaultA = StateOwnerA()
        val vaultB = StateOwnerB()
        val foreignState = vaultA.a

        val result =
            vaultB action {
                foreignState mutate 99
            }

        if (result is TransactionResult.Success) {
            assertEquals(
                0,
                vaultA.a.value,
                "store A's state was silently mutated from store B's action; cross-store writes should be rejected",
            )
        }
    }

    @Test
    fun crossStoreMutationErrorMessageNamesStateAndBothStores() {
        val vaultA = StateOwnerA()
        val vaultB = StateOwnerB()
        val foreignState = vaultA.a

        val result = vaultB action { foreignState mutate 99 }
        val error = result as TransactionResult.Error
        val message = error.exception.message ?: ""
        assertTrue(message.contains("'a'"), "message names the state property: $message")
        assertTrue(message.contains("StateOwnerA"), "message names the owning store: $message")
        assertTrue(message.contains("StateOwnerB"), "message names the acting store: $message")
    }

    @Test
    fun failedTransactionInOneVaultDoesNotMutateAnotherVaultsState() {
        val vaultA = StateOwnerA()
        val vaultB = StateOwnerB()
        val foreignState = vaultA.a

        vaultA action { a mutate 5 }
        assertEquals(5, vaultA.a.value)

        vaultB action {
            foreignState mutate 99
            error("B fails")
        }
        assertEquals(
            5,
            vaultA.a.value,
            "store A's state was modified by store B's failed transaction; current value: ${vaultA.a.value}",
        )
    }

    @Test
    fun readingForeignVaultStateValueIsAllowedAndReturnsCommittedView() {
        val vaultA = StateOwnerA()
        val vaultB = StateOwnerB()
        vaultA action { a mutate 42 }

        // Reading is unrestricted; only mutating a foreign state is rejected.
        val seenInB = atomic(-1)
        vaultB action {
            seenInB.value = vaultA.a.value
        }
        assertEquals(42, seenInB.value, "reading foreign store state from inside another store's action works")
    }

    @Test
    fun parallelActionsOnDifferentVaultsRunIndependently() =
        runBlocking {
            val vA = StateOwnerA()
            val vB = StateOwnerB()
            val opsPerVault = 500

            val jobs =
                listOf(
                    async(Dispatchers.Default) {
                        repeat(opsPerVault) { vA action { a mutate a.value + 1 } }
                    },
                    async(Dispatchers.Default) {
                        repeat(opsPerVault) { vB action { b mutate b.value + 1 } }
                    },
                )
            jobs.awaitAll()

            assertEquals(opsPerVault, vA.a.value)
            assertEquals(opsPerVault, vB.b.value)
        }

    @Test
    fun successfulActionInVaultADoesNotFireVaultBObservers() {
        val vA = StateOwnerA()
        val vB = StateOwnerB()
        val bObserved = mutableListOf<Int>()
        val d = vB { b effect { bObserved.add(this) } }
        bObserved.clear()

        vA action { a mutate 99 }

        assertEquals(
            emptyList(),
            bObserved,
            "store A's commit must not fire store B's observers",
        )
        d.dispose()
    }
}
