package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.Store
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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

/**
 * A state initializer may read states but not write them (issue #20, R5/D3):
 * the suspending entrypoints refuse to run inside one, like the blocking
 * `action` and `atomic` do.
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
}
