package com.vynatix.holdfast

import com.vynatix.holdfast.testing.bridge.RecordingBridge
import com.vynatix.holdfast.testing.matcher.shouldBeSuccess
import com.vynatix.holdfast.testing.matcher.shouldFire
import com.vynatix.holdfast.testing.matcher.shouldNotFire
import com.vynatix.holdfast.testing.storeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Issue #007: the `distinct` default flipped to `true` at every state
 * declaration site. These pins hold the new default semantics: a same-value
 * commit skips observer fanout AND outbound bridge publish, `distinct = false`
 * restores the re-fire contract, and a `restore()` whose values are unchanged
 * fires nothing. Inbound bridge apply (`applyFromBridge`) staying unconditional
 * is pinned by `BridgeTest` (frozen), not here.
 */
private class DistinctVault : Store<DistinctVault>() {
    val n by state { 0 }
    val plain by state(distinct = false) { 0 }
}

class DistinctDefaultTest {
    @Test
    fun sameValueCommitSkipsObserverFanoutAndBridgePublishByDefault() =
        storeTest {
            val v = DistinctVault()
            val bridge = RecordingBridge<Int>(initial = 0)
            v { n bridge bridge }
            val handle = track(v)

            val seen = mutableListOf<Int>()
            val sub = v { n effect { seen.add(this) } }
            seen.clear() // drop the initial fire

            handle.action { n mutate 0 }.shouldBeSuccess() // same value as committed
            handle.action { n mutate 0 }.shouldBeSuccess()

            assertEquals(emptyList(), seen, "a same-value commit skips observer fanout on a default (distinct) state")
            assertEquals(emptyList(), bridge.published, "a same-value commit publishes nothing to the bridge")
            handle shouldNotFire { bridgePublished(DistinctVault::n) }

            // Control: a changed value still fans out and publishes.
            handle.action { n mutate 1 }.shouldBeSuccess()
            assertEquals(listOf(1), seen, "a changed value fires observers")
            assertEquals(listOf(1), bridge.published, "a changed value publishes to the bridge")
            handle shouldFire { bridgePublished(DistinctVault::n) }
            sub.dispose()
        }

    @Test
    fun distinctFalseRestoresReFireOnSameValueCommit() =
        storeTest {
            val v = DistinctVault()
            val bridge = RecordingBridge<Int>(initial = 0)
            v { plain bridge bridge }
            val handle = track(v)

            val seen = mutableListOf<Int>()
            val sub = v { plain effect { seen.add(this) } }
            seen.clear() // drop the initial fire

            handle.action { plain mutate 0 }.shouldBeSuccess()
            handle.action { plain mutate 0 }.shouldBeSuccess()

            assertEquals(
                listOf(0, 0),
                seen,
                "distinct = false re-fires observers on every same-value commit",
            )
            assertEquals(
                listOf(0, 0),
                bridge.published,
                "distinct = false re-publishes to the bridge on every same-value commit",
            )
            sub.dispose()
        }

    @Test
    fun restoreWithUnchangedValuesFiresNoObserversAndNoBridgePublishes() =
        storeTest {
            val v = DistinctVault()
            val bridge = RecordingBridge<Int>(initial = 0)
            v { n bridge bridge }
            val handle = track(v)

            handle.action { n mutate 7 }.shouldBeSuccess()
            val snap = v.snapshot() // holds n = 7, exactly what is committed

            val seen = mutableListOf<Int>()
            val sub = v { n effect { seen.add(this) } }
            seen.clear() // drop the initial fire

            v.restore(snap).shouldBeSuccess() // values unchanged

            assertEquals(emptyList(), seen, "an unchanged-value restore fires no observers (distinct default)")
            assertEquals(listOf(7), bridge.published, "an unchanged-value restore publishes nothing new")

            // Changed values are unaffected: mutate away, then restore back —
            // both the commit and the restore fire and publish.
            handle.action { n mutate 9 }.shouldBeSuccess()
            v.restore(snap).shouldBeSuccess()

            assertEquals(listOf(9, 7), seen, "a changed-value commit and the restore that undoes it both fire")
            assertEquals(listOf(7, 9, 7), bridge.published, "a changed-value commit and the restoring publish both land")
            sub.dispose()
        }
}
