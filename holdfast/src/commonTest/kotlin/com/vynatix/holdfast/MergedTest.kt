@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A mail draft the user writes, the server's copy sync writes, and what the screen shows. */
private class InboxStore : Store<InboxStore>() {
    val draft by state(tags = setOf(StateTag.UserAuthored)) { "" }
    val server by state(tags = setOf(StateTag.Remote)) { emptyList<String>() }
    val untagged by state { 0 }
    val token by state(tags = setOf(StateTag.Secret)) { "" }

    /** How many times `shown`'s merge has run. Declared before `shown`, whose initial merge counts. */
    var merges = 0
    val shown by merged(draft, server) { d, s ->
        merges++
        if (d.isEmpty()) s else listOf(d) + s
    }
}

/** A store holding the sources of a derived state that lives on another store. */
private class SourcesStore : Store<SourcesStore>() {
    val a by state { 0 }
    val b by state { 0 }
}

private class HostStore : Store<HostStore>() {
    val local by state { 0 }
    val other by state { 0 }
}

/** Two synced states with the same tag, for a merge `merged` does not check the tags of. */
private class TwinRemoteStore : Store<TwinRemoteStore>() {
    val primary by state(tags = setOf(StateTag.Remote)) { 0 }
    val replica by state(tags = setOf(StateTag.Remote)) { 0 }
}

/** A derived state an initializer reads, for `reset()`. */
private class TenfoldStore : Store<TenfoldStore>() {
    val a by state { 1 }
    val tenA by derivedState(a) { a.value * 10 }
    val x by state { tenA.value + 1 }
}

/**
 * `merged(local, remote)` and `derivedState(...)` (issue #20, R6): a
 * read-only [DerivedState] recomputed once per outermost entry that changes a
 * source (issue #20, R9), observable like
 * a declared state, carrying no tag but a source's Secret taint, and absent
 * from everything that captures or resets the store's own state.
 */
class MergedTest {
    private val disposables = mutableListOf<Disposable>()

    @AfterTest fun cleanup() {
        disposables.forEach { it.dispose() }
    }

    /**
     * R6 acceptance 1: an adoption writes the remote state (several writes, one
     * action); the local state is untouched and its observers silent, and the
     * merged value is recomputed exactly once.
     */
    @Test fun adoptingIntoRemoteLeavesLocalUntouchedAndRecomputesMergedOnce() {
        val inbox = InboxStore()
        inbox action { draft mutate "mine" }
        val localFires = mutableListOf<String>()
        disposables += inbox.draft effect { localFires += this }
        localFires.clear()
        val before = inbox.merges

        inbox action {
            server mutate listOf("a")
            server update { it + "b" }
            server update { it + "c" }
        }

        assertEquals(1, inbox.merges - before, "one adoption commit recomputes the merged value once")
        assertEquals("mine", inbox.draft.value, "the adoption leaves the user's side untouched")
        assertEquals(emptyList(), localFires, "and never fires its observers")
        assertEquals(listOf("mine", "a", "b", "c"), inbox.shown.value)
    }

    /** One commit that changes both inputs recomputes once too: the two source fires share one recompute. */
    @Test fun oneCommitChangingBothInputsRecomputesOnce() {
        val inbox = InboxStore()
        val before = inbox.merges
        inbox action {
            draft mutate "mine"
            server mutate listOf("a")
        }
        assertEquals(1, inbox.merges - before)
        assertEquals(listOf("mine", "a"), inbox.shown.value)
    }

    /** R6 acceptance 2: the merged value is observable through `effect`, once per change. */
    @Test fun mergedIsObservableThroughEffect() {
        val inbox = InboxStore()
        val seen = mutableListOf<List<String>>()
        disposables += inbox.shown effect { seen += this }
        assertEquals(listOf(emptyList()), seen, "effect fires once at once with the current value")

        inbox action { server mutate listOf("a") }
        inbox action { draft mutate "mine" }
        inbox action { untagged mutate 1 } // Not a source: no recompute.
        inbox action { server mutate listOf("a") } // Same merged value: no fire.

        assertEquals(listOf(emptyList(), listOf("a"), listOf("mine", "a")), seen)
        assertEquals(1, inbox.shown.observerCount)
    }

    /**
     * R6 acceptance 3: the local state is tagged UserAuthored and the remote
     * one Remote, each on its own; the merged state carries neither.
     */
    @Test fun localAndRemoteAreTaggedIndependentlyAndMergedCarriesNeither() {
        val inbox = InboxStore()
        assertEquals(setOf(StateTag.UserAuthored), inbox.draft.tags)
        assertEquals(setOf(StateTag.Remote), inbox.server.tags)
        assertEquals(emptySet(), inbox.shown.tags)
        assertEquals(listOf<State<*>>(inbox.draft), inbox.taggedStates(StateTag.UserAuthored))
        assertEquals(listOf<State<*>>(inbox.server), inbox.taggedStates(StateTag.Remote))
    }

    /** A derived state of a Secret source is Secret — through a chain of derived states too — and is never listed. */
    @Test fun aSecretSourceTaintsTheDerivedStateTransitively() {
        val inbox = InboxStore()
        val masked = inbox.merged(inbox.token, inbox.server) { t, s -> "$t:${s.size}" }
        val length = inbox.derivedState(masked) { masked.value.length }
        disposables += listOf(masked, length)
        assertEquals(setOf(StateTag.Secret), masked.tags)
        assertEquals(setOf(StateTag.Secret), length.tags)
        assertEquals(listOf<State<*>>(inbox.token), inbox.taggedStates(StateTag.Secret), "the store does not list them")
    }

    @Test fun mergedIsReadOnly() {
        val inbox = InboxStore()
        val writes =
            listOf<Pair<String, () -> Unit>>(
                "mutate" to { inbox { shown mutate listOf("x") } },
                "update" to { inbox { shown update { it + "x" } } },
                "bridge" to { inbox { shown bridge null } },
                "observeFrom" to { inbox { shown observeFrom Observable { Disposable { } } } },
                "mutate on the backing state" to { inbox { shown.observableBacking()!! mutate listOf("x") } },
            )
        for ((what, write) in writes) {
            val e = assertFailsWith<IllegalStateException>(what) { write() }
            assertContains(e.message.orEmpty(), "InboxStore.merged(draft, server)", message = what)
            assertContains(e.message.orEmpty(), "read-only", message = what)
        }
        val inAction = inbox action { shown mutate listOf("x") }
        assertIs<IllegalStateException>(assertIs<TransactionResult.Error>(inAction).exception)
        assertEquals(emptyList(), inbox.shown.value, "no write landed")
    }

    @Test fun mergedRejectsInputsThatAreNotTwoDeclaredStatesOfItsStore() {
        val inbox = InboxStore()
        val other = InboxStore()
        val (legacy, legacyHandle) = inbox.derived(inbox.draft) { draft.value }
        disposables += legacyHandle
        val computedDraft = inbox.computed { draft.value }
        val internal = inbox.registerInternalState("__merged_probe", "")
        val refused =
            listOf<Pair<String, () -> Unit>>(
                "a state of another store (InboxStore)" to { inbox.merged(other.draft, inbox.server) { d, _ -> d } },
                "derived backing state" to { inbox.merged(legacy, inbox.server) { d, _ -> d } },
                "derived state (derivedState or merged)" to { inbox.merged(inbox.draft, inbox.shown) { d, _ -> d } },
                "computed" to { inbox.merged(computedDraft, inbox.server) { d, _ -> d } },
                "internal state" to { inbox.merged(internal, inbox.server) { d, _ -> d } },
                "as both its local and its remote" to { inbox.merged(inbox.draft, inbox.draft) { d, _ -> d } },
            )
        for ((expected, call) in refused) {
            val e = assertFailsWith<IllegalArgumentException>(expected) { call() }
            assertContains(e.message.orEmpty(), "merged on InboxStore", message = expected)
            assertContains(e.message.orEmpty(), expected, message = expected)
        }
    }

    /**
     * R6 acceptance 3, from `merged`'s side: each input is tagged on its own,
     * and `merged` does not check the tags — a Remote state passed first (the
     * issue's `merged(remoteEntries, pinnedIds)` shape), two inputs with the
     * same tag, and untagged ones are all merged.
     */
    @Test fun mergedLeavesTheTagsOfItsInputsToTheCaller() {
        val inbox = InboxStore()
        val remoteFirst = inbox.merged(inbox.server, inbox.draft) { s, d -> "${s.size}:$d" }
        val untagged = inbox.merged(inbox.untagged, inbox.server) { n, s -> n + s.size }
        val twins = TwinRemoteStore()
        val newest = twins.merged(twins.primary, twins.replica) { p, r -> maxOf(p, r) }
        disposables += listOf(remoteFirst, untagged, newest)

        inbox action {
            server mutate listOf("a")
            draft mutate "mine"
        }
        twins action { replica mutate 2 }

        assertEquals("1:mine", remoteFirst.value)
        assertEquals(1, untagged.value)
        assertEquals(2, newest.value)
        assertEquals(emptySet(), remoteFirst.tags)
        assertEquals(emptySet(), newest.tags, "a merge of two Remote states is not Remote either")
    }

    @Test fun aDerivedStateCanBeASourceOfAnotherDerivedStateAndOfDerived() {
        val inbox = InboxStore()
        val count = inbox.derivedState(inbox.shown) { shown.value.size }
        val (label, labelHandle) = inbox.derived(inbox.shown) { "${shown.value.size} shown" }
        disposables += listOf(count, labelHandle)

        inbox action { server mutate listOf("a", "b") }
        assertEquals(2, count.value)
        assertEquals("2 shown", label.value)
        inbox action { draft mutate "mine" }
        assertEquals(3, count.value)
        assertEquals("3 shown", label.value)
    }

    @Test fun derivedStateRecomputesOncePerCommitHoweverManySourcesItChanges() {
        val host = HostStore()
        var computes = 0
        val sum =
            host.derivedState(host.local, host.other) {
                computes++
                local.value * 10 + other.value
            }
        disposables += sum
        host action {
            local mutate 1
            other mutate 2
        }
        assertEquals(12, sum.value)
        assertEquals(2, computes, "the initial compute, then one recompute for a commit changing both sources")
    }

    /**
     * Sources on another store: one commit of that store changing several
     * sources recomputes once, and it runs once that commit has released its
     * store, never inside its fanout. A blocking commit fans out on the thread
     * that holds its store, so the recompute's deferral to a blocking holder
     * on this thread gives this result on its own; the routing that queues it
     * on the source's store is what gives it to a suspending commit, which the
     * recompute never defers to (`:holdfast-coroutines`' `MergedSuspendTest`,
     * and `DerivedStateSourceRoutingTest` for one that fans out on another
     * thread).
     */
    @Test fun crossStoreSourcesChangedInOneCommitRecomputeOnceAfterTheCommit() {
        val sources = SourcesStore()
        val host = HostStore()
        var computes = 0
        var sourceHeld = false
        val sum =
            host.derivedState(sources.a, sources.b) {
                computes++
                if (sources.activeTransaction != null) sourceHeld = true
                sources.a.value + sources.b.value
            }
        disposables += sum
        sources action {
            a mutate 1
            b mutate 2
        }
        assertEquals(3, sum.value, "recomputed before the source's action returned")
        assertEquals(2, computes, "the initial compute, then one recompute for the commit")
        assertTrue(!sourceHeld, "the recompute ran after the source commit released its store")
    }

    /**
     * Sources on two stores written in one `atomic` frame: both participants'
     * commits queue the recompute, and the frame runs it once it has unwound,
     * so it runs once and its observers never see one participant's new value
     * with the other's old one.
     */
    @Test fun sourcesOnTwoStoresWrittenInOneFrameRecomputeOnceWithoutATornPair() {
        val left = SourcesStore()
        val right = SourcesStore()
        val host = HostStore()
        var computes = 0
        val pair =
            host.derivedState(left.a, right.a) {
                computes++
                left.a.value to right.a.value
            }
        val seen = mutableListOf<Pair<Int, Int>>()
        disposables += listOf(pair, pair effect { seen += this })

        atomic(left, right) {
            left.action { a mutate 1 }
            right.action { a mutate 1 }
        }.getOrThrow()

        assertEquals(1 to 1, pair.value)
        assertEquals(2, computes)
        assertEquals(listOf(0 to 0, 1 to 1), seen)
    }

    @Test fun disposeStopsRecomputationAndReleasesTheSourceSubscriptions() {
        val inbox = InboxStore()
        val baseline = inbox.server.observerCount
        val count = inbox.derivedState(inbox.server) { server.value.size }
        assertEquals(baseline + 1, inbox.server.observerCount)
        val seen = mutableListOf<Int>()
        disposables += count effect { seen += this }

        count.dispose()
        count.dispose() // Idempotent.
        inbox action { server mutate listOf("a") }

        assertEquals(baseline, inbox.server.observerCount, "the subscription is released")
        assertEquals(0, count.value, "frozen at its last recompute")
        assertEquals(listOf(0), seen)
    }

    /** A recompute already queued when the derived state is disposed does not commit. */
    @Test fun aQueuedRecomputeDoesNotCommitAfterDispose() {
        val inbox = InboxStore()
        val count = inbox.derivedState(inbox.server) { server.value.size }
        // Subscribed after the derived state, so it runs after the recompute is queued.
        disposables += inbox.server effect { if (isNotEmpty()) count.dispose() }
        inbox action { server mutate listOf("a") }
        assertEquals(0, count.value)
    }

    @Test fun aDerivedStateIsAbsentFromSnapshotsPropertiesAndReset() {
        val inbox = InboxStore()
        val count = inbox.derivedState(inbox.server) { server.value.size }
        disposables += count
        inbox action {
            draft mutate "mine"
            server mutate listOf("a", "b")
        }
        assertEquals(2, count.value)

        val names = setOf("draft", "server", "untagged", "token")
        assertEquals(names, inbox.snapshot().stateNames)
        assertEquals(names, inbox.properties.keys)
        val text = inbox.snapshot().encode()
        assertTrue("merged" !in text && "derivedState" !in text, text)
        val entry = assertFailsWith<IllegalArgumentException> { inbox.snapshot()[inbox.shown] }
        assertContains(entry.message.orEmpty(), "derivedState(...) or merged(...)")
        // Its backing state too (a timeline's EmissionEvent hands that one out).
        val backing = assertFailsWith<IllegalArgumentException> { inbox.snapshot().entry(count.observableBacking()!!) }
        assertContains(backing.message.orEmpty(), "derivedState(...) or merged(...)")

        var resetIds = listOf<String>()
        inbox.middlewares(
            object : Middleware<InboxStore>() {
                override fun onTransactionCompleted(context: Middleware.MiddlewareContext<InboxStore>) {
                    if (context.transaction.id == "Reset") resetIds = context.transaction.modifiedStates.map { it.toString() }
                }
            },
        )
        inbox.reset().getOrThrow()
        assertEquals(
            listOf("MutableState(InboxStore.draft)", "MutableState(InboxStore.server)"),
            resetIds.sorted(),
            "reset writes the declared states only",
        )
        assertEquals(emptyList(), inbox.shown.value, "the derived states recompute from the reset sources")
        assertEquals(0, count.value)
    }

    @Test fun aThrowingInitialComputeThrowsToTheCaller() {
        val inbox = InboxStore()
        val baseline = inbox.server.observerCount
        assertFailsWith<NoSuchElementException> { inbox.derivedState(inbox.server) { server.value.first() } }
        assertEquals(baseline, inbox.server.observerCount, "nothing was subscribed")
    }

    @Test fun aRecomputeFailureIsReportedAndTheValueStays() {
        val inbox = InboxStore()
        val reported = mutableListOf<Throwable>()
        inbox.uncaughtObserverHandler = { reported += it }
        val size =
            inbox.derivedState(inbox.server) {
                check(server.value.size < 2) { "too many" }
                server.value.size
            }
        disposables += size
        inbox action { server mutate listOf("a") }
        inbox action { server mutate listOf("a", "b") }
        assertEquals(1, size.value)
        assertEquals(listOf("too many"), reported.map { it.message })
    }

    @Test fun derivedStateDeclaresWithByAndNamesItselfWithoutItsValue() {
        val inbox = InboxStore()
        assertSame(inbox.shown, inbox.shown, "a by-delegated DerivedState is one instance")
        assertEquals("DerivedState(InboxStore.merged(draft, server))", inbox.shown.toString())
        val sources = SourcesStore()
        val host = HostStore()
        val sum = host.derivedState(host.local, sources.a) { 0 }
        disposables += sum
        assertEquals("DerivedState(HostStore.derivedState(local, SourcesStore.a))", sum.toString())
    }

    @Test fun derivedStateRefusesMissingOrUnobservableSourcesAndDisposedStores() {
        val inbox = InboxStore()
        assertFailsWith<IllegalArgumentException> { inbox.derivedState { 0 } }
        val computedSize = inbox.computed { server.value.size }
        val e = assertFailsWith<IllegalArgumentException> { inbox.derivedState(computedSize) { 0 } }
        assertContains(e.message.orEmpty(), "computed { }")

        val gone = SourcesStore()
        val a = gone.a
        gone.dispose()
        val disposed = assertFailsWith<IllegalStateException> { inbox.derivedState(a) { 0 } }
        assertContains(disposed.message.orEmpty(), "disposed")
    }

    /** A disposed host stops recomputing, and the next source commit drops its subscriptions to other stores. */
    @Test fun aDisposedHostReleasesItsSubscriptionsToAnotherStoresSources() {
        val sources = SourcesStore()
        val host = HostStore()
        val baseline = sources.a.observerCount
        val doubled = host.derivedState(sources.a) { sources.a.value * 2 }
        assertEquals(baseline + 1, sources.a.observerCount)

        host.dispose()
        sources action { a mutate 1 }

        assertEquals(baseline, sources.a.observerCount)
        assertEquals(0, doubled.value)
        val e = assertFailsWith<IllegalStateException> { doubled effect { } }
        assertContains(e.message.orEmpty(), "disposed")
        doubled.dispose() // Still safe.
    }

    /**
     * A source commit nested in an action on another source's store: the
     * recompute it queues runs once that action ends — never from its
     * uncommitted writes, which a rollback then discards.
     */
    @Test fun aRecomputeNeverCommitsAnotherSourceStoresUncommittedWrites() {
        val left = SourcesStore()
        val right = SourcesStore()
        val host = HostStore()
        val pair = host.derivedState(left.a, right.a) { left.a.value to right.a.value }
        val seen = mutableListOf<Pair<Int, Int>>()
        disposables += listOf(pair, pair effect { seen += this })

        val rolledBack =
            left action {
                a mutate 100
                right action { a mutate 1 }
                assertEquals(0 to 0, pair.value, "not recomputed while left's write is uncommitted")
                error("abort")
            }
        assertIs<TransactionResult.Error>(rolledBack)
        assertEquals(0 to 1, pair.value, "recomputed from the committed values once left's action ended")
        assertEquals(listOf(0 to 0, 0 to 1), seen, "no observer saw left's rolled-back write")

        left action {
            a mutate 2
            right action { a mutate 2 }
        }
        assertEquals(2 to 2, pair.value)
        assertEquals(listOf(0 to 0, 0 to 1, 2 to 2), seen, "one recompute, after both commits: never a torn pair")
    }

    /**
     * A recompute reads committed values only: a state its compute reads
     * without listing it as a source, held by an action on the committing
     * thread, is read at its committed value. The source commit nested in that
     * action settles once the outer action ends (issue #20, R9): the
     * recompute then reads the rolled-back state's committed value.
     */
    @Test fun aRecomputeReadsCommittedValuesOfAStateItDoesNotList() {
        val sources = SourcesStore()
        val other = HostStore()
        val host = HostStore()
        val sum = host.derivedState(sources.a) { sources.a.value + other.local.value }
        disposables += sum
        var inside = -1

        val rolledBack =
            other action {
                local mutate 100
                sources action { a mutate 1 }
                inside = sum.value
                error("abort")
            }

        assertIs<TransactionResult.Error>(rolledBack)
        assertEquals(0, inside, "not recomputed before the outermost action ended")
        assertEquals(1, sum.value, "recomputed once it ended, without other's rolled-back write")
    }

    /** A recompute's compute may read states but not write them: the write is refused and reported. */
    @Test fun aWriteFromARecomputeIsRefusedAndReported() {
        val sources = SourcesStore()
        val host = HostStore()
        val reported = mutableListOf<Throwable>()
        host.uncaughtObserverHandler = { reported += it }
        val doubled =
            host.derivedState(sources.a) {
                if (sources.a.value > 0) sources { b mutate sources.a.value }
                sources.a.value * 2
            }
        disposables += doubled

        sources action { a mutate 1 }

        assertEquals(0, doubled.value, "the refused recompute rolled back")
        assertEquals(0, sources.b.value)
        val refusal = assertIs<IllegalStateException>(reported.single())
        assertContains(refusal.message.orEmpty(), "Cannot write SourcesStore.b")
        assertContains(refusal.message.orEmpty(), "the compute of HostStore.derivedState(SourcesStore.a)")
    }

    /** The same, with the derived state hosted on either source store. */
    @Test fun aRecomputeHostedOnASourceStoreNeverCommitsTheOtherOnesUncommittedWrites() {
        val left = SourcesStore()
        val right = SourcesStore()
        val onRight = right.derivedState(left.a, right.a) { left.a.value to right.a.value }
        val onLeft = left.derivedState(left.a, right.a) { left.a.value to right.a.value }
        disposables += listOf(onRight, onLeft)

        val rolledBack =
            left action {
                a mutate 100
                right action { a mutate 1 }
                error("abort")
            }
        assertIs<TransactionResult.Error>(rolledBack)
        assertEquals(0 to 1, onRight.value)
        assertEquals(0 to 1, onLeft.value)
    }

    /**
     * An `atomic` frame nested in an action on one participant: the other
     * participant commits when the frame ends, and the recompute that queues
     * waits for the enclosing action, which then rolls back.
     */
    @Test fun aRecomputeAfterAFrameNestedInAnActionWaitsForThatAction() {
        val left = SourcesStore()
        val right = SourcesStore()
        val host = HostStore()
        val pair = host.derivedState(left.a, right.a) { left.a.value to right.a.value }
        disposables += pair

        val rolledBack =
            left action {
                atomic(left, right) {
                    right.action { a mutate 1 }
                    left.action { a mutate 100 }
                }.getOrThrow()
                error("abort")
            }
        assertIs<TransactionResult.Error>(rolledBack)
        assertEquals(0, left.a.value)
        assertEquals(1, right.a.value)
        assertEquals(0 to 1, pair.value)
    }

    /**
     * Created inside an action, a derived state sees that action's pending
     * writes, and recomputes from the committed values once the action ends:
     * a rollback leaves it explained by its sources.
     */
    @Test fun aDerivedStateCreatedInAnActionRecomputesOnceTheActionEnds() {
        val sources = SourcesStore()
        val host = HostStore()
        val inbox = InboxStore()
        lateinit var doubled: DerivedState<Int>
        lateinit var onHost: DerivedState<Int>
        lateinit var shown: DerivedState<String>
        val rolledBack =
            sources action {
                a mutate 5
                doubled = sources.derivedState(a) { a.value * 2 }
                onHost = host.derivedState(sources.a) { sources.a.value * 3 }
                assertEquals(10, doubled.value, "the initial compute reads the action's pending write")
                assertEquals(15, onHost.value)
                error("abort")
            }
        assertIs<TransactionResult.Error>(rolledBack)
        assertEquals(0, doubled.value, "recomputed from the committed value after the rollback")
        assertEquals(0, onHost.value)

        val mergedRolledBack =
            inbox action {
                server mutate listOf("a")
                shown = inbox.merged(draft, server) { d, s -> "$d:${s.size}" }
                assertEquals(":1", shown.value)
                error("abort")
            }
        assertIs<TransactionResult.Error>(mergedRolledBack)
        assertEquals(":0", shown.value, "the merge of the committed values")

        sources action {
            a mutate 5
            doubled.dispose()
            doubled = sources.derivedState(a) { a.value * 2 }
        }
        assertEquals(10, doubled.value)
        disposables += listOf(doubled, onHost, shown)
    }

    /**
     * Disposing a source's store from its own commit's fanout still runs the
     * recompute that commit queued there, for a derived state on another,
     * live store. (The compute reads the state it holds: a disposed store's
     * delegated property throws on read.)
     */
    @Test fun disposingASourceStoreRightAfterItsCommitStillRecomputes() {
        val sources = SourcesStore()
        val host = HostStore()
        val a = sources.a
        val doubled = host.derivedState(a) { a.value * 2 }
        disposables += doubled
        sources { a effect { if (this == 1) sources.dispose() } }
        sources action { a mutate 1 }
        assertTrue(sources.isDisposed)
        assertEquals(2, doubled.value, "the recompute the last commit queued ran")
    }

    /** A chain across three stores: one source commit recomputes each link once, before it returns. */
    @Test fun aChainOfDerivedStatesAcrossStoresRecomputesEachLinkOnce() {
        val sources = SourcesStore()
        val middle = HostStore()
        val top = HostStore()
        var middleComputes = 0
        var topComputes = 0
        val doubled =
            middle.derivedState(sources.a) {
                middleComputes++
                sources.a.value * 2
            }
        val plusOne =
            top.derivedState(doubled) {
                topComputes++
                doubled.value + 1
            }
        disposables += listOf(plusOne, doubled)

        sources action { a mutate 5 }

        assertEquals(10, doubled.value)
        assertEquals(11, plusOne.value)
        assertEquals(2, middleComputes, "the initial compute, then one recompute")
        assertEquals(2, topComputes, "the initial compute, then one recompute")
    }

    /**
     * `restore` writes the sources, and the derived state recomputes after
     * its commit; a Strict restore neither reports nor restores it.
     */
    @Test fun restoreWritesTheSourcesAndTheDerivedStateRecomputes() {
        val inbox = InboxStore()
        val count = inbox.derivedState(inbox.server) { server.value.size }
        disposables += count
        inbox action {
            draft mutate "mine"
            server mutate listOf("a")
        }
        val snapshot = inbox.snapshot()
        inbox action {
            draft mutate ""
            server mutate listOf("x", "y")
        }
        assertEquals(2, count.value)

        val report = inbox.restore(snapshot, RestorePolicy.Strict).getOrThrow()
        assertEquals(setOf("draft", "server", "untagged", "token"), report.restored + report.kept, "declared states only")
        assertEquals(emptyList(), report.issues)
        assertEquals(listOf("mine", "a"), inbox.shown.value)
        assertEquals(1, count.value)

        inbox action { server mutate listOf("x", "y", "z") }
        assertEquals(3, count.value)
        inbox.restore(snapshot).getOrThrow()
        assertEquals(listOf("mine", "a"), inbox.shown.value)
        assertEquals(1, count.value)
    }

    /**
     * An initializer `reset()` re-runs reads a derived state at its pre-reset
     * value (derived states recompute after the reset commits), as for
     * `derived`.
     */
    @Test fun aResetInitializerReadsADerivedStateAtItsPreResetValue() {
        val store = TenfoldStore()
        disposables += store.tenA
        assertEquals(11, store.x.value)
        store action { a mutate 5 }
        assertEquals(50, store.tenA.value)

        store.reset().getOrThrow()

        assertEquals(51, store.x.value, "x's initializer read tenA at 50, not a fresh store's 10")
        assertEquals(1, store.a.value)
        assertEquals(10, store.tenA.value, "tenA recomputed after the reset committed")
    }
}
