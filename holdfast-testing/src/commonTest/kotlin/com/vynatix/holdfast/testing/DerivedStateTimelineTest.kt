@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.testing

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Redacted
import com.vynatix.holdfast.StateTag
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.derivedState
import com.vynatix.holdfast.merged
import com.vynatix.holdfast.testing.matcher.shouldFire
import com.vynatix.holdfast.testing.matcher.shouldFireInOrder
import com.vynatix.holdfast.testing.matcher.shouldMatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

private class DraftStore : Store<DraftStore>() {
    val draft by state(tags = setOf(StateTag.UserAuthored)) { "" }
    val server by state(tags = setOf(StateTag.Remote)) { "" }
    val pin by state(tags = setOf(StateTag.Secret)) { "" }
    val shown by merged(draft, server) { d, s -> d.ifEmpty { s } }
    val pinLength by derivedState(pin) { pin.value.length }
}

/**
 * A `derivedState`/`merged` property in a `:holdfast-testing` timeline (issue
 * #20, R6): its recompute commits in a transaction of its own, and the
 * handle's lookups resolve the property to the state that transaction writes,
 * so `emissions`, `emitted` and value matchers work on it like on a declared
 * state — with a Secret source's taint honoured.
 */
class DerivedStateTimelineTest {
    @Test fun aMergedStatesRecomputeIsRecordedUnderItsProperty() =
        storeTest {
            val handle = track(DraftStore())
            handle.action { server mutate "from sync" }

            val emissions = handle.emissions(DraftStore::shown)
            assertEquals(1, emissions.size, "one recompute for the adoption")
            assertEquals("", emissions.single().oldValue, "the committed value before the recompute")
            assertEquals("from sync", emissions.single().newValue)
            handle shouldFire { emitted(DraftStore::shown, "from sync") }
            handle shouldFireInOrder {
                emitted(DraftStore::server)
                committed
                emitted(DraftStore::shown)
                committed
            }
            handle shouldMatch { DraftStore::shown shouldEqual "from sync" }
        }

    @Test fun aDerivedStateOfASecretIsRedactedInTheTimeline() =
        storeTest {
            val handle = track(DraftStore())
            handle.action { pin mutate "1234" }
            val emission = handle.emissions(DraftStore::pinLength).single()
            assertSame(Redacted, emission.oldValue)
            assertSame(Redacted, emission.newValue)
        }
}
