@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast

import com.vynatix.holdfast.bridge.IntCodec
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class EdgeLeft : Store<EdgeLeft>() {
    val a by state { 0 }
    val docs by keyedState<String, Int> { 0 }
}

private class EdgeRight : Store<EdgeRight>() {
    val b by state { 0 }
}

private class EdgeHost : Store<EdgeHost>() {
    val y by state { 0 }
}

/** Counts the transactions a store completes: a node's recomputes, on its host. */
private class EdgeCommits<V : Store<V>> : Middleware<V>() {
    var completed = 0

    override fun onTransactionCompleted(context: MiddlewareContext<V>) {
        completed++
    }
}

/** A bridge the test drives: [deliver] is an inbound write. */
private class EdgeBridge : Bridge<Int> {
    private var inbound: ((Int) -> Unit)? = null

    override fun observe(observer: (Int) -> Unit): Disposable {
        inbound = observer
        return Disposable { inbound = null }
    }

    override fun publish(value: Int): Boolean = true

    fun deliver(value: Int) {
        inbound?.invoke(value)
    }
}

/** The followers [store]'s store-level edges hold (0 once it has none, or is disposed). */
private fun followersOf(store: Store<*>): Int = store.internalAttachment(STORE_EDGES)?.followerCount ?: 0

/**
 * Store-level derivation edges (issue #20 plan PR 15, D21): a derived state
 * that follows whole stores, added and removed at runtime — what issue #21's
 * `tree` value (`StoreTree`) recomputes on, over the stores that join and
 * leave a subtree. It settles like any derived state: once per outermost
 * entry or frame.
 */
class DynamicDerivationTest {
    private val disposables = mutableListOf<Disposable>()

    @AfterTest fun cleanup() {
        disposables.forEach { it.dispose() }
    }

    private class Node(
        host: EdgeHost,
        stores: List<Store<*>>,
        read: () -> Int,
    ) {
        var computes = 0
        val commits = EdgeCommits<EdgeHost>().also { host.middlewares(it) }
        val node: DerivedStateNode<Int> =
            host.derivedStateOverStores("total", stores) {
                computes++
                read()
            }
    }

    private fun node(
        host: EdgeHost,
        stores: List<Store<*>>,
        read: () -> Int,
    ): Node = Node(host, stores, read).also { disposables += it.node }

    @Test fun aTwoStoreFrameOverStoreEdgesRecomputesOnce() {
        val left = EdgeLeft()
        val right = EdgeRight()
        val host = EdgeHost()
        val probe = node(host, listOf(left, right)) { left.a.value + right.b.value }
        val seen = mutableListOf<Int>()
        disposables += probe.node effect { seen += this }

        atomic(left, right) {
            left.action { a mutate 1 }
            right.action { b mutate 2 }
        }.getOrThrow()

        assertEquals(3, probe.node.value)
        assertEquals(2, probe.computes, "the initial compute, then one recompute for the whole frame")
        assertEquals(1, probe.commits.completed, "one recompute transaction on the host")
        assertEquals(listOf(0, 3), seen, "never one participant's new value with the other's old one")
    }

    @Test fun eachOutermostEntryOutsideAFrameRecomputesOnce() {
        val left = EdgeLeft()
        val right = EdgeRight()
        val host = EdgeHost()
        val probe = node(host, listOf(left, right)) { left.a.value + right.b.value }

        left action { a mutate 1 }
        assertEquals(2, probe.computes)
        right action { b mutate 1 }
        assertEquals(3, probe.computes)
        left action {
            a mutate 2
            right action { b mutate 2 }
            a mutate 3
        }
        assertEquals(4, probe.computes, "an action with a nested one on another store: one outermost entry")
        assertEquals(5, probe.node.value)
    }

    @Test fun addingAnEdgeRecomputesAtOnceAndFollowsLaterCommits() {
        val left = EdgeLeft()
        val host = EdgeHost()
        val probe = node(host, emptyList()) { left.a.value }
        left action { a mutate 5 }
        assertEquals(1, probe.computes, "a store no edge leads to recomputes nothing")
        assertEquals(0, probe.node.value)

        assertTrue(probe.node.addSourceStore(left))

        assertEquals(2, probe.computes, "adding the edge recomputes once, reading the store")
        assertEquals(5, probe.node.value)
        assertEquals(listOf<Store<*>>(left), probe.node.sourceStores)
        assertEquals(1, followersOf(left))
        assertFalse(probe.node.addSourceStore(left), "an edge is added once")
        assertEquals(2, probe.computes)
        assertEquals(1, followersOf(left))
        left action { a mutate 6 }
        assertEquals(3, probe.computes)
        assertEquals(6, probe.node.value)
    }

    @Test fun removingAnEdgeRecomputesOnceAndStopsFollowing() {
        val left = EdgeLeft()
        val right = EdgeRight()
        val host = EdgeHost()
        val members = mutableListOf<Store<*>>(left, right)
        val probe =
            node(host, listOf(left, right)) {
                (if (left in members) left.a.value else 0) + (if (right in members) right.b.value else 0)
            }
        left action { a mutate 1 }
        right action { b mutate 10 }
        assertEquals(11, probe.node.value)

        members -= right
        assertTrue(probe.node.removeSourceStore(right))

        assertEquals(1, probe.node.value, "the removal recomputed without the store")
        val computes = probe.computes
        assertEquals(0, followersOf(right), "the store holds no follower any more")
        right action { b mutate 20 }
        assertEquals(computes, probe.computes, "its commits recompute nothing")
        assertFalse(probe.node.removeSourceStore(right), "removing an edge twice changes nothing")
        assertEquals(computes, probe.computes)
        assertEquals(listOf<Store<*>>(left), probe.node.sourceStores)
    }

    @Test fun keyedEntriesAndInboundBridgeWritesAreChangesOfTheStore() {
        val left = EdgeLeft()
        val host = EdgeHost()
        val probe = node(host, listOf(left)) { left.docs.entries.size * 100 + left.a.value }

        left.docs["x"]
        assertEquals(2, probe.computes, "an entry coming to life changes what a capture of the store holds")
        assertEquals(100, probe.node.value)
        left.docs["x"]
        assertEquals(2, probe.computes, "getting a live entry changes nothing")
        left action { docs["x"] mutate 7 }
        assertEquals(3, probe.computes, "an entry's commit")
        left action { docs.evict("x") }
        assertEquals(4, probe.computes, "an eviction")
        assertEquals(0, probe.node.value)

        val inbound = EdgeBridge()
        left { a bridge inbound }
        inbound.deliver(9)
        assertEquals(5, probe.computes, "an inbound bridge write")
        assertEquals(9, probe.node.value)
    }

    @Test fun aDerivedStatesOwnCommitIsNoChangeOfItsStore() {
        val left = EdgeLeft()
        val host = EdgeHost()
        val doubled = left.derivedState(left.a) { a.value * 2 }
        disposables += doubled
        val probe = node(host, listOf(left)) { left.a.value }

        left action { a mutate 1 }

        assertEquals(2, doubled.value)
        assertEquals(2, probe.computes, "the source commit recomputes it once; the derived state's own commit does not")
    }

    @Test fun aNodeFollowingItsOwnHostRecomputesOncePerCommit() {
        val host = EdgeHost()
        val probe = node(host, listOf(host)) { host.y.value }

        host action { y mutate 4 }

        assertEquals(4, probe.node.value)
        assertEquals(2, probe.computes, "its own recompute's commit is no change of the host: no loop")
    }

    @Test fun disposingAFollowedStoreDropsItsEdgeAndRecomputes() {
        val left = EdgeLeft()
        val right = EdgeRight()
        val host = EdgeHost()
        val probe =
            node(host, listOf(left, right)) {
                (if (left.isDisposed) 0 else left.a.value) + right.b.value
            }
        left action { a mutate 3 }
        right action { b mutate 4 }
        val computes = probe.computes
        val edges = checkNotNull(left.internalAttachment(STORE_EDGES))

        left.dispose()

        assertEquals(computes + 1, probe.computes, "the dispose recomputed the node once")
        assertEquals(4, probe.node.value)
        assertEquals(listOf<Store<*>>(right), probe.node.sourceStores)
        assertEquals(0, edges.followerCount, "the disposed store's edges hold no follower")
        assertFailsWith<IllegalStateException> { probe.node.addSourceStore(left) }
        assertEquals(listOf<Store<*>>(right), probe.node.sourceStores)
    }

    @Test fun disposingTheNodeReleasesEveryEdge() {
        val left = EdgeLeft()
        val right = EdgeRight()
        val host = EdgeHost()
        val probe = node(host, listOf(left, right)) { left.a.value + right.b.value }
        assertEquals(1, followersOf(left))

        probe.node.dispose()

        assertEquals(0, followersOf(left))
        assertEquals(0, followersOf(right))
        assertEquals(emptyList(), probe.node.sourceStores)
        left action { a mutate 1 }
        assertEquals(1, probe.computes, "a disposed node recomputes nothing")
        assertFalse(probe.node.addSourceStore(right), "a disposed node takes no edge")
        assertEquals(0, followersOf(right))
    }

    @Test fun aDisposedHostReleasesItsEdgesOnTheNextChange() {
        val left = EdgeLeft()
        val host = EdgeHost()
        val probe = node(host, listOf(left)) { left.a.value }

        host.dispose()
        left action { a mutate 1 }

        assertEquals(0, followersOf(left), "the change found the host disposed and released the edge")
        assertEquals(1, probe.computes)
    }

    @Test fun aThousandAttachDisposeCyclesLeaveNothingBehind() {
        val anchor = EdgeLeft()
        val host = EdgeHost()
        val members = mutableListOf<EdgeRight>()
        val probe = node(host, listOf(anchor)) { anchor.a.value + members.filterNot { it.isDisposed }.sumOf { it.b.value } }
        val watch = probe.node effect { }
        val hostObservers = probe.node.observerCount
        val anchorObservers = anchor.a.observerCount

        repeat(1_000) { i ->
            val branch = EdgeRight()
            members += branch
            assertTrue(probe.node.addSourceStore(branch))
            branch action { b mutate i + 1 }
            assertEquals(i + 1, probe.node.value)
            val edges = checkNotNull(branch.internalAttachment(STORE_EDGES))
            branch.dispose()
            members -= branch
            assertEquals(0, edges.followerCount, "a disposed store's edges hold no follower")
        }

        assertEquals(1 + 3 * 1_000, probe.computes, "one recompute per attach, commit and dispose — never more")
        assertEquals(listOf<Store<*>>(anchor), probe.node.sourceStores, "no disposed store stays followed")
        assertEquals(1, followersOf(anchor))
        assertEquals(hostObservers, probe.node.observerCount, "no observer left behind on the node")
        assertEquals(anchorObservers, anchor.a.observerCount, "edges subscribe no observer")
        assertNull(SettleScopes.current(), "no settle scope left open")
        host.internalDrainPostCommitTasks()
        assertEquals(1 + 3 * 1_000, probe.computes, "no recompute left queued on the host")
        anchor action { a mutate 1 }
        assertEquals(2 + 3 * 1_000, probe.computes, "and the node still follows its anchor")
        watch.dispose()
        probe.node.dispose()
        assertEquals(0, followersOf(anchor))
    }

    @Test fun aChangeInsideAnInitializerOutsideAnyEntryRecomputesOnceTheFirstReadReturns() {
        val left = EdgeLeft()
        val host = EdgeHost()
        val probe = node(host, listOf(left)) { left.docs.entries.size }
        var inside = -1
        val reader = EdgeFirstReader { left.docs["from-initializer"].value.also { inside = probe.computes } }

        // A first read outside any entry: the initializer creates the entry.
        reader.first.value

        assertEquals(1, inside, "no recompute runs inside the initializer")
        assertTrue("from-initializer" in left.docs)
        assertEquals(2, probe.computes, "one, once the first read returned: the materialization settles")
        assertEquals(1, probe.node.value)
        assertNull(SettleScopes.current(), "no settle scope left open")
    }

    @Test fun aChangeInsideAMigrateOutsideAnyEntryRecomputesOnceAfterTheRestore() {
        val left = EdgeLeft()
        val host = EdgeHost()
        val probe = node(host, listOf(left)) { left.docs.entries.size }
        var inside = -1
        val target = EdgeMigrating { left.docs["from-migrate"].also { inside = probe.computes } }
        val old = StoreSnapshot.decode(EdgeMigratingV1().apply { action { n mutate 3 } }.snapshot().encode())

        target.restore(old).getOrThrow()

        assertEquals(1, inside, "no recompute runs inside migrate")
        assertEquals(3, target.n.value)
        assertEquals(2, probe.computes, "one, after the restore: its plan and its action are one entry")
        assertEquals(1, probe.node.value)
    }

    @Test fun aChangeNoticedInANoWriteRegionWithNoScopeOpenIsHandedOffAndReported() {
        val left = EdgeLeft()
        val host = EdgeHost()
        val reported = mutableListOf<Throwable>()
        host.uncaughtObserverHandler = { reported += it }
        val probe = node(host, listOf(left)) { left.a.value }

        // No known path gets here — every such region runs in a settle scope —
        // so the fallback is reached by hand.
        NoWriteRegion.runCompute("a probe") { left.tellStoreEdges() }

        assertEquals(1, probe.computes, "not recomputed in the middle of the region")
        val lag = assertIs<IllegalStateException>(reported.single())
        assertContains(lag.message!!, "may lag EdgeLeft")
        host action { }
        assertEquals(2, probe.computes, "the host's next holder ran it")
    }

    @Test fun aHandlerThrowingForOneFollowerOfADisposedStoreStillLetsTheNextBeTold() {
        for (throwingFirst in listOf(true, false)) {
            val source = EdgeLeft()
            val throwingHost = EdgeHost().apply { uncaughtObserverHandler = { throw it } }
            val quietHost = EdgeHost()
            val nodes =
                listOf(throwingFirst, !throwingFirst).map { throwing ->
                    if (throwing) {
                        node(throwingHost, listOf(source)) { if (source.isDisposed) error("compute boom") else 0 }
                    } else {
                        node(quietHost, listOf(source)) { if (source.isDisposed) -1 else source.a.value }
                    }
                }
            val (throwing, quiet) = if (throwingFirst) nodes else nodes.reversed()
            val quietComputes = quiet.computes

            source.dispose()

            assertEquals(emptyList(), quiet.node.sourceStores, "throwing first = $throwingFirst: told, and dropped it")
            assertEquals(quietComputes + 1, quiet.computes, "throwing first = $throwingFirst: recomputed once more")
            assertEquals(-1, quiet.node.value)
            assertEquals(emptyList(), throwing.node.sourceStores, "throwing first = $throwingFirst: forgotten first")
        }
    }

    @Test fun removeStateAndClearStatesAreChangesOfTheStore() {
        val left = EdgeLeft()
        val host = EdgeHost()
        val probe = node(host, listOf(left)) { left.a.value }
        left action { a mutate 5 }
        assertEquals(5, probe.node.value)

        left.removeState("a")

        assertEquals(3, probe.computes, "a capture of the store re-creates the state now")
        assertEquals(0, probe.node.value, "at its initializer value")
        left action { a mutate 6 }
        assertEquals(6, probe.node.value)
        left.clearStates()
        assertEquals(5, probe.computes)
        assertEquals(0, probe.node.value)
    }

    @Test fun removeStateInsideAnActionRecomputesOnceAtItsSettle() {
        val left = EdgeLeft()
        val host = EdgeHost()
        val probe = node(host, listOf(left)) { left.a.value }
        left action { a mutate 5 }
        val before = probe.computes
        var inside = -1

        host action {
            left.removeState("a")
            left.clearStates()
            inside = probe.computes
        }

        assertEquals(before, inside, "nothing recomputes inside the action")
        assertEquals(before + 1, probe.computes, "once, at the action's settle")
        assertEquals(0, probe.node.value)
    }

    @Test fun aSealedOnlyCommitIsNoChangeOfItsStore() {
        val left = EdgeLeft()
        val host = EdgeHost()
        val phase = left.internalSealedState("phase", "idle", "sealed")
        val probe = node(host, listOf(left)) { left.a.value }

        left action { left.internalStageSealed(phase, "busy") }

        assertEquals("busy", phase.value)
        assertEquals(1, probe.computes, "a sealed state is library machinery's, not store state a capture holds")
        left action {
            a mutate 1
            left.internalStageSealed(phase, "idle")
        }
        assertEquals(2, probe.computes, "a commit that also changes store state recomputes once")
    }

    @Test fun aLegacyDerivedBackingOnAFollowedStoreCounts() {
        val left = EdgeLeft()
        val host = EdgeHost()
        val (twice, handle) = left.derived(left.a) { a.value * 2 }
        disposables += handle
        val probe = node(host, listOf(left)) { left.a.value * 1_000 + twice.value }

        left action { a mutate 1 }

        assertEquals(1_002, probe.node.value, "the fresh backing: the legacy recompute committed before the settle")
        assertEquals(2, probe.computes, "one recompute for the commit and the backing's commit together")
    }

    @Test fun aDerivedStateOfAFollowedStoreListedAsASourceSettlesBeforeTheNode() {
        val left = EdgeLeft()
        val host = EdgeHost()
        val doubled = left.derivedState(left.a) { a.value * 2 }
        disposables += doubled
        var computes = 0
        val total =
            host.derivedStateOverStores("n", listOf(left), sources = listOf(doubled)) {
                computes++
                doubled.value
            }
        disposables += total

        left action { a mutate 1 }

        assertEquals(2, total.value, "it read the derived state after its recompute")
        assertEquals(2, computes, "the initial compute, then once for the commit")
        assertEquals(listOf<Store<*>>(left), total.sourceStores)
    }

    @Test fun anAddedEdgeRefusesADisposedStoreNamingIt() {
        val host = EdgeHost()
        val gone = EdgeRight().also { it.dispose() }
        val probe = node(host, emptyList()) { 0 }

        val refused = assertFailsWith<IllegalStateException> { probe.node.addSourceStore(gone) }

        assertContains(refused.message!!, "EdgeRight")
        assertEquals(emptyList(), probe.node.sourceStores)
    }
}

/** Schema 1 of [EdgeMigrating]. */
private class EdgeMigratingV1 : Store<EdgeMigratingV1>() {
    val n by state(codec = IntCodec) { 0 }
}

/** Schema 2: its migrate runs [onMigrate]. */
private class EdgeMigrating(
    private val onMigrate: () -> Unit,
) : Store<EdgeMigrating>(),
    SchemaVersioned {
    val n by state(codec = IntCodec) { 0 }

    override val schemaVersion: Int get() = 2

    override fun migrate(
        from: Int,
        view: EncodedSnapshotView,
    ) {
        onMigrate()
    }
}

/** A store whose [first] state's initializer runs [read]. */
private class EdgeFirstReader(
    read: () -> Int,
) : Store<EdgeFirstReader>() {
    val first by state { read() }
}
