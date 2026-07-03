package com.vynatix.holdfast.testing.matcher

import com.vynatix.holdfast.Store
import com.vynatix.holdfast.atomic
import com.vynatix.holdfast.testing.storeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private class MatcherAccount(
    initial: Long = 0,
) : Store<MatcherAccount>() {
    val balance by state { initial }
}

class FrameMatcherTest {
    @Test fun shouldCommitTogetherPassesForOneFrameAcrossBothStores() =
        storeTest {
            val a = MatcherAccount(initial = 100)
            val b = MatcherAccount(initial = 0)
            val ha = track(a)
            val hb = track(b)

            atomic(a, b) {
                a.action { balance update { it - 30 } }
                b.action { balance update { it + 30 } }
            }

            val frameId = (ha and hb).shouldCommitTogether()
            assertTrue(frameId.startsWith("atomic-"))
        }

    @Test fun shouldCommitTogetherReturnsTheMostRecentSharedFrame() =
        storeTest {
            val a = MatcherAccount()
            val b = MatcherAccount()
            val ha = track(a)
            val hb = track(b)

            atomic(a, b) { a.action { balance mutate 1L } }
            atomic(a, b) { b.action { balance mutate 2L } }

            val frameId = (ha and hb).shouldCommitTogether()
            assertEquals(ha.committedFrameIds().last(), frameId)
            assertEquals(2, ha.committedFrameIds().size)
        }

    @Test fun shouldCommitTogetherFailsForIndependentCommits() =
        storeTest {
            val a = MatcherAccount()
            val b = MatcherAccount()
            val ha = track(a)
            val hb = track(b)

            ha.action { balance mutate 1L }
            hb.action { balance mutate 2L }

            val e = assertFailsWith<AssertionError> { (ha and hb).shouldCommitTogether() }
            assertTrue("MatcherAccount" in (e.message ?: ""), "failure lists per-store frames: ${e.message}")
        }

    @Test fun shouldCommitTogetherFailsAfterAFrameRollback() =
        storeTest {
            val a = MatcherAccount(initial = 100)
            val b = MatcherAccount()
            val ha = track(a)
            val hb = track(b)

            atomic(a, b, policy = com.vynatix.holdfast.FramePolicy.TolerateInnerErrors) {
                a.action { balance update { it - 30 } }
                error("aborts the frame")
            }

            assertFailsWith<AssertionError> { (ha and hb).shouldCommitTogether() }
            (ha and hb).shouldNotCommitTogether()
        }

    @Test fun groupsExtendBeyondTwoHandles() =
        storeTest {
            val a = MatcherAccount()
            val b = MatcherAccount()
            val c = MatcherAccount()
            val ha = track(a)
            val hb = track(b)
            val hc = track(c)

            atomic(a, b, c) {
                a.action { balance mutate 1L }
                b.action { balance mutate 2L }
                c.action { balance mutate 3L }
            }

            (ha and hb and hc).shouldCommitTogether()
        }
}
