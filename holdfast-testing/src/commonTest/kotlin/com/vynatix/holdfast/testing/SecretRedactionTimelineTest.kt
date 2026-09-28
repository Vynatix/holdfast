@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.testing

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Redacted
import com.vynatix.holdfast.StateTag
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.crypto.EncryptingTransformer
import com.vynatix.holdfast.crypto.XorCipher
import com.vynatix.holdfast.derived
import com.vynatix.holdfast.testing.bridge.RecordingBridge
import com.vynatix.holdfast.testing.matcher.shouldFire
import com.vynatix.holdfast.testing.matcher.shouldFireInOrder
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

private val WALLET_SEED = "timeline-redaction-seed".encodeToByteArray()

private const val KEY = "k3y-5ECRET-01"
private const val SEED_PHRASE = "s33d-5ECRET-02"
private const val STORED = "st0red-5ECRET-03"

private class WalletStore : Store<WalletStore>() {
    val owner by state { "nobody" }
    val key by state(tags = setOf(StateTag.Secret)) { "" }
    val seedPhrase by state(transformer = EncryptingTransformer(XorCipher(WALLET_SEED)), tags = setOf(StateTag.Secret)) { "" }
}

/**
 * A Secret state's value never appears in a `:holdfast-testing` timeline
 * (issue #20, R3, acceptance 1, timeline part): emissions and bridge events
 * record `Redacted`, and matching a Secret state's value is refused with a
 * teaching error, while matching that it emitted stays allowed.
 */
class SecretRedactionTimelineTest {
    private val secrets = listOf(KEY, SEED_PHRASE, STORED, XorCipher(WALLET_SEED).encrypt(SEED_PHRASE))

    private fun assertNoSecretIn(events: List<StoreEvent>) {
        assertTrue(events.isNotEmpty())
        val printed = events.joinToString("\n")
        secrets.forEach { secret -> assertFalse(secret in printed, "the timeline holds a secret:\n$printed") }
    }

    @Test fun emissionsOfASecretStateRecordRedacted() =
        storeTest {
            val handle = track(WalletStore())
            handle.action {
                owner mutate "ada"
                key mutate KEY
                seedPhrase mutate SEED_PHRASE
            }
            handle.action { key mutate "$KEY-rotated" }

            assertNoSecretIn(handle.timeline)
            val keyEmissions = handle.emissions(WalletStore::key)
            assertEquals(2, keyEmissions.size, "the emissions are recorded, only their values are withheld")
            keyEmissions.forEach {
                assertSame(Redacted, it.oldValue)
                assertSame(Redacted, it.newValue)
            }
            assertSame(Redacted, handle.emissions(WalletStore::seedPhrase).single().newValue)
            assertEquals("ada", handle.emissions(WalletStore::owner).single().newValue, "other states keep their values")
        }

    @Test fun bridgeEventsOfASecretStateRecordRedacted() =
        storeTest {
            val bridge = RecordingBridge(initial = STORED)
            val store = WalletStore().also { w -> w { key bridge bridge } }
            val handle = track(store)
            handle.action { key mutate KEY }

            assertNoSecretIn(handle.timeline)
            val events = handle.bridgeEvents(WalletStore::key)
            assertTrue(events.any { it is BridgePublished } && events.any { it is BridgeObserved }, "$events")
            events.forEach { event ->
                val value = if (event is BridgePublished) event.value else (event as BridgeObserved).value
                assertSame(Redacted, value)
            }
            assertEquals(KEY, bridge.published.last(), "the app's own bridge still gets the real value")
        }

    @Test fun aDerivedOfASecretIsRedactedToo() =
        storeTest {
            val store = WalletStore()
            val (keyLength, disposable) = store.derived(store.key) { key.value.length }
            try {
                val handle = track(store)
                handle.action { key mutate KEY }
                val emitted = handle.timeline.filterIsInstance<EmissionEvent>().filter { it.state === keyLength }
                assertEquals(listOf<Any?>(Redacted), emitted.map { it.newValue })
            } finally {
                disposable.dispose()
            }
        }

    @Test fun matchingThatASecretStateEmittedIsAllowed() =
        storeTest {
            val handle = track(WalletStore())
            handle.action { key mutate KEY }
            handle shouldFire { emitted(WalletStore::key) }
            handle shouldFireInOrder {
                started
                emitted(WalletStore::key)
                committed
            }
        }

    @Test fun matchingASecretStatesValueIsRefused() =
        storeTest {
            val handle = track(WalletStore())
            handle.action {
                owner mutate "ada"
                key mutate KEY
            }
            val refused = assertFailsWith<IllegalArgumentException> { handle shouldFire { emitted(WalletStore::key, KEY) } }
            assertContains(refused.message.orEmpty(), "Secret state")
            assertContains(refused.message.orEmpty(), "emitted(key)")
            assertFalse(KEY in refused.message.orEmpty(), "the refusal does not quote the value it was given")
            handle shouldFire { emitted(WalletStore::owner, "ada") } // a non-Secret state still matches by value
        }
}
