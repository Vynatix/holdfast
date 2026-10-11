package com.vynatix.holdfast.compose

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent

/** The applier of a composition that emits no nodes: its content only reads state. */
private class UnitApplier : AbstractApplier<Unit>(Unit) {
    override fun insertTopDown(
        index: Int,
        instance: Unit,
    ) = Unit

    override fun insertBottomUp(
        index: Int,
        instance: Unit,
    ) = Unit

    override fun remove(
        index: Int,
        count: Int,
    ) = Unit

    override fun move(
        from: Int,
        to: Int,
        count: Int,
    ) = Unit

    override fun onClear() = Unit
}

/**
 * A composition with no UI, driven by hand from a [TestScope]: a [Recomposer]
 * on the test dispatcher and a [BroadcastFrameClock] that only ticks in
 * [settle]. What the content reads, and how many times it runs, is all a test
 * observes — enough to count recompositions without a UI toolkit.
 */
internal class HeadlessComposition(
    private val test: TestScope,
) {
    private val clock = BroadcastFrameClock()
    private val recomposer = Recomposer(test.backgroundScope.coroutineContext + clock)
    private val composition = Composition(UnitApplier(), recomposer)
    private var frameTimeNanos = 0L

    init {
        test.backgroundScope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        test.runCurrent()
    }

    /** Compose [content] once, then [settle] (which also starts its effects, such as `collectAsState`'s). */
    fun setContent(content: @Composable () -> Unit) {
        composition.setContent(content)
        settle()
    }

    /**
     * Deliver the snapshot writes made outside composition (a store commit's
     * observer writing a Compose state, as `collectAsState` does), then send
     * frames until no recomposition is waiting for one.
     */
    fun settle() {
        Snapshot.sendApplyNotifications()
        test.runCurrent()
        var frames = 0
        while (clock.hasAwaiters) {
            check(++frames <= MAX_FRAMES) { "the composition kept requesting frames" }
            clock.sendFrame(frameTimeNanos)
            frameTimeNanos += FRAME_NANOS
            test.runCurrent()
            Snapshot.sendApplyNotifications()
            test.runCurrent()
        }
    }

    fun dispose() {
        composition.dispose()
        recomposer.cancel()
    }

    private companion object {
        const val MAX_FRAMES = 100
        const val FRAME_NANOS = 16_000_000L
    }
}
