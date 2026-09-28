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
import com.vynatix.holdfast.tree.Root
import com.vynatix.holdfast.tree.StoreNode
import com.vynatix.holdfast.tree.TreeMiddleware
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class FxLeafStore : Store<FxLeafStore>() {
    val n by state { 0 }
    val secret by state(tags = setOf(StateTag.Secret)) { "hunter2-plaintext" }
}

private class FxKeyedStore(
    id: String,
    root: FxRoot,
) : Store<FxKeyedStore>(root.keyed.at(id)) {
    val title by state { "t $id" }
}

private class FxRoot : Root("fx") {
    val a = FxLeafStore()
    val b = FxLeafStore()
    val pair by branch(a, b).named(a, "a").named(b, "b")
    val keyed by keyed<String, FxKeyedStore>()
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

/** `trackTree`: every leaf tracked, one tree timeline, teardown that removes only the fixture and resets the tree. */
class TreeFixtureTest {
    @Test
    fun trackTreeAutoTracksEveryLeafAndKeyedStoresCreatedMidTest() =
        storeTest {
            val root = FxRoot()
            val tree = trackTree(root)
            root.a action { n mutate 1 }
            val k = root.keyed.create("k") { FxKeyedStore(it, root) }
            k action { title mutate "x" }
            assertEquals(1, tree.handle(root.a).transactions.size / 2, "the leaf's own recorder saw its action")
            assertEquals(1, tree.handle(k).transactions.size / 2, "a keyed store created mid-test is tracked as it joins")
            assertSame(track(k), tree.handle(k))
            assertEquals(
                listOf("Started a", "Completed a", "Started k", "Completed k"),
                tree.timeline.map { "${it.phase} ${it.node.name}" },
            )
            assertEquals(3, allTrackedHandles().size, "a, b, k — and nothing else")
        }

    @Test
    fun exactlyOneHandlePerStoreUnderParallelCreation() =
        storeTest {
            val root = FxRoot()
            val tree = trackTree(root)
            val created = parallel(8) { i -> root.keyed.create("k$i") { FxKeyedStore(it, root) } }
            assertEquals(8, created.distinct().size)
            val handles = allTrackedHandles()
            assertEquals(2 + 8, handles.size)
            assertEquals(handles.size, handles.map { it.store }.distinct().size, "one handle per store")
            for (store in created) assertSame(track(store), tree.handle(store))
        }

    @Test
    fun aThousandKeyedStoresTrackedMidTestTearDownCleanly() =
        storeTest {
            val root = FxRoot()
            trackTree(root)
            repeat(1_000) { i ->
                val k = root.keyed.create("k$i") { FxKeyedStore(it, root) }
                k action { title mutate "changed" }
            }
            assertEquals(1_002, allTrackedHandles().size)
        }

    @Test
    fun shouldCommitTogetherUsesFrameIdsAndFailsWhenTheLastParticipantVetoes() =
        storeTest {
            val root = FxRoot()
            val tree = trackTree(root)
            atomic(root.a, root.b) {
                root.a { n mutate 1 }
                root.b { n mutate 2 }
            }.getOrThrow()
            val frame = tree.shouldCommitTogether(root.pair)
            assertTrue(frame.startsWith("atomic-"))
            assertEquals(listOf(frame), tree.committedFrameIds(root))
            tree.group(root.pair).shouldCommitTogether()

            val vetoed = FxRoot()
            val vetoedTree = trackTree(vetoed, resetAtTeardown = false)
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
            assertEquals(emptyList<String>(), vetoedTree.committedFrameIds(vetoed), "a vetoed frame committed nowhere")
        }

    @Test
    fun eventsOfADisposedKeyedStoreStayVisibleUnderItsBranch() =
        storeTest {
            val root = FxRoot()
            val tree = trackTree(root)
            val k = root.keyed.create("k") { FxKeyedStore(it, root) }
            val leaf = root.nodeOf(k)!!
            k action { title mutate "x" }
            k.dispose()
            assertEquals(listOf("Started k", "Completed k"), tree.events(root.keyed).map { "${it.phase} ${it.node.name}" })
            assertEquals(2, tree.events(leaf).size)
            assertEquals(2, tree.events(root).size)
            assertEquals(0, tree.events(root.pair).size)
            assertSame(track(k), tree.handle(k), "a former member still answers its handle")
        }

    @Test
    fun teardownRemovesOnlyTheFixtureAndKeepsUserAndTreeMiddleware() {
        val root = FxRoot()
        val user = CountingConsumer<FxLeafStore>().also { root.a.middlewares(it) }
        val treeMiddleware = CountingTree().also { root.middlewares(it) }
        var handle: TreeHandle? = null
        storeTest {
            handle = trackTree(root)
            root.a action { n mutate 1 }
            assertEquals(2, handle!!.timeline.size)
        }
        // The teardown reset ran through both (a's user middleware once, the tree's for a and b).
        assertEquals(2, user.started)
        assertEquals(3, treeMiddleware.started)
        root.a action { n mutate 2 }
        assertEquals(3, user.started, "the user middleware stayed installed")
        assertEquals(4, treeMiddleware.started, "the tree middleware stayed installed")
        assertTrue(handle!!.timeline.isEmpty(), "the fixture's recorder is gone from the root")
        storeTest {
            assertTrue(root.a.timeline.isEmpty(), "a fresh handle in a fresh scope")
        }
    }

    @Test
    fun teardownResetsTheTreeSoASecondTestSeesInitialValuesAndSkipsDisposedLeaves() {
        val root = FxRoot()
        storeTest {
            trackTree(root)
            root.a action { n mutate 5 }
            val k = root.keyed.create("k") { FxKeyedStore(it, root) }
            k action { title mutate "x" }
            k.dispose()
            val kept = root.keyed.create("kept") { FxKeyedStore(it, root) }
            kept action { title mutate "changed" }
        }
        storeTest {
            trackTree(root)
            assertEquals(0, root.a.n.value)
            assertEquals("t kept", root[root.keyed, "kept"]!!.title.value)
            assertFalse(root.isDisposed, "the root is never disposed by teardown")
        }
    }

    @Test
    fun aVetoedResetFailsTheTestNamingTheLeafAndTheVeto() {
        val root = FxRoot()
        val veto = Veto<FxLeafStore>().also { root.b.middlewares(it) }
        val failure =
            assertFailsWith<AssertionError> {
                storeTest {
                    trackTree(root)
                    root.b action { n mutate 1 }
                    veto.armed = true
                }
            }
        assertContains(failure.message!!, "could not reset")
        assertContains(failure.message!!, "leaf 'b'")
        assertContains(failure.message!!, "IllegalStateException: rejected by policy")
        veto.armed = false
        assertEquals(1, root.b.n.value, "the vetoed reset left the value")
    }

    @Test
    fun aResetFailureIsSuppressedWhenTheBodyAlreadyFailed() {
        val root = FxRoot()
        val veto = Veto<FxLeafStore>().also { root.b.middlewares(it) }
        veto.armed = true
        val failure =
            assertFailsWith<IllegalArgumentException> {
                storeTest {
                    trackTree(root)
                    throw IllegalArgumentException("the body's own failure")
                }
            }
        assertEquals("the body's own failure", failure.message)
    }

    @Test
    fun resetAtTeardownCanBeOptedOut() {
        val root = FxRoot()
        storeTest {
            trackTree(root, resetAtTeardown = false)
            root.a action { n mutate 9 }
        }
        assertEquals(9, root.a.n.value)
    }

    @Test
    fun trackTreeIsIdempotentByRootIdentityAndThrowsOnADisposedRoot() =
        storeTest {
            val root = FxRoot()
            val first = trackTree(root)
            assertSame(first, trackTree(root, Capture.None, resetAtTeardown = false))
            val gone = FxRoot()
            gone.dispose()
            val failure = assertFailsWith<IllegalStateException> { trackTree(gone) }
            assertContains(failure.message!!, "disposed")
        }

    @Test
    fun aTreeEventCauseChainNeverContainsSecretPlaintext() =
        storeTest {
            val root = FxRoot()
            val tree = trackTree(root)
            val veto = Veto<FxLeafStore>().also { root.a.middlewares(it) }
            veto.armed = true
            tree.handle(root.a).action { secret mutate "new-secret" }.shouldBeError<IllegalStateException>()
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
    fun consumeAllPendingErrorsClearsEveryLeafHandle() =
        storeTest {
            val root = FxRoot()
            val tree = trackTree(root)
            val k = root.keyed.create("k") { FxKeyedStore(it, root) }
            tree.handle(root.a).action { error("a failed") }
            tree.handle(k).action { error("k failed") }
            tree.consumeAllPendingErrors()
        }
}
