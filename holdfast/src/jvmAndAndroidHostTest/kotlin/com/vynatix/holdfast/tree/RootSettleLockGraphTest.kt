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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private class LgLeafStore : Store<LgLeafStore>() {
    val n by state { 0 }
}

private class LgKeyedStore(
    id: String,
    root: LgRoot,
) : Store<LgKeyedStore>(root.keyed.at(id)) {
    val title by state { "t $id" }
}

private class LgRoot : Root("lg") {
    val a = LgLeafStore()
    val b = LgLeafStore()
    val pair by branch(a, b).named(a, "a").named(b, "b")
    val keyed by keyed<String, LgKeyedStore>()
}

private class LgHostLog : Middleware<RootHost>() {
    var started = 0

    override fun onTransactionStarted(context: MiddlewareContext<RootHost>) {
        started++
    }
}

private class LgReplayingBridge(
    private val replay: Int,
) : Bridge<Int> {
    override fun observe(observer: (Int) -> Unit): Disposable {
        observer(replay)
        return Disposable { }
    }

    override fun publish(value: Int): Boolean = true
}

/** §10.2 for the tree: the host lock is taken only by a settle, never under a leaf lock, and never waited for by a read. */
class RootSettleLockGraphTest {
    @Test
    fun settleOnReadFromInsideAFrameBodyNeverTakesTheHostLock() {
        val root = LgRoot()
        root.value.value
        val log = LgHostLog().also { root.rootValue.host.middlewares(it) }
        atomic(root.a, root.b) {
            root.keyed.create("k") { LgKeyedStore(it, root) }
            val inside = root.value.value
            assertNotNull(inside[root.nodeOf(root[root.keyed, "k"]!!)!!], "fresh, uncommitted")
            assertEquals(0, log.started)
        }.getOrThrow()
        assertEquals(1, log.started, "the settle after the frame")
    }

    @Test
    fun bridgeAttachReplayWithACrossStoreValueObserverDoesNotDeadlock() =
        completesWithin(30, "a bridge replay under a value observer writing across stores") {
            val root = LgRoot()
            var wrote = false
            root.value effect {
                if (!wrote && this[root.a.n] == 5) {
                    wrote = true
                    root.b action { n mutate 6 }
                }
            }
            root.a { n bridge LgReplayingBridge(5) }
            assertEquals(5, root.a.n.value)
            assertEquals(6, root.b.n.value)
            assertEquals(6, root.value.value[root.b.n])
        }

    @Test
    fun settleOnReadWhileTheHostIsBusyReturnsAFreshCaptureWithoutBlocking() =
        completesWithin(30, "a read beside a busy host") {
            val root = LgRoot()
            root.value.value
            val holding = CountDownLatch(1)
            val release = CountDownLatch(1)
            val holder =
                daemon("host-holder") {
                    root.rootValue.host action {
                        holding.countDown()
                        release.await()
                    }
                }
            assertTrue(holding.await(10, TimeUnit.SECONDS))
            val k = root.keyed.create("k") { LgKeyedStore(it, root) }
            val settlesBefore = root.internalSettleCount
            val fresh = root.value.value
            assertNotNull(fresh[root.nodeOf(k)!!], "a fresh capture, not the stale backing")
            assertEquals(settlesBefore, root.internalSettleCount, "no settle could run: the host is held")
            release.countDown()
            holder.join()
            // The recompute handed to the host's holder ran at its tail.
            assertEquals(settlesBefore + 1, root.internalSettleCount)
            assertNotNull(root.value.value[root.nodeOf(k)!!])
        }

    @Test
    fun rootDisposeFromALeafObserverWhileAValueObserverWritesToThatLeafCompletes() =
        completesWithin(30, "root dispose racing a value observer's write") {
            val root = LgRoot()
            var disposedFrom = false
            root.a.n effect {
                if (this == 2 && !disposedFrom) {
                    disposedFrom = true
                    root.dispose()
                }
            }
            var wrote = false
            root.value effect {
                if (!wrote && this[root.a.n] == 1) {
                    wrote = true
                    root.a action { n mutate 2 }
                }
            }
            root.a action { n mutate 1 }
            assertTrue(root.isDisposed)
            assertEquals(2, root.a.n.value)
        }

    @Test
    fun twoRootsOverDisjointLeavesSettleIndependently() {
        val one = LgRoot()
        val two = LgRoot()
        one.value.value
        two.value.value
        one.a action { n mutate 1 }
        assertEquals(2, one.internalSettleCount)
        assertEquals(1, two.internalSettleCount)
        atomic(one.b, two.a) {
            one.b { n mutate 2 }
            two.a { n mutate 3 }
        }.getOrThrow()
        assertEquals(3, one.internalSettleCount)
        assertEquals(2, two.internalSettleCount)
        assertEquals(2, one.value.value[one.b.n])
        assertEquals(3, two.value.value[two.a.n])
    }
}
