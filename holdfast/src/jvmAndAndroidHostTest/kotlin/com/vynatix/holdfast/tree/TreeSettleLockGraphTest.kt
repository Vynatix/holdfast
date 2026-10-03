@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.Bridge
import com.vynatix.holdfast.Disposable
import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.atomic
import com.vynatix.holdfast.completesWithin
import com.vynatix.holdfast.daemon
import com.vynatix.holdfast.effect
import com.vynatix.holdfast.internalTransactionLockFree
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class TreeSettleLockGraphLeafStore : Store<TreeSettleLockGraphLeafStore>() {
    val n by state { 0 }
}

private class TreeSettleLockGraphKeyedStore(
    id: String,
) : Store<TreeSettleLockGraphKeyedStore>() {
    val title by state { "t $id" }
}

private class TreeSettleLockGraphParent : Store<TreeSettleLockGraphParent>() {
    val a by store(onParentDispose = KeyedDisposal.Release) { TreeSettleLockGraphLeafStore() }
    val b by store(onParentDispose = KeyedDisposal.Release) { TreeSettleLockGraphLeafStore() }
    val keyed by keyed<String, TreeSettleLockGraphKeyedStore> { TreeSettleLockGraphKeyedStore(it) }
}

private class TreeSettleLockGraphHostLog : Middleware<TreeValueHost>() {
    var started = 0

    override fun onTransactionStarted(context: MiddlewareContext<TreeValueHost>) {
        started++
    }
}

private class TreeSettleLockGraphReplayingBridge(
    private val replay: Int,
) : Bridge<Int> {
    override fun observe(observer: (Int) -> Unit): Disposable {
        observer(replay)
        return Disposable { }
    }

    override fun publish(value: Int): Boolean = true
}

private val StoreTree.host: TreeValueHost get() = (this as StoreTreeImpl).treeValue().host

/**
 * GUIDE §10.2 for the tree: the host lock is taken only by a settle (and by
 * the host's own dispose, deferred past every entry of its store), never
 * under a lock of a store of the subtree, and never waited for by a read.
 */
class TreeSettleLockGraphTest {
    @Test
    fun settleOnReadFromInsideAFrameBodyNeverTakesTheHostLock() {
        val parent = TreeSettleLockGraphParent()
        val tree = parent.tree
        tree.value
        val log = TreeSettleLockGraphHostLog().also { tree.host.middlewares(it) }
        atomic(parent.a, parent.b) {
            parent.keyed.create("k")
            val inside = tree.value
            assertNotNull(inside[tree.nodeOf(parent.keyed["k"]!!)!!], "fresh, uncommitted")
            assertEquals(0, log.started)
        }.getOrThrow()
        assertEquals(1, log.started, "the settle after the frame")
    }

    @Test
    fun bridgeAttachReplayWithACrossStoreValueObserverDoesNotDeadlock() =
        completesWithin(30, "a bridge replay under a value observer writing across stores") {
            val parent = TreeSettleLockGraphParent()
            val tree = parent.tree
            val a = parent.a
            val b = parent.b
            var wrote = false
            tree effect {
                if (!wrote && this[a.n] == 5) {
                    wrote = true
                    b action { n mutate 6 }
                }
            }
            a { n bridge TreeSettleLockGraphReplayingBridge(5) }
            assertEquals(5, a.n.value)
            assertEquals(6, b.n.value)
            assertEquals(6, tree.value[b.n])
        }

    @Test
    fun settleOnReadWhileTheHostIsBusyReturnsAFreshCaptureWithoutBlocking() =
        completesWithin(30, "a read beside a busy host") {
            val parent = TreeSettleLockGraphParent()
            val tree = parent.tree
            tree.value
            val holding = CountDownLatch(1)
            val release = CountDownLatch(1)
            val holder =
                daemon("host-holder") {
                    tree.host action {
                        holding.countDown()
                        release.await()
                    }
                }
            assertTrue(holding.await(10, TimeUnit.SECONDS))
            val k = parent.keyed.create("k")
            val settlesBefore = tree.internalSettleCount
            val fresh = tree.value
            assertNotNull(fresh[tree.nodeOf(k)!!], "a fresh capture, not the stale backing")
            assertEquals(settlesBefore, tree.internalSettleCount, "no settle could run: the host is held")
            release.countDown()
            holder.join()
            // The recompute handed to the host's holder ran at its tail.
            assertEquals(settlesBefore + 1, tree.internalSettleCount)
            assertNotNull(tree.value[tree.nodeOf(k)!!])
        }

    @Test
    fun parentDisposeFromAChildObserverWhileAValueObserverWritesToThatChildCompletes() =
        completesWithin(30, "parent dispose racing a value observer's write") {
            val parent = TreeSettleLockGraphParent()
            val tree = parent.tree
            val a = parent.a
            var disposedFrom = false
            a.n effect {
                if (this == 2 && !disposedFrom) {
                    disposedFrom = true
                    parent.dispose()
                }
            }
            var wrote = false
            tree effect {
                if (!wrote && this[a.n] == 1) {
                    wrote = true
                    a action { n mutate 2 }
                }
            }
            a action { n mutate 1 }
            assertTrue(parent.isDisposed)
            assertEquals(2, a.n.value)
            assertTrue(tree.internalHost().isDisposed, "the host's deferred dispose ran once the entries settled")
        }

    @Test
    fun twoParentsOverDisjointChildrenSettleIndependently() {
        val one = TreeSettleLockGraphParent()
        val two = TreeSettleLockGraphParent()
        one.tree.value
        two.tree.value
        one.a action { n mutate 1 }
        assertEquals(2, one.tree.internalSettleCount)
        assertEquals(1, two.tree.internalSettleCount)
        atomic(one.b, two.a) {
            one.b { n mutate 2 }
            two.a { n mutate 3 }
        }.getOrThrow()
        assertEquals(3, one.tree.internalSettleCount)
        assertEquals(2, two.tree.internalSettleCount)
        assertEquals(2, one.tree.value[one.b.n])
        assertEquals(3, two.tree.value[two.a.n])
    }

    /**
     * The owner disposes inside its own action while another thread's settle
     * holds the host (parked in a value observer): the dispose never waits
     * for the host under the owner's lock — the host's dispose is a settle
     * task of the action's scope, run after the action released the owner's
     * lock — and everything completes once the settle lets go.
     */
    @Test
    fun anOwnerDisposedInsideItsOwnActionWhileASettleHoldsTheHostCompletes() =
        completesWithin(30, "an owner disposed inside its own action beside a settle holding the host") {
            val parent = TreeSettleLockGraphParent()
            val tree = parent.tree
            val a = parent.a
            val last = tree.value
            val armed = AtomicBoolean(true)
            val inObserver = CountDownLatch(1)
            val release = CountDownLatch(1)
            val failures = ConcurrentLinkedQueue<Throwable>()
            tree effect {
                if (this[a.n] == 1 && armed.compareAndSet(true, false)) {
                    inObserver.countDown()
                    assertTrue(release.await(10, TimeUnit.SECONDS), "released by the test")
                }
            }
            val settler = daemon("settler", failures) { a action { n mutate 1 } }
            assertTrue(inObserver.await(10, TimeUnit.SECONDS), "a settle holds the host, parked in a value observer")

            val pastDispose = CountDownLatch(1)
            val disposer =
                daemon("disposer", failures) {
                    parent action {
                        dispose()
                        pastDispose.countDown()
                    }
                }
            assertTrue(
                pastDispose.await(10, TimeUnit.SECONDS),
                "the dispose inside the owner's action did not wait for the host",
            )
            disposer.join(200)
            assertTrue(disposer.isAlive, "its settle waits for the host's holder")
            assertTrue(parent.internalTransactionLockFree(), "having released the owner's lock first")

            release.countDown()
            settler.join()
            disposer.join()
            assertEquals(emptyList(), failures.toList())
            assertTrue(parent.isDisposed)
            assertTrue(tree.internalHost().isDisposed)
            assertEquals(1, a.n.value)
            assertEquals(1, tree.value[a.n], "the value keeps the tree the parked settle committed")
            assertSame(tree.value, tree.value)
            assertEquals(0, last[a.n])
        }
}
