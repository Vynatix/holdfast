package com.vynatix.holdfast.testing.bridge

import com.vynatix.holdfast.Store
import com.vynatix.holdfast.testing.matcher.shouldBeSuccess
import com.vynatix.holdfast.testing.matcher.shouldHaveLastPublished
import com.vynatix.holdfast.testing.matcher.shouldHavePublished
import com.vynatix.holdfast.testing.matcher.shouldHavePublishedInOrder
import com.vynatix.holdfast.testing.storeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class TextVault : Store<TextVault>() {
    val text by state { "" }
}

class BridgeViewTest {
    @Test
    fun bridgeViewOverRecordingBridgeReadsPublished() {
        val bridge = RecordingBridge<String>(initial = "init")
        bridge.publish("a")
        bridge.publish("b")

        val view = BridgeView(bridge)
        assertEquals(listOf("a", "b"), view.published)
        assertEquals("b", view.lastPublished)
    }

    @Test
    fun bridgeViewOverLatchedBridgeReadsPublished() {
        val bridge = LatchedBridge<String>()
        bridge.publish("x")
        bridge.publish("y")

        val view = BridgeView(bridge)
        assertEquals(listOf("x", "y"), view.published)
        assertEquals("y", view.lastPublished)
    }

    @Test
    fun bridgeViewLastPublishedIsNullBeforeAnyPublish() {
        val bridge = RecordingBridge<String>(initial = "init")
        val view = BridgeView(bridge)
        assertNull(view.lastPublished)
        assertTrue(view.published.isEmpty())
    }

    @Test
    fun receivingFeedsObserverThroughRecordingBridge() {
        val bridge = RecordingBridge<String>(initial = "init")
        val received = mutableListOf<String>()
        bridge.observe { received.add(it) }
        // Drop the initial replay.
        received.clear()

        val view = BridgeView(bridge)
        view receiving "in1"
        view receiving "in2"
        assertEquals(listOf("in1", "in2"), received)
    }

    @Test
    fun receivingFeedsObserverThroughLatchedBridge() {
        val bridge = LatchedBridge<String>()
        val received = mutableListOf<String>()
        bridge.observe { received.add(it) }

        val view = BridgeView(bridge)
        view receiving "in1"
        assertEquals(listOf("in1"), received)
    }

    @Test
    fun handleBridgeLookupReturnsViewForAttachedBridge() =
        storeTest {
            val bridge = RecordingBridge<String>(initial = "init")
            val ctr =
                track(
                    TextVault().also { v ->
                        v { text bridge bridge }
                    },
                )
            ctr.action { text mutate "hello" }.shouldBeSuccess()

            val view = ctr.bridge(TextVault::text)
            assertEquals(listOf("hello"), view.published)
            assertEquals("hello", view.lastPublished)
        }

    @Test
    fun handleBridgeLookupThrowsWhenNoBridgeAttached() =
        storeTest {
            val ctr = track(TextVault())
            val ex = assertFailsWith<IllegalStateException> { ctr.bridge(TextVault::text) }
            // Error message identifies the missing bridge by property name.
            assertTrue(
                ex.message.orEmpty().contains("text"),
                "expected error message to mention property 'text', got: ${ex.message}",
            )
        }

    @Test
    fun handleBridgeLookupReceivingPushesIntoState() =
        storeTest {
            val bridge = RecordingBridge<String>(initial = "")
            val ctr =
                track(
                    TextVault().also { v ->
                        v { text bridge bridge }
                    },
                )

            // Typed lookup (F24): no cast needed — the view is BridgeView<String>.
            val view: BridgeView<String> = ctr.bridge(TextVault::text)
            view receiving "external"
            assertEquals("external", ctr.read { text.value })
        }

    @Test
    fun bridgeMatchersDelegateToView() {
        val bridge = RecordingBridge<String>(initial = "init")
        bridge.publish("a")
        bridge.publish("b")
        bridge.publish("c")

        val view = BridgeView(bridge)
        view shouldHavePublished "b"
        view shouldHavePublishedInOrder listOf("a", "b", "c")
        view shouldHaveLastPublished "c"
    }

    @Test
    fun shouldHavePublishedFailsWithHelpfulMessage() {
        val bridge = RecordingBridge<String>(initial = "init")
        bridge.publish("a")
        val view = BridgeView(bridge)

        val err = assertFailsWith<AssertionError> { view shouldHavePublished "missing" }
        val msg = err.message.orEmpty()
        assertTrue(msg.contains("missing"), "expected message to mention 'missing', got: $msg")
        assertTrue(msg.contains("[a]"), "expected message to list history '[a]', got: $msg")
    }

    @Test
    fun shouldHavePublishedInOrderFailsOnExtraValue() {
        val bridge = RecordingBridge<String>(initial = "init")
        bridge.publish("a")
        bridge.publish("b")
        val view = BridgeView(bridge)

        val err = assertFailsWith<AssertionError> { view shouldHavePublishedInOrder listOf("a") }
        val msg = err.message.orEmpty()
        assertTrue(msg.contains("expected="), "expected 'expected=' in message, got: $msg")
        assertTrue(msg.contains("actual="), "expected 'actual=' in message, got: $msg")
    }

    @Test
    fun shouldHaveLastPublishedFailsOnMismatch() {
        val bridge = RecordingBridge<String>(initial = "init")
        bridge.publish("a")
        val view = BridgeView(bridge)

        val err = assertFailsWith<AssertionError> { view shouldHaveLastPublished "b" }
        val msg = err.message.orEmpty()
        assertTrue(msg.contains("a"), "expected 'a' in message, got: $msg")
        assertTrue(msg.contains("b"), "expected 'b' in message, got: $msg")
    }
}
