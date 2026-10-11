@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// Issue #20, R7 with R9: keyed entries and their evictions inside atomic(...)
// frames. A frame applies every participant — writes and evictions — inside
// one write bracket before any participant fans out, and rolls back whole.

private class KfSessions : Store<KfSessions>() {
    val tick by state { 0 }
    val active by keyedState<String, Boolean> { false }
}

/** A value whose `equals` runs [probe]: a `distinct` state compares values inside its commit's write bracket. */
private class KfProbed(
    val n: Int,
    private val probe: () -> Unit,
) {
    override fun equals(other: Any?): Boolean {
        probe()
        return other is KfProbed && other.n == n
    }

    override fun hashCode(): Int = n
}

private class KfAudit : Store<KfAudit>() {
    val count by state { 0 }
    val entries by keyedState<String, String> { "" }
    var probe: () -> Unit = {}
    val watched by state(distinct = true) { KfProbed(0) { probe() } }
}

private fun MutableState<*>.bracketOpen(): Boolean = writesBegun.value != writesEnded.value

/** Records `+key`/`-key` per entry, in order, and the entries it holds live, by identity. */
private class KfMembership : KeyedMembershipListener {
    val events = mutableListOf<Pair<String, State<*>>>()
    val live = mutableListOf<State<*>>()

    override fun onEntryAdded(
        family: String,
        key: Any,
        entry: State<*>,
    ) {
        events += "+$key" to entry
        live += entry
    }

    override fun onEntryEvicted(
        family: String,
        key: Any,
        entry: State<*>,
    ) {
        events += "-$key" to entry
        live.removeAll { it === entry }
    }
}

class KeyedStateFrameTest {
    @Test fun anEvictionInsideAFrameCommitsAndRollsBackWithTheFrame() {
        val sessions = KfSessions()
        val audit = KfAudit()
        sessions.active["u1"]
        audit.entries["u1"]

        val failed =
            atomic(sessions, audit) {
                sessions.active.evict("u1")
                audit.entries.evict("u1")
                audit { count mutate 1 }
                error("the frame fails")
            }
        assertIs<TransactionResult.Error>(failed)
        assertTrue("u1" in sessions.active)
        assertTrue("u1" in audit.entries)

        atomic(sessions, audit) {
            sessions.active.evict("u1")
            audit.entries.evict("u1")
            audit { count mutate 1 }
        }.getOrThrow()
        assertFalse("u1" in sessions.active)
        assertFalse("u1" in audit.entries)
        assertEquals(1, audit.count.value)
    }

    @Test fun anObserverOfOneParticipantFindsTheOthersEvictionsApplied() {
        val sessions = KfSessions()
        val audit = KfAudit()
        assertTrue(sessions.lockOrderKey < audit.lockOrderKey, "sessions fans out first")
        val auditEntry = audit.entries["u1"] as MutableState<*>
        val seen = mutableListOf<Boolean>()
        // The earlier participant's observer reads the later one's entry directly
        // (not through the frame's view of its staged evictions).
        sessions.tick effect { if (this == 1) seen += auditEntry.retired }

        atomic(sessions, audit) {
            sessions { tick mutate 1 }
            audit.entries.evict("u1")
        }.getOrThrow()

        assertEquals(listOf(true), seen, "every participant applied, evictions included, before any fanned out")
    }

    @Test fun anEvictionFromAFrameParticipantsObserverIsDeferredUntilTheFrameEnds() {
        val sessions = KfSessions()
        val audit = KfAudit()
        sessions.active["u1"]
        var reported: Throwable? = null
        sessions.uncaughtObserverHandler = { reported = it }
        audit.uncaughtObserverHandler = { reported = it }
        audit.count effect { if (this == 1) sessions.active.evict("u1") }

        atomic(sessions, audit) {
            sessions { active["u2"] mutate true }
            audit { count mutate 1 }
        }.getOrThrow()

        assertNull(reported)
        assertFalse("u1" in sessions.active, "evicted once the frame released the store")
        assertEquals(true, sessions.active["u2"].value)
    }

    @Test fun aResetStagedIntoFrameRootsReStagesEachStoresEntries() {
        val sessions = KfSessions()
        val audit = KfAudit()
        sessions.active["u1"]
        audit.entries["u1"]
        atomic(sessions, audit) {
            sessions { active["u1"] mutate true }
            audit { entries["u1"] mutate "logged" }
        }.getOrThrow()

        atomic(sessions, audit) {
            sessions.stageResetOfDeclaredStates(checkNotNull(sessions.activeTransaction))
            audit.stageResetOfDeclaredStates(checkNotNull(audit.activeTransaction))
        }.getOrThrow()

        assertEquals(false, sessions.active["u1"].value)
        assertEquals("", audit.entries["u1"].value)
        assertTrue("u1" in sessions.active, "reset(node) never evicts either")
    }

    @Test fun aFramesEvictionsApplyInsideItsOneWriteBracket() {
        val sessions = KfSessions()
        val audit = KfAudit()
        assertTrue(sessions.lockOrderKey < audit.lockOrderKey, "sessions applies first")
        audit.watched.value
        val s = sessions.active["u1"] as MutableState<*>
        val a = audit.entries["u1"] as MutableState<*>
        var sessionsEntryRetired = false
        var sessionsEntryBracketOpen = false
        var auditEntryBracketOpen = false
        // Runs inside audit's apply pass, after sessions applied: never a
        // consistent read here, since the brackets are open.
        audit.probe = {
            sessionsEntryRetired = s.retired
            sessionsEntryBracketOpen = s.bracketOpen()
            auditEntryBracketOpen = a.bracketOpen()
        }

        atomic(sessions, audit) {
            sessions.active.evict("u1")
            audit.entries.evict("u1")
            audit { watched mutate KfProbed(1) {} }
        }.getOrThrow()
        audit.probe = {}

        assertTrue(sessionsEntryRetired, "sessions applied its eviction first")
        assertTrue(sessionsEntryBracketOpen, "…and its entry's bracket stayed open while audit applied")
        assertTrue(auditEntryBracketOpen, "audit's evicted entry is bracketed as well")
        assertFalse(s.bracketOpen() || a.bracketOpen(), "both brackets closed after the apply pass")
        val (left, right) = captureConsistent(listOf(sessions, audit))
        assertEquals(emptySet(), left.keysOf(sessions.active))
        assertEquals(emptySet(), right.keysOf(audit.entries))
    }

    @Test fun aListenerTrackingByIdentityFollowsAKeyEvictedAndCreatedAgainInOneFrame() {
        val first = KfSessions()
        val second = KfSessions()
        assertTrue(first.lockOrderKey < second.lockOrderKey, "first fans out before second")
        val heard = KfMembership()
        second.internalObserveKeyedMembership(heard)
        val old = second.active["k"]
        var recreated: State<*>? = null
        first.tick effect { if (this == 1) recreated = second.active["k"] }

        atomic(first, second) {
            first { tick mutate 1 }
            second.active.evict("k")
        }.getOrThrow()

        val fresh = checkNotNull(recreated)
        assertEquals(listOf("+k", "+k", "-k"), heard.events.map { it.first }, "the new entry's add precedes the old one's eviction")
        assertSame(old, heard.events[0].second)
        assertSame(fresh, heard.events[1].second)
        assertSame(old, heard.events[2].second)
        assertEquals(listOf<State<*>>(fresh), heard.live, "identity tracking ends with the new entry live")
        assertSame<State<*>?>(fresh, second.active.getOrNull("k"))
    }
}
