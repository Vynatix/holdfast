@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A store with a declared state named like the sealed state a test makes, and a keyed family. */
private class Machine : Store<Machine>() {
    val phase by state { "declared" }
    val other by state { 0 }
    val docs by keyedState<String, Int> { 0 }
}

private const val REFUSAL = "it is the machinery's own. Fix: ask the machinery."

/**
 * The core hooks a store's own machinery drives it through (issue #20, R8;
 * `:holdfast-coroutines`' hydrator uses them): sealed states, which every
 * store write entrypoint refuses and no snapshot, restore or reset sees;
 * staging into them; a top-level transaction for a caller that holds the
 * store; and forbidding `removeState`/`clearStates` inside a transaction.
 */
class SealedStateTest {
    @Test fun aSealedStateRefusesEveryStoreWriteWithItsOwnersTeachingText() {
        val store = Machine()
        val sealed = store.internalSealedState("phase", "idle", REFUSAL)
        val attempts =
            listOf<() -> Unit>(
                { store { sealed mutate "x" } },
                { store { sealed update { "x" } } },
                { store { sealed bridge null } },
                { store { sealed observeFrom Observable { Disposable { } } } },
                { sealed.bridge = null },
            )
        for (attempt in attempts) {
            val thrown = assertFailsWith<IllegalStateException> { attempt() }
            assertEquals("Cannot write Machine.phase: $REFUSAL", thrown.message)
        }
        assertEquals("idle", sealed.value)
    }

    @Test fun aSealedStateIsNeverCapturedRestoredResetOrListed() {
        val store = Machine()
        val sealed = store.internalSealedState("phase", "idle", REFUSAL)
        store action { store.internalStageSealed(sealed, "busy") }
        store action { phase mutate "changed" }

        val snapshot = store.snapshot()
        assertEquals("changed", snapshot[store.phase], "the name is the declared state's")
        assertFailsWith<IllegalArgumentException> { snapshot[sealed] }
        assertFalse(store.properties.values.any { it === sealed })
        assertTrue(store.taggedStates(StateTag.Remote).none { it === sealed })

        store.restore(StoreSnapshot(mapOf("phase" to "restored")))
        store.reset()
        assertEquals("busy", sealed.value, "neither a restore nor a reset reaches it")
        assertEquals("declared", store.phase.value)
        assertTrue(sealed.tags.isEmpty())
    }

    @Test fun internalStageSealedCommitsAndRollsBackWithTheActionItStagesInto() {
        val store = Machine()
        val sealed = store.internalSealedState("phase", "idle", REFUSAL)
        val fired = mutableListOf<String>()
        sealed effect { fired += this }

        store action {
            store.internalStageSealed(sealed, "busy")
            assertEquals("busy", sealed.value, "read-your-own-writes")
            error("abort")
        }
        assertEquals("idle", sealed.value)
        store action { store.internalStageSealed(sealed, "busy") }
        assertEquals("busy", sealed.value)
        store action { store.internalStageSealed(sealed, "busy") }
        assertEquals(listOf("idle", "busy"), fired, "distinct: an equal value does not fire")
    }

    @Test fun internalStageSealedRefusesOutsideAnActionAClosedTransactionAndAnotherState() {
        val store = Machine()
        val sealed = store.internalSealedState("phase", "idle", REFUSAL)

        val outside = assertFailsWith<IllegalStateException> { store.internalStageSealed(sealed, "busy") }
        assertTrue("none is" in outside.message.orEmpty(), outside.message)
        @Suppress("UNCHECKED_CAST") // Machine.other is declared `state { 0 }`: a MutableState<Int>.
        val declared = store.other as MutableState<Int>
        assertFailsWith<IllegalArgumentException> { store.internalStageSealed(declared, 1) }
        val foreign = Machine().internalSealedState("phase", "idle", REFUSAL)
        assertFailsWith<IllegalArgumentException> { store.internalStageSealed(foreign, "busy") }

        // From an observer of the store's own commit, the transaction has applied.
        var refused: Throwable? = null
        store.uncaughtObserverHandler = { refused = it }
        val subscription = store.other effect { if (this == 1) store.internalStageSealed(sealed, "late") }
        store action { other mutate 1 }
        subscription.dispose()
        assertIs<IllegalStateException>(refused)
        assertTrue("already applied its writes" in refused?.message.orEmpty(), refused?.message)
        assertEquals("idle", sealed.value)
    }

    @Test fun internalStageSealedInAFrameThatDoesNotEnrollTheStoreIsAnEscape() {
        val store = Machine()
        val bystander = Machine()
        val sealed = store.internalSealedState("phase", "idle", REFUSAL)
        val result =
            store action {
                atomic(bystander) { store.internalStageSealed(sealed, "busy") }
            }
        assertIs<UnenrolledStoreException>((result as TransactionResult.Error).exception)
        assertEquals("idle", sealed.value)
    }

    @Test fun internalTopLevelActionRunsTheMiddlewareChainAndCommits() {
        val store = Machine()
        val seen = mutableListOf<String>()
        store.middlewares(
            object : Middleware<Machine>() {
                override fun onTransactionStarted(context: MiddlewareContext<Machine>) {
                    seen += "started ${context.transaction.id}"
                }

                override fun onTransactionCompleted(context: MiddlewareContext<Machine>) {
                    seen += "completed ${context.transaction.id}"
                }
            },
        )
        val result = store.internalTopLevelAction("MachineStep") { other mutate 2 }
        assertIs<TransactionResult.Success<Unit>>(result)
        assertEquals(2, store.other.value)
        assertEquals(listOf("started MachineStep", "completed MachineStep"), seen)

        val failed =
            store.internalTopLevelAction("MachineStep") {
                other mutate 3
                error("abort")
            }
        assertIs<TransactionResult.Error>(failed)
        assertEquals(2, store.other.value)
    }

    @Test fun internalTopLevelActionRefusesToNestAndADisposedStore() {
        val store = Machine()
        val nested = store action { store.internalTopLevelAction("Nested") { } }
        val refusal = (nested as TransactionResult.Error).exception
        assertTrue("its caller must hold the store" in refusal.message.orEmpty(), refusal.message)
        store.dispose()
        assertFailsWith<IllegalStateException> { store.internalTopLevelAction("Late") { } }
    }

    @Test fun structuralWritesAreRefusedWhileTheMarkedTransactionIsActiveOnItsThread() {
        val store = Machine()
        store.phase.value
        store.other.value
        val refused =
            store action {
                action {
                    checkNotNull(activeTransaction).internalForbidStructuralWrites("the machinery is adopting.")
                    val thrown = assertFailsWith<IllegalStateException> { removeState("phase") }
                    assertEquals("Cannot remove state 'phase' from Machine: the machinery is adopting.", thrown.message)
                    assertFailsWith<IllegalStateException> { clearStates() }
                }
                // Its savepoint has ended: the enclosing transaction allows them.
                removeState("other")
            }
        assertIs<TransactionResult.Success<Unit>>(refused)
        assertTrue(store.hasState("phase"))
        assertFalse(store.hasState("other"))
    }

    @Test fun internalQualifiedNameNamesAStateNeverItsValue() {
        val store = Machine()
        assertEquals("Machine.other", store.other.internalQualifiedName)
        assertEquals("Machine.docs[*]", store.docs["secret-key"].internalQualifiedName)
        assertEquals("Machine.phase", store.internalSealedState("phase", "x", REFUSAL).internalQualifiedName)
        assertNull(store.computed { 1 }.internalQualifiedName)
    }
}
