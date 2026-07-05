package com.vynatix.holdfast.testing.bridge

import com.vynatix.holdfast.Disposable
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.testing.BridgeObserved
import com.vynatix.holdfast.testing.BridgePublished
import com.vynatix.holdfast.testing.matcher.shouldBeSuccess
import com.vynatix.holdfast.testing.storeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class ThemeVault : Store<ThemeVault>() {
    val theme by state { "light" }
}

class RecordingBridgeTest {
    @Test
    fun publishedListInitiallyEmptyAndLastNull() {
        val bridge = RecordingBridge<String>(initial = "light")
        assertTrue(bridge.published.isEmpty())
        assertNull(bridge.lastPublished)
    }

    @Test
    fun publishAccumulatesValuesInCallOrder() {
        val bridge = RecordingBridge<String>(initial = "light")
        assertTrue(bridge.publish("dark"))
        assertTrue(bridge.publish("auto"))
        assertEquals(listOf("dark", "auto"), bridge.published)
        assertEquals("auto", bridge.lastPublished)
    }

    @Test
    fun observeReplaysInitialOnAttachAndCapturesObserver() {
        val bridge = RecordingBridge<String>(initial = "light")
        val received = mutableListOf<String>()
        val sub: Disposable = bridge.observe { received.add(it) }
        // observe replays initial through the observer once.
        assertEquals(listOf("light"), received)
        // simulateInbound feeds the captured observer.
        bridge.simulateInbound("dark")
        assertEquals(listOf("light", "dark"), received)
        sub.dispose()
    }

    @Test
    fun simulateInboundIsNoOpBeforeAttach() {
        val bridge = RecordingBridge<String>(initial = "light")
        // No observer registered yet — this should silently do nothing.
        bridge.simulateInbound("dark")
        assertTrue(bridge.published.isEmpty())
    }

    @Test
    fun disposingObserverStopsInboundDelivery() {
        val bridge = RecordingBridge<String>(initial = "light")
        val received = mutableListOf<String>()
        val sub = bridge.observe { received.add(it) }
        sub.dispose()
        bridge.simulateInbound("dark")
        // Only the initial replay landed before dispose.
        assertEquals(listOf("light"), received)
    }

    @Test
    fun publishedReturnsDefensiveCopy() {
        val bridge = RecordingBridge<String>(initial = "")
        bridge.publish("a")
        val snapshot = bridge.published
        bridge.publish("b")
        // First snapshot must not see the second publish.
        assertEquals(listOf("a"), snapshot)
        assertEquals(listOf("a", "b"), bridge.published)
    }

    @Test
    fun integrationWithVaultPublishesOnCommit() =
        storeTest {
            val bridge = RecordingBridge<String>(initial = "light")
            val ctr =
                track(
                    ThemeVault().also { v ->
                        v { theme bridge bridge }
                    },
                )
            ctr.action { theme mutate "dark" }.shouldBeSuccess()

            // The bridge sees the post-commit publish.
            assertEquals("dark", bridge.lastPublished)
        }

    @Test
    fun integrationWithVaultEmitsBridgePublishedEvent() =
        storeTest {
            val bridge = RecordingBridge<String>(initial = "light")
            val ctr =
                track(
                    ThemeVault().also { v ->
                        v { theme bridge bridge }
                    },
                )
            ctr.action { theme mutate "dark" }.shouldBeSuccess()

            // The recorder wrapped the bridge at install time and pushed
            // BridgePublished into the timeline.
            val published = ctr.timeline.filterIsInstance<BridgePublished>()
            assertEquals(1, published.size, "expected 1 BridgePublished, got ${ctr.timeline}")
            assertEquals("dark", published.single().value)
        }

    @Test
    fun integrationWithVaultEmitsBridgeObservedOnReAttach() =
        storeTest {
            val bridge = RecordingBridge<String>(initial = "light")
            val v = ThemeVault().also { v -> v { theme bridge bridge } }
            // The wrapping by track() re-attaches the bridge, which replays
            // 'light' through the observer — that re-attach produces a
            // BridgeObserved event.
            val ctr = track(v)
            val observed = ctr.timeline.filterIsInstance<BridgeObserved>()
            assertEquals(1, observed.size, "expected 1 BridgeObserved replay, got ${ctr.timeline}")
            assertEquals("light", observed.single().value)
        }

    @Test
    fun simulateInboundUpdatesStateOnAttachedVault() =
        storeTest {
            val bridge = RecordingBridge<String>(initial = "light")
            val ctr =
                track(
                    ThemeVault().also { v ->
                        v { theme bridge bridge }
                    },
                )

            // Inbound update through the bridge changes state.
            bridge.simulateInbound("dark")
            assertEquals("dark", ctr.read { theme.value })
        }

    @Test
    fun bridgeViewLookupReturnsCorrectBridge() =
        storeTest {
            val bridge = RecordingBridge<String>(initial = "light")
            val ctr =
                track(
                    ThemeVault().also { v ->
                        v { theme bridge bridge }
                    },
                )

            ctr.action { theme mutate "dark" }.shouldBeSuccess()
            // Typed lookup (F24): the view is BridgeView<String>, no star projection.
            val view: BridgeView<String> = ctr.bridge(ThemeVault::theme)
            // The published list comes from the wrapper, which intercepted the
            // single commit-time publish.
            assertEquals(listOf("dark"), view.published)
            assertSame("dark", view.lastPublished)
        }
}
