package com.vynatix.holdfast.testing

import com.vynatix.holdfast.MutableState
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.testing.bridge.BridgeView
import com.vynatix.holdfast.testing.bridge.RecordingBridge
import com.vynatix.holdfast.testing.concurrency.parallel
import com.vynatix.holdfast.testing.matcher.shouldHavePublished
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class RcStore : Store<RcStore>() {
    val x by state { 0 }
}

/**
 * Two handles built at once for one store (`parallel { track(store) }`, or
 * the tree fixture adopting a keyed store from its constructing thread while
 * the test thread tracks it): whichever re-attached a state's bridge first
 * owns its wrapper, and the handle that loses the registration must not
 * leave that wrapper feeding its cleared recorder while the winner sees no
 * publish. The lost race is staged in order through the registry's own
 * insert-or-yield step.
 */
class HandleRegistryRaceTest {
    private fun bridged(): Pair<RcStore, RecordingBridge<Int>> {
        val store = RcStore()
        val bridge = RecordingBridge(0)
        store { x bridge bridge }
        return store to bridge
    }

    private fun StoreHandle<RcStore>.publishedValues(): List<Any?> =
        bridgeEvents(RcStore::x).filterIsInstance<BridgePublished>().map { it.value }

    @Test
    fun aLosingRacerHandsItsBridgeWrapperToTheWinner() =
        storeTest {
            val (store, _) = bridged()
            val loser = StoreHandle(store, Capture.All) // built first: wrapped x
            val winner = StoreHandle(store, Capture.All) // built second: found x wrapped, skipped it
            assertSame(winner, registry.register(winner)) // registered first
            assertSame(winner, registry.register(loser)) // lost the race
            assertSame(winner, track(store))
            store action { x mutate 7 }
            assertEquals(listOf<Any?>(7), winner.publishedValues(), "the winner's timeline sees the publish")

            @Suppress("UNCHECKED_CAST")
            val view = winner.bridge(RcStore::x) as BridgeView<Int>
            view shouldHavePublished 7
            assertTrue(loser.timeline.isEmpty(), "the loser's recorder is gone")
        }

    @Test
    fun aLosingRacerUnwrapsWhenTheWinnerRecordsNothing() =
        storeTest {
            val (store, bridge) = bridged()
            val loser = StoreHandle(store, Capture.All)
            val winner = StoreHandle(store, Capture.None)
            assertSame(winner, registry.register(winner))
            assertSame(winner, registry.register(loser))

            @Suppress("UNCHECKED_CAST")
            val attached = (store.x as MutableState<Int>).bridge
            assertSame(bridge, attached, "the state's own bridge is back")
            store action { x mutate 3 }
            assertEquals(listOf(3), bridge.published)
        }

    @Test
    fun parallelTrackingSeesEveryPublishWhoeverWins() =
        storeTest {
            repeat(20) { round ->
                val (store, _) = bridged()
                val handles = parallel(2) { track(store) }
                assertSame(handles[0], handles[1], "one handle per store")
                store action { x mutate round + 1 }
                assertEquals(listOf<Any?>(round + 1), track(store).publishedValues(), "round $round")
            }
        }
}
