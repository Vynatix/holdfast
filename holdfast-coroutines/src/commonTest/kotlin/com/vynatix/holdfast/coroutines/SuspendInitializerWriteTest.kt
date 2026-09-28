@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.EncodedSnapshotView
import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.SchemaVersioned
import com.vynatix.holdfast.SnapshotMigrationException
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreSnapshot
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.bridge.IntCodec
import com.vynatix.holdfast.restore
import com.vynatix.holdfast.snapshot
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

private class SuspendWritingInitStore : Store<SuspendWritingInitStore>() {
    val target by state { 0 }
    val viaAction by state {
        runBlocking { suspendAction { target mutate 1 } }
        1
    }
    val viaFrame by state {
        runBlocking { suspendAtomic(this@SuspendWritingInitStore) { } }
        2
    }
}

/** Schema 1 of [SuspendMigratingStore]. */
private class SuspendSchemaOne : Store<SuspendSchemaOne>() {
    val n by state(codec = IntCodec) { 0 }
}

/** Schema 2, whose migrate runs [attempt]. */
private class SuspendMigratingStore(
    private val attempt: SuspendMigratingStore.() -> Unit,
) : Store<SuspendMigratingStore>(),
    SchemaVersioned {
    val n by state(codec = IntCodec) { 0 }

    override val schemaVersion: Int get() = 2

    override fun migrate(
        from: Int,
        view: EncodedSnapshotView,
    ) = attempt()
}

/**
 * A state initializer may read states but not write them (issue #20, R5/D3):
 * the suspending entrypoints refuse to run inside one, like the blocking
 * `action` and `atomic` do. So may a schema migration (`SchemaVersioned.migrate`,
 * issue #20, R2).
 */
class SuspendInitializerWriteTest {
    @Test
    fun suspend_action_inside_an_initializer_is_refused() {
        val v = SuspendWritingInitStore()
        val e = assertFailsWith<IllegalStateException> { v.viaAction }
        assertContains(e.message.orEmpty(), "run suspendAction on SuspendWritingInitStore")
        assertContains(e.message.orEmpty(), "initializer of SuspendWritingInitStore.viaAction")
        assertEquals(0, v.target.value, "the refused write never landed")
    }

    @Test
    fun suspend_atomic_inside_an_initializer_is_refused() {
        val v = SuspendWritingInitStore()
        val e = assertFailsWith<IllegalStateException> { v.viaFrame }
        assertContains(e.message.orEmpty(), "open a suspendAtomic(...) frame")
        assertContains(e.message.orEmpty(), "initializer of SuspendWritingInitStore.viaFrame")
    }

    @Test
    fun suspend_action_and_suspend_atomic_inside_a_migration_are_refused() {
        val text = SuspendSchemaOne().snapshot().encode()
        val attempts: List<Pair<String, SuspendMigratingStore.() -> Unit>> =
            listOf(
                "run suspendAction on SuspendMigratingStore" to { runBlocking { suspendAction { n mutate 1 } } },
                "open a suspendAtomic(...) frame" to {
                    val self = this
                    runBlocking { suspendAtomic(self) { } }
                },
            )
        for ((expected, attempt) in attempts) {
            val store = SuspendMigratingStore(attempt)
            val result = store.restore(StoreSnapshot.decode(text))
            val failure = assertIs<SnapshotMigrationException>(assertIs<TransactionResult.Error>(result).exception)
            val refused = assertIs<IllegalStateException>(failure.cause, "$expected: the refusal is attached")
            assertContains(refused.message.orEmpty(), expected)
            assertContains(refused.message.orEmpty(), "SuspendMigratingStore.migrate(from = 1) is running")
            assertEquals(0, store.n.value, "the refused write never landed")
        }
    }
}
