@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.testing

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Redacted
import com.vynatix.holdfast.StateTag
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.keyedState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val VAULT_KEY = "vault-key-5ECRET"
private const val TOKEN = "t0ken-5ECRET"

private class SessionsStore : Store<SessionsStore>() {
    val visits by keyedState<String, Int> { 0 }
    val tokens by keyedState<String, String>(tags = setOf(StateTag.Secret)) { "" }
}

/**
 * Keyed-state entries in a `:holdfast-testing` timeline (issue #20, R7): an
 * entry's write is an [EmissionEvent] naming the entry's State, like any
 * state's; a Secret family's entries record [Redacted]; and nothing the
 * harness prints names a key — an entry's State prints as `Store.family[*]`.
 * An eviction is a transaction of its own with no emission for the entry.
 */
class KeyedStateTimelineTest {
    @Test fun anEntrysWritesAreEmissionsOfItsState() =
        storeTest {
            val store = SessionsStore()
            val handle = track(store)
            val home = store.visits["home"]

            handle.action {
                visits["home"] update { it + 1 }
                visits["about"] mutate 5
            }

            val emissions = handle.timeline.filterIsInstance<EmissionEvent>()
            val forHome = emissions.single { it.state === home }
            assertEquals(0, forHome.oldValue)
            assertEquals(1, forHome.newValue)
            assertEquals(5, emissions.single { it.state === store.visits["about"] }.newValue)
        }

    @Test fun anEvictionIsATransactionWithoutAnEmissionForTheEntry() =
        storeTest {
            val store = SessionsStore()
            val handle = track(store)
            val home = store.visits["home"]

            handle.action { visits.evict("home") }

            assertTrue(handle.timeline.any { it is TransactionCommitted })
            assertTrue(handle.timeline.none { it is EmissionEvent && it.state === home })
            assertFalse("home" in store.visits)
        }

    @Test fun aSecretFamilysEntriesRecordRedactedAndNoKeyIsPrinted() =
        storeTest {
            val store = SessionsStore()
            val handle = track(store)

            handle.action { tokens[VAULT_KEY] mutate TOKEN }

            val emission = handle.timeline.filterIsInstance<EmissionEvent>().single()
            assertSame(Redacted, emission.oldValue)
            assertSame(Redacted, emission.newValue)
            val printed = handle.timeline.joinToString("\n")
            assertFalse(TOKEN in printed, "the value is withheld:\n$printed")
            assertFalse(VAULT_KEY in printed, "and no key is printed:\n$printed")
            assertTrue("SessionsStore.tokens[*]" in printed, "an entry prints as its family:\n$printed")
        }
}
