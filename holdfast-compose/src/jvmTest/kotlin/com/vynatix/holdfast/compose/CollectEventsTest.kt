package com.vynatix.holdfast.compose

import androidx.compose.runtime.mutableStateOf
import com.vynatix.holdfast.EventfulStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private sealed class Tick {
    data object Saved : Tick()

    data class Failed(
        val why: String,
    ) : Tick()
}

private class TickerStore : EventfulStore<TickerStore, Tick>() {
    val n by state { 0 }

    fun save() =
        action {
            n update { it + 1 }
            emit(Tick.Saved)
        }
}

/**
 * The event-collection entrypoint (ROADMAP 0.6.0, E1): events are delivered
 * to the callback in emission order while subscribed, with the `replay = 0`
 * contract stated honestly — anything emitted before the composition's
 * collector subscribes is never delivered. The handler always sees the
 * latest recomposition's lambda, a different store restarts the collection,
 * and a store already disposed at composition fails fast instead of
 * silently collecting a flow that can never emit again.
 */
class CollectEventsTest {
    @Test
    fun eventsAreCollectedInEmissionOrderWhileSubscribed() =
        runTest {
            val store = TickerStore()
            val ui = HeadlessComposition(this)
            val received = mutableListOf<Tick>()
            ui.setContent { store.collectEvents { received += it } }

            store.save()
            ui.settle()
            store action { emit(Tick.Failed("offline")) }
            ui.settle()
            assertEquals(listOf(Tick.Saved, Tick.Failed("offline")), received)
            ui.dispose()
        }

    @Test
    fun eventsEmittedBeforeTheCollectorSubscribesAreNotDelivered() =
        runTest {
            val store = TickerStore()
            // No subscriber yet: the events SharedFlow (replay = 0) drops it.
            store.save()
            val ui = HeadlessComposition(this)
            val received = mutableListOf<Tick>()
            ui.setContent { store.collectEvents { received += it } }

            store action { emit(Tick.Failed("later")) }
            ui.settle()
            assertEquals(
                // The explicit <Tick> matters: kotlin.test's assertEquals is
                // @OnlyInputTypes, and List<Tick.Failed> shares no subtype
                // path with MutableList<Tick>.
                listOf<Tick>(Tick.Failed("later")),
                received,
                "the pre-subscription Saved event is gone — no replay, by design",
            )
            ui.dispose()
        }

    @Test
    fun theHandlerAlwaysSeesTheLatestRecomposition() =
        runTest {
            val store = TickerStore()
            val ui = HeadlessComposition(this)
            val label = mutableStateOf("first")
            val received = mutableListOf<String>()
            ui.setContent {
                val current = label.value
                store.collectEvents { event -> received += "$current:$event" }
            }

            label.value = "second"
            ui.settle()
            store action { emit(Tick.Saved) }
            ui.settle()
            assertEquals(
                listOf("second:Saved"),
                received,
                "the collector must not capture the first composition's handler",
            )
            ui.dispose()
        }

    @Test
    fun aDifferentStoreRestartsTheCollection() =
        runTest {
            val a = TickerStore()
            val b = TickerStore()
            val ui = HeadlessComposition(this)
            val which = mutableStateOf(a)
            val received = mutableListOf<String>()
            ui.setContent { which.value.collectEvents { received += "${it.javaClass.simpleName}" } }

            a action { emit(Tick.Saved) }
            ui.settle()
            assertEquals(listOf("Saved"), received)

            which.value = b
            ui.settle()
            a action { emit(Tick.Failed("to the old store")) }
            b action { emit(Tick.Saved) }
            ui.settle()
            assertEquals(
                listOf("Saved", "Saved"),
                received,
                "after the switch only the new store's events are collected",
            )
            ui.dispose()
        }

    @Test
    fun aStoreAlreadyDisposedAtCompositionFailsFast() =
        runTest {
            val store = TickerStore()
            store.dispose()
            val ui = HeadlessComposition(this)
            assertFailsWith<IllegalStateException> {
                ui.setContent { store.collectEvents { /* never reached */ } }
            }
            ui.dispose()
        }
}
