@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.StateTag
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.keyedState
import com.vynatix.holdfast.restore
import com.vynatix.holdfast.snapshot
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A store with a state of every tag, and a Remote and a non-Remote keyed family; [adoption] is its adopt. */
private class Inbox(
    adoption: Inbox.(Int) -> Unit,
) : Store<Inbox>() {
    val draft by state(tags = setOf(StateTag.UserAuthored)) { "" }
    val server by state(tags = setOf(StateTag.Remote)) { 0 }
    val untagged by state { 0 }
    val remoteThreads by keyedState<String, Int>(tags = setOf(StateTag.Remote)) { 0 }
    val drafts by keyedState<String, String>(tags = setOf(StateTag.UserAuthored)) { "" }

    val hydration =
        hydrator {
            refresh { 7 } adopt { fetched -> adoption(fetched) }
        }
}

/**
 * `adopt` may write only Remote states (issue #20, R8 acceptance): any other
 * write — or keyed-entry eviction — fails the adoption naming the state, and
 * rolls the whole adoption back, Remote writes included; the phase moves to
 * Failed in the same adopt transaction.
 */
class HydrationAdoptPolicyTest {
    private fun hydrated(adoption: Inbox.(Int) -> Unit): Pair<Inbox, Hydration> =
        runBlocking {
            val store = Inbox(adoption)
            store.hydration.hydrate(this)
            store to store.hydration.awaitSettled()
        }

    private fun Hydration.failure(): Throwable = assertIs<Hydration.Failed>(this).cause

    @Test fun anAdoptionWritingOnlyRemoteStatesHydrates() {
        val (store, phase) =
            hydrated { fetched ->
                server mutate fetched
                remoteThreads["a"] mutate fetched
            }
        assertEquals(Hydration.Hydrated, phase)
        assertEquals(7, store.server.value)
        assertEquals(7, store.remoteThreads["a"].value)
    }

    @Test fun anAdoptionWritingAUserAuthoredStateFailsNamingItAndRollsBackWhole() {
        val (store, phase) =
            hydrated { fetched ->
                server mutate fetched
                draft mutate "clobbered"
            }
        val cause = phase.failure()
        assertIs<IllegalStateException>(cause)
        assertTrue("wrote Inbox.draft, which is not tagged StateTag.Remote" in cause.message.orEmpty(), cause.message)
        assertEquals("", store.draft.value)
        assertEquals(0, store.server.value, "the Remote write rolled back with it")
    }

    @Test fun anUntaggedStateIsRefusedTooAndEveryOffenderIsNamed() {
        val (_, phase) =
            hydrated {
                untagged mutate 1
                draft mutate "x"
            }
        val message = phase.failure().message.orEmpty()
        assertTrue("Inbox.untagged" in message && "Inbox.draft" in message, message)
    }

    @Test fun aWriteInANestedActionOrARestoreInsideAdoptIsPolicedToo() {
        val (store, nested) = hydrated { action { draft mutate "x" } }
        assertTrue("Inbox.draft" in nested.failure().message.orEmpty())
        assertEquals("", store.draft.value)

        val donor = Inbox { }
        donor { draft mutate "from a snapshot" }
        val snapshot = donor.snapshot()
        val (restored, viaRestore) = hydrated { restore(snapshot) }
        assertTrue("Inbox.draft" in viaRestore.failure().message.orEmpty())
        assertEquals("", restored.draft.value)
    }

    @Test fun evictingAnEntryOfANonRemoteFamilyIsRefusedAndOfARemoteOneAllowed() {
        val (store, refused) =
            hydrated {
                // Creating an entry is no write: only the evictions count.
                drafts["a"]
                remoteThreads["b"]
                drafts.evict("a")
                remoteThreads.evict("b")
            }
        val message = refused.failure().message.orEmpty()
        assertTrue("Inbox.drafts[*]" in message, message)
        assertTrue("remoteThreads" !in message, message)
        assertTrue("a" in store.drafts, "the eviction rolled back")
        assertTrue("b" in store.remoteThreads, "the Remote eviction rolled back with it")

        val (_, allowed) =
            hydrated {
                remoteThreads["b"]
                remoteThreads.evict("b")
            }
        assertEquals(Hydration.Hydrated, allowed)
    }

    @Test fun aThrowingAdoptFailsWithWhatItThrewAndRollsBack() {
        val broken = IllegalArgumentException("unparseable")
        val (store, phase) =
            hydrated {
                server mutate it
                throw broken
            }
        assertSame(broken, phase.failure())
        assertEquals(0, store.server.value)
    }

    @Test fun removeStateAndClearStatesAreRefusedInsideAdopt() {
        // Each reads draft first, so it is live for removeState/clearStates to drop.
        val drops = listOf<Inbox.() -> Unit>({ removeState("draft") }, { clearStates() })
        for (structural in drops) {
            val (store, phase) =
                hydrated {
                    server mutate it
                    draft.value
                    structural()
                }
            val message = phase.failure().message.orEmpty()
            assertTrue("adopt { } on Inbox is running" in message, message)
            assertTrue(store.hasState("draft"), "nothing was dropped")
            assertEquals(0, store.server.value)
        }
    }
}
