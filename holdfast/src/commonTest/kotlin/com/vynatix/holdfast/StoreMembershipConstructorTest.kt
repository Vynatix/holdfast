@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast

import kotlinx.coroutines.channels.BufferOverflow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Coverage for [StoreMembership] and the constructors that accept it (issue
 * #21 plan, PR 21-1, decision U3, T2). A store built only with the plain
 * no-arg constructor is untouched by this machinery; one built with the
 * membership constructor gets [StoreMembership.bind] called exactly once,
 * before its own delegated states run, and after every [Store] field (and,
 * for an [EventfulStore] subclass, its own) has initialized.
 */
private class RecordingMembership<S : Store<S>> : StoreMembership<S>() {
    var boundStore: Store<S>? = null
    var bindCount = 0

    override fun bind(store: Store<S>) {
        boundStore = store
        bindCount++
    }
}

/** Uses only the plain, public no-arg constructor: `<init>()V` untouched by this PR. */
private class PlainProbe : Store<PlainProbe>() {
    val n by state { 0 }
}

/**
 * Records, in its own primary constructor body, whether [membership] had
 * already bound by the time this subclass's own initializers ran — proving
 * [bind] runs strictly before any subclass code, including delegated states.
 */
private class MembershipProbe(
    membership: RecordingMembership<MembershipProbe>,
) : Store<MembershipProbe>(membership) {
    val boundBeforeSubclassInit = membership.boundStore != null
    val n by state { 0 }
}

private sealed class ProbeEvent {
    data object Pinged : ProbeEvent()
}

/** Same shape as [MembershipProbe], for the `EventfulStore` mirror constructor. */
private class EventfulMembershipProbe(
    membership: RecordingMembership<EventfulMembershipProbe>,
) : EventfulStore<EventfulMembershipProbe, ProbeEvent>(membership, extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.SUSPEND) {
    val eventsAtBindTime = membership.boundStore?.let { (it as EventfulMembershipProbe).events }
    val n by state { 0 }
}

class StoreMembershipConstructorTest {
    @Test
    fun plainNoArgConstructorAttachesNoMembership() {
        val probe = PlainProbe()
        assertTrue(probe.internalAttachments().isEmpty())
        assertEquals(0, probe.n.value)
    }

    @Test
    fun bindRunsExactlyOnceBeforeSubclassInitializersAndDelegatedStates() {
        val membership = RecordingMembership<MembershipProbe>()
        val probe = MembershipProbe(membership)

        assertTrue(probe.boundBeforeSubclassInit, "bind() must run before the subclass's own property initializers")
        assertSame(probe, membership.boundStore)
        assertEquals(1, membership.bindCount)
        // The state declares fine afterward, proving construction completed normally.
        assertEquals(0, probe.n.value)
    }

    @Test
    fun eventfulStoreMirrorInitializesEventsBeforeBinding() {
        val membership = RecordingMembership<EventfulMembershipProbe>()
        val probe = EventfulMembershipProbe(membership)

        assertNotNull(probe.eventsAtBindTime, "events must already be initialized when bind() runs")
        assertSame(probe.events, probe.eventsAtBindTime)
        assertSame(probe, membership.boundStore)
    }

    @Test
    fun membershipConstructorStoreBehavesLikeAnyOtherStoreAfterConstruction() {
        val membership = RecordingMembership<MembershipProbe>()
        val probe = MembershipProbe(membership)

        probe action { n mutate 5 }
        assertEquals(5, probe.n.value)
        probe.dispose()
        assertTrue(probe.isDisposed)
    }
}
