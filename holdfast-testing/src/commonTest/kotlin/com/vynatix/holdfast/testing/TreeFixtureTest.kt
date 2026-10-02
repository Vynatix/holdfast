@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.testing

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.StateTag
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.atomic
import com.vynatix.holdfast.testing.concurrency.parallel
import com.vynatix.holdfast.testing.matcher.shouldBeError
import com.vynatix.holdfast.testing.matcher.shouldCommitTogether
import com.vynatix.holdfast.testing.matcher.shouldNotCommitTogether
import com.vynatix.holdfast.tree.StoreNode
import com.vynatix.holdfast.tree.TreeMiddleware
import com.vynatix.holdfast.tree.store
import com.vynatix.holdfast.tree.stores
import com.vynatix.holdfast.tree.tree
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

private open class FxLeafStore : Store<FxLeafStore>() {
    val n by state { 0 }
    val secret by state(tags = setOf(StateTag.Secret)) { "hunter2-plaintext" }
}

/** The group's two leaves need distinct classes: a group pins its leaf names by exact class. */
private class FxAStore : FxLeafStore()

private class FxBStore : FxLeafStore()

private class FxKeyedStore(
    id: String,
) : Store<FxKeyedStore>() {
    val title by state { "t $id" }
}

private class FxParent : Store<FxParent>() {
    val own by state { 0 }
    val pair by stores(names = mapOf(FxAStore::class to "a", FxBStore::class to "b")) { listOf(FxAStore(), FxBStore()) }
    val keyed by stores<String, FxKeyedStore> { FxKeyedStore(it) }

    val a: FxLeafStore get() = pair.stores[0] as FxLeafStore
    val b: FxLeafStore get() = pair.stores[1] as FxLeafStore
}

private class FxPlainStore : Store<FxPlainStore>() {
    val n by state { 0 }
}

private class FxMidStore : Store<FxMidStore>() {
    val leaf by store { FxPlainStore() }
}

private class FxRoot : Store<FxRoot>() {
    val mid by store { FxMidStore() }
}

private class CountingConsumer<V : Store<V>> : Middleware<V>() {
    var started = 0

    override fun onTransactionStarted(context: MiddlewareContext<V>) {
        started++
    }
}

private class CountingTree : TreeMiddleware() {
    var started = 0

    override fun onTransactionStarted(
        node: StoreNode,
        context: Middleware.MiddlewareContext<*>,
    ) {
        started++
    }
}

/** Vetoes every `completed` while [armed], with a message that quotes no value. */
private class Veto<V : Store<V>> : Middleware<V>() {
    var armed = false

    override fun onTransactionCompleted(context: MiddlewareContext<V>) {
        if (armed) error("rejected by policy")
    }
}

/**
 * `track(tree)`: the receiver and every store of its subtree tracked, one
 * tree timeline, teardown that removes only the fixture and resets the
 * receiver with its subtree.
 */
class TreeFixtureTest {
    @Test
    fun trackAutoTracksTheReceiverEveryMemberAndKeyedStoresCreatedMidTest() =
        storeTest {
            val parent = FxParent()
            val tree = track(parent.tree)
            parent action { own mutate 1 }
            parent.a action { n mutate 1 }
            val k = parent.keyed.create("k")
            k action { title mutate "x" }
            assertEquals(1, tree.handle(parent.a).transactions.size / 2, "the member's own recorder saw its action")
            assertEquals(1, tree.handle(k).transactions.size / 2, "a keyed store created mid-test is tracked as it joins")
            assertEquals(1, tree.handle(parent).transactions.size / 2, "the receiver itself is tracked")
            assertSame(track(k), tree.handle(k))
            assertSame(track(parent), tree.handle(parent))
            assertEquals(
                listOf("Started FxParent", "Completed FxParent", "Started a", "Completed a", "Started k", "Completed k"),
                tree.timeline.map { "${it.phase} ${it.node.name}" },
            )
            assertEquals(4, allTrackedHandles().size, "the receiver, a, b, k — and nothing else")
        }

    @Test
    fun exactlyOneHandlePerStoreUnderParallelCreation() =
        storeTest {
            val parent = FxParent()
            val tree = track(parent.tree)
            val created = parallel(8) { i -> parent.keyed.create("k$i") }
            assertEquals(8, created.distinct().size)
            val handles = allTrackedHandles()
            assertEquals(3 + 8, handles.size)
            assertEquals(handles.size, handles.map { it.store }.distinct().size, "one handle per store")
            for (store in created) assertSame(track(store), tree.handle(store))
        }

    @Test
    fun aThousandKeyedStoresTrackedMidTestTearDownCleanly() =
        storeTest {
            val parent = FxParent()
            track(parent.tree)
            repeat(1_000) { i ->
                val k = parent.keyed.create("k$i")
                k action { title mutate "changed" }
            }
            assertEquals(1_003, allTrackedHandles().size)
        }

    @Test
    fun shouldCommitTogetherUsesFrameIdsAndFailsWhenTheLastParticipantVetoes() =
        storeTest {
            val parent = FxParent()
            val tree = track(parent.tree)
            val a = parent.a
            val b = parent.b
            atomic(a, b) {
                a { n mutate 1 }
                b { n mutate 2 }
            }.getOrThrow()
            val frame = tree.shouldCommitTogether(parent.pair)
            assertTrue(frame.startsWith("atomic-"))
            assertEquals(listOf(frame), tree.committedFrameIds(parent.tree.node))
            tree.group(parent.pair).shouldCommitTogether()
            val whole = assertFailsWith<AssertionError> { tree.shouldCommitTogether(parent.tree.node) }
            assertContains(whole.message!!, "no frame id is shared", message = "the receiver never joined the frame")

            val vetoed = FxParent()
            val vetoedTree = track(vetoed.tree, resetAtTeardown = false)
            val veto = Veto<FxLeafStore>().also { vetoed.b.middlewares(it) }
            veto.armed = true
            val result =
                atomic(vetoed.a, vetoed.b) {
                    vetoed.a { n mutate 1 }
                    vetoed.b { n mutate 2 }
                }
            assertIs<TransactionResult.Error>(result)
            assertEquals(0, vetoed.a.n.value)
            val failure = assertFailsWith<AssertionError> { vetoedTree.shouldCommitTogether(vetoed.pair) }
            assertContains(failure.message!!, "no frame id is shared")
            vetoedTree.shouldNotCommitTogether(vetoed.pair)
            assertEquals(emptyList<String>(), vetoedTree.committedFrameIds(vetoed.tree.node), "a vetoed frame committed nowhere")
        }

    @Test
    fun eventsOfADisposedKeyedStoreStayVisibleUnderItsBranch() =
        storeTest {
            val parent = FxParent()
            val tree = track(parent.tree)
            val k = parent.keyed.create("k")
            val leaf = parent.tree.nodeOf(k)!!
            k action { title mutate "x" }
            k.dispose()
            assertEquals(listOf("Started k", "Completed k"), tree.events(parent.keyed).map { "${it.phase} ${it.node.name}" })
            assertEquals(2, tree.events(leaf).size)
            assertEquals(2, tree.events(parent.tree.node).size)
            assertEquals(0, tree.events(parent.pair).size)
            assertSame(track(k), tree.handle(k), "a former member still answers its handle")
        }

    @Test
    fun aReleasedStoresEarlierEventsStayUnderItsFormerAncestors() =
        storeTest {
            val root = FxRoot()
            val tree = track(root.tree)
            val mid = root.mid
            val midNode = mid.tree.node
            val leaf = mid.leaf
            leaf action { n mutate 1 }
            // Disposing `mid` releases `leaf` as a subtree root: its parent link is gone today.
            mid.dispose()
            assertEquals(null, leaf.tree.parent, "the leaf is a subtree root now")
            val underMid = tree.events(midNode)
            assertEquals(listOf("Started", "Completed"), underMid.map { "${it.phase}" }, "judged by where the leaf sat when recorded")
            assertTrue(underMid.all { it.store === leaf })
            assertEquals(2, tree.events(root.tree.node).size, "and still under the root")
        }

    @Test
    fun teardownRemovesOnlyTheFixtureAndKeepsUserAndTreeMiddleware() {
        val parent = FxParent()
        val a = parent.a
        val user = CountingConsumer<FxLeafStore>().also { a.middlewares(it) }
        val treeMiddleware = CountingTree().also { parent.tree.middlewares(it) }
        var handle: TreeHandle? = null
        storeTest {
            handle = track(parent.tree)
            a action { n mutate 1 }
            assertEquals(2, handle!!.timeline.size)
        }
        // The teardown reset ran through both (a's user middleware once, the tree's for the receiver, a and b).
        assertEquals(2, user.started)
        assertEquals(4, treeMiddleware.started)
        a action { n mutate 2 }
        assertEquals(3, user.started, "the user middleware stayed installed")
        assertEquals(5, treeMiddleware.started, "the tree middleware stayed installed")
        assertTrue(handle!!.timeline.isEmpty(), "the fixture's recorder is gone from the tree")
        storeTest {
            assertTrue(a.timeline.isEmpty(), "a fresh handle in a fresh scope")
        }
    }

    @Test
    fun teardownResetsTheReceiverAndItsSubtreeSoASecondTestSeesInitialValuesAndSkipsDisposedStores() {
        val parent = FxParent()
        storeTest {
            track(parent.tree)
            parent action { own mutate 3 }
            parent.a action { n mutate 5 }
            val k = parent.keyed.create("k")
            k action { title mutate "x" }
            k.dispose()
            val kept = parent.keyed.create("kept")
            kept action { title mutate "changed" }
        }
        storeTest {
            track(parent.tree)
            assertEquals(0, parent.own.value, "the receiver's own states are reset too")
            assertEquals(0, parent.a.n.value)
            assertEquals("t kept", parent.keyed["kept"]!!.title.value)
            assertFalse(parent.isDisposed, "the receiver is never disposed by teardown")
        }
    }

    @Test
    fun aVetoedResetFailsTheTestNamingTheLeafAndTheVeto() {
        val parent = FxParent()
        val b = parent.b
        val veto = Veto<FxLeafStore>().also { b.middlewares(it) }
        val failure =
            assertFailsWith<AssertionError> {
                storeTest {
                    track(parent.tree)
                    b action { n mutate 1 }
                    veto.armed = true
                }
            }
        assertContains(failure.message!!, "could not reset")
        assertContains(failure.message!!, "tree 'FxParent'")
        assertContains(failure.message!!, "leaf 'b'")
        assertContains(failure.message!!, "IllegalStateException: rejected by policy")
        veto.armed = false
        assertEquals(1, b.n.value, "the vetoed reset left the value")
    }

    @Test
    fun aResetFailureIsSuppressedWhenTheBodyAlreadyFailed() {
        val parent = FxParent()
        val veto = Veto<FxLeafStore>().also { parent.b.middlewares(it) }
        veto.armed = true
        val failure =
            assertFailsWith<IllegalArgumentException> {
                storeTest {
                    track(parent.tree)
                    throw IllegalArgumentException("the body's own failure")
                }
            }
        assertEquals("the body's own failure", failure.message)
    }

    @Test
    fun resetAtTeardownCanBeOptedOut() {
        val parent = FxParent()
        storeTest {
            track(parent.tree, resetAtTeardown = false)
            parent action { own mutate 9 }
            parent.a action { n mutate 9 }
        }
        assertEquals(9, parent.own.value)
        assertEquals(9, parent.a.n.value)
    }

    @Test
    fun trackIsIdempotentByReceiverIdentityAndThrowsOnADisposedStore() =
        storeTest {
            val parent = FxParent()
            val first = track(parent.tree)
            assertSame(first, track(parent.tree, Capture.None, resetAtTeardown = false))
            assertSame(parent, first.root)
            val gone = FxParent()
            val goneTree = gone.tree
            gone.dispose()
            val failure = assertFailsWith<IllegalStateException> { track(goneTree) }
            assertContains(failure.message!!, "disposed")
            assertFailsWith<IllegalStateException> { gone.tree }
        }

    @Test
    fun aTreeEventCauseChainNeverContainsSecretPlaintext() =
        storeTest {
            val parent = FxParent()
            val a = parent.a
            val tree = track(parent.tree)
            val veto = Veto<FxLeafStore>().also { a.middlewares(it) }
            veto.armed = true
            tree.handle(a).action { secret mutate "new-secret" }.shouldBeError<IllegalStateException>()
            veto.armed = false
            val errored = tree.timeline.single { it.phase == TreeEvent.Phase.Errored }
            var chain: Throwable? = errored.cause
            assertTrue(chain != null)
            while (chain != null) {
                assertFalse("hunter2" in chain.message.orEmpty() || "new-secret" in chain.message.orEmpty())
                chain = chain.cause
            }
            assertFalse("hunter2" in errored.toString() || "new-secret" in errored.toString())
        }

    @Test
    fun consumeAllPendingErrorsClearsEveryMemberHandleTheReceiversIncluded() =
        storeTest {
            val parent = FxParent()
            val tree = track(parent.tree)
            val k = parent.keyed.create("k")
            tree.handle(parent).action { error("parent failed") }
            tree.handle(parent.a).action { error("a failed") }
            tree.handle(k).action { error("k failed") }
            tree.consumeAllPendingErrors()
        }
}
