package com.vynatix.holdfast

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class ApiVault : Store<ApiVault>() {
    val n by state { 0 }
    val s by state { "init" }
    val items by state { emptyList<String>() }
    val nDistinct by state(distinct = true) { 0 }
}

class UpdateExtensionTest {
    @Test
    fun updateAppliesBlockToCurrentValueAndCommits() {
        val v = ApiVault()
        v action { n update { it + 1 } }
        assertEquals(1, v.n.value)
    }

    @Test
    fun updateChainsInsideAnAction() {
        val v = ApiVault()
        v action {
            n update { it + 10 }
            n update { it * 2 }
            n update { it + 1 }
        }
        // 0 → 10 → 20 → 21
        assertEquals(21, v.n.value)
    }

    @Test
    fun updateOutsideActionWrapsInImplicitTransaction() {
        val v = ApiVault()
        // Standalone update via the store receiver (a bare `store { }` invoke no
        // longer permits mutation); the RMW still wraps in an implicit action.
        with(v) { n update { it + 5 } }
        assertEquals(5, v.n.value)
    }

    @Test
    fun updateOnCollectionAppendsAtomically() {
        val v = ApiVault()
        v action {
            items update { it + "a" }
            items update { it + "b" }
            items update { it + "c" }
        }
        assertEquals(listOf("a", "b", "c"), v.items.value)
    }
}

class ObserveFromExtensionTest {
    @Test
    fun observeFromAttachesAnInboundOnlyObservableAndAppliesValuesToState() {
        val v = ApiVault()
        val callbacks = mutableListOf<(Int) -> Unit>()
        val obs =
            Observable<Int> { observer ->
                callbacks.add(observer)
                Disposable { callbacks.remove(observer) }
            }
        val handle = v { n observeFrom obs }
        // Push from external source.
        callbacks.toList().forEach { it(42) }
        assertEquals(42, v.n.value, "external push via observeFrom updated state")
        handle.dispose()
        // After dispose, further pushes should NOT affect the state.
        callbacks.toList().forEach { it(99) }
        assertEquals(42, v.n.value, "after dispose, external pushes are ignored")
    }

    @Test
    fun observeFromFiresStateObserversWithoutGoingThroughATransaction() {
        val v = ApiVault()
        val seen = mutableListOf<Int>()
        val sub = v { n effect { seen.add(this) } }
        seen.clear()

        val callbacks = mutableListOf<(Int) -> Unit>()
        val obs =
            Observable<Int> { observer ->
                callbacks.add(observer)
                Disposable { callbacks.remove(observer) }
            }
        v { n observeFrom obs }
        callbacks.toList().forEach { it(7) }
        assertEquals(listOf(7), seen, "state observer fired for inbound bridge update")
        sub.dispose()
    }
}

class ActionGenericReturnValueTest {
    @Test
    fun actionReturnsValueComputedByBody() {
        val v = ApiVault()
        val r =
            v action {
                n mutate 10
                n.value * 2 // body's return value
            }
        assertIs<TransactionResult.Success<Int>>(r)
        assertEquals(20, r.value, "TransactionResult.Success.value carries the body's computed return")
    }

    @Test
    fun actionReturningStringValueIsCarriedThroughSuccess() {
        val v = ApiVault()
        val r =
            v action {
                s mutate "hello"
                "computed: ${s.value}"
            }
        assertIs<TransactionResult.Success<String>>(r)
        assertEquals("computed: hello", r.value)
    }

    @Test
    fun actionReturningUnitWorksUnchanged() {
        val v = ApiVault()
        val r = v action { n mutate 5 }
        assertIs<TransactionResult.Success<Unit>>(r)
        assertEquals(Unit, r.value)
    }

    @Test
    fun actionReturningDataClassPipesItOut() {
        val v = ApiVault()

        data class Receipt(
            val id: String,
            val balance: Int,
        )
        val r =
            v action {
                n mutate 100
                Receipt(id = "r1", balance = n.value)
            }
        assertIs<TransactionResult.Success<Receipt>>(r)
        assertEquals(Receipt("r1", 100), r.value)
    }

    @Test
    fun erroringActionReturnsErrorEvenWithGenericReturnType() {
        val v = ApiVault()
        val r: TransactionResult<Int> =
            v action {
                n mutate 1
                error("rolled back")
            }
        assertIs<TransactionResult.Error>(r)
        assertEquals("rolled back", r.exception.message)
    }
}

class StateDistinctOptInTest {
    @Test
    fun distinctStateDoesNotFireObserverOnSameValueCommit() {
        val v = ApiVault()
        v action { nDistinct mutate 5 }
        val seen = mutableListOf<Int>()
        val sub = v { nDistinct effect { seen.add(this) } }
        seen.clear()

        v action { nDistinct mutate 5 } // same value
        v action { nDistinct mutate 5 } // same value again
        assertEquals(emptyList(), seen, "distinct=true skips observer fanout when the committed value is unchanged")

        v action { nDistinct mutate 6 } // different
        assertEquals(listOf(6), seen)
        sub.dispose()
    }

    @Test
    fun nonDistinctStateStillFiresOnSameValueCommit() {
        // Verify the default (distinct=false) preserves the original library contract.
        val v = ApiVault()
        v action { n mutate 5 }
        val seen = mutableListOf<Int>()
        val sub = v { n effect { seen.add(this) } }
        seen.clear()

        v action { n mutate 5 }
        v action { n mutate 5 }
        assertEquals(listOf(5, 5), seen, "distinct=false (default) fires observer on every commit, even same value")
        sub.dispose()
    }
}

class TransactionModifiedStatesTest {
    @Test
    fun modifiedStatesReflectsPendingWriteKeysOnOwnerThread() {
        val v = ApiVault()
        var captured: Set<State<*>> = emptySet()
        v action {
            n mutate 1
            s mutate "x"
            captured = activeTransaction!!.modifiedStates
        }
        assertEquals(2, captured.size)
        assertTrue(v.n in captured, "n is in the modified states")
        assertTrue(v.s in captured, "s is in the modified states")
    }

    @Test
    fun modifiedStatesIsEmptyForActionThatWritesNothing() {
        val v = ApiVault()
        var captured: Set<State<*>> = setOf(v.n)
        v action {
            captured = activeTransaction!!.modifiedStates
        }
        assertEquals(emptySet(), captured)
    }
}
