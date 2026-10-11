package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.Disposable
import com.vynatix.holdfast.State
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.effect
import com.vynatix.holdfast.restore
import com.vynatix.holdfast.snapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

private class RestorableStore(
    scope: CoroutineScope,
) : Store<RestorableStore>() {
    val n by state { 0 }
    val label by state { "init" }

    init {
        bindToScope(scope)
    }
}

/**
 * A `suspendDerived`'s backing state in snapshots (issue #20, R5/D6): captured
 * for an undo on the same store, hidden from `stateNames`, and skipped when
 * the snapshot is restored into another instance, whose own `suspendDerived`
 * has a backing state of its own.
 */
class SuspendDerivedRestoreTest {
    private val disposables = mutableListOf<Disposable>()
    private val scopes = mutableListOf<CoroutineScope>()

    @AfterTest fun cleanup() {
        disposables.forEach { runCatching { it.dispose() } }
        scopes.forEach { runCatching { it.cancel() } }
    }

    private fun newStore(): RestorableStore = RestorableStore(CoroutineScope(SupervisorJob()).also { scopes += it })

    private fun RestorableStore.doubled(): State<Int> {
        val (derived, d) = suspendDerived(n) { n.value * 2 }
        disposables += d
        return derived
    }

    private suspend fun State<Int>.awaitValue(expected: Int) {
        withTimeout(2_000) { while (value != expected) delay(5) }
    }

    @Test
    fun backing_state_is_hidden_from_state_names_and_restored_by_a_same_store_undo() =
        runBlocking {
            val v = newStore()
            val doubled = v.doubled()
            v action { n mutate 3 }
            doubled.awaitValue(6)

            val snap = v.snapshot()
            assertEquals(setOf("n", "label"), snap.stateNames, "the backing state is not a declared state")

            v action { n mutate 5 }
            doubled.awaitValue(10)

            // What an observer of the source sees of the derived during the
            // restore's own commit. The fanout runs under the store lock and
            // the recompute is only launched from postCommit after it, so this
            // read cannot see the async recompute's result.
            val seenInRestoreCommit = mutableListOf<Int>()
            val sub = v.n effect { seenInRestoreCommit += doubled.value }
            seenInRestoreCommit.clear() // drop effect's initial fire
            val r =
                try {
                    v.restore(snap)
                } finally {
                    sub.dispose()
                }
            assertIs<TransactionResult.Success<Unit>>(r)
            assertEquals(listOf(6), seenInRestoreCommit, "backing restored in the restore's own commit")
            assertEquals(3, v.n.value)
            doubled.awaitValue(6) // and the follow-up recompute agrees
        }

    @Test
    fun a_snapshot_with_a_suspend_derived_restores_into_another_instance() =
        runBlocking {
            val a = newStore()
            a.doubled()
            a action { n mutate 4 }
            val snap = a.snapshot()

            val b = newStore()
            val doubledB = b.doubled()
            // a's backing state is unknown to b; it used to fail the restore
            // as a state b does not declare.
            val r = b.restore(snap)
            assertIs<TransactionResult.Success<Unit>>(r)
            assertEquals(4, b.n.value)
            doubledB.awaitValue(8)
        }
}
