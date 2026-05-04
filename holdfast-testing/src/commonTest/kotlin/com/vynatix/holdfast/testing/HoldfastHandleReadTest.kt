package com.vynatix.holdfast.testing

import com.vynatix.holdfast.Holdfast
import kotlin.test.Test
import kotlin.test.assertEquals

private class CounterVault : Holdfast<CounterVault>() {
    val count by state { 42 }
    val label by state { "init" }
}

class VaultHandleReadTest {

    @Test
    fun readEvaluatesAgainstLiveState() = vaultTest {
        val ctr = track(CounterVault())
        assertEquals(42, ctr.read { count.value })
    }

    @Test
    fun readReturnsBlockResult() = vaultTest {
        val ctr = track(CounterVault())
        val combined = ctr.read { "${label.value}-${count.value}" }
        assertEquals("init-42", combined)
    }

    @Test
    fun readSeesMutationsViaAction() = vaultTest {
        val ctr = track(CounterVault())
        ctr.vault action { count mutate 100 }
        assertEquals(100, ctr.read { count.value })
    }
}
