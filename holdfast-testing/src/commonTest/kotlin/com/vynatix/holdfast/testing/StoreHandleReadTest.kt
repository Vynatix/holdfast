package com.vynatix.holdfast.testing

import com.vynatix.holdfast.Store
import kotlin.test.Test
import kotlin.test.assertEquals

private class CounterVault : Store<CounterVault>() {
    val count by state { 42 }
    val label by state { "init" }
}

class StoreHandleReadTest {
    @Test
    fun readEvaluatesAgainstLiveState() =
        vaultTest {
            val ctr = track(CounterVault())
            assertEquals(42, ctr.read { count.value })
        }

    @Test
    fun readReturnsBlockResult() =
        vaultTest {
            val ctr = track(CounterVault())
            val combined = ctr.read { "${label.value}-${count.value}" }
            assertEquals("init-42", combined)
        }

    @Test
    fun readSeesMutationsViaAction() =
        vaultTest {
            val ctr = track(CounterVault())
            ctr.store action { count mutate 100 }
            assertEquals(100, ctr.read { count.value })
        }
}
