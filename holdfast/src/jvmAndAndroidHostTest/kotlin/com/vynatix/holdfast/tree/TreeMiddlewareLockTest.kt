@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.completesWithin
import com.vynatix.holdfast.daemon
import com.vynatix.holdfast.internalAttachment
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class LkLeafStore : Store<LkLeafStore>() {
    val n by state { 0 }
}

private class LkKeyedStore(
    val id: Int,
) : Store<LkKeyedStore>() {
    val n by state { 0 }
}

private class LkParent : Store<LkParent>() {
    val a by store { LkLeafStore() }
    val b by store { LkLeafStore() }
    val keyed by keyed<Int, LkKeyedStore> { LkKeyedStore(it) }
}

/** The tree middleware [store] installed itself (`tree.middlewares` on its own handle). */
private fun Store<*>.installedTreeMiddleware(): List<TreeMiddleware> = internalAttachment(treeMembershipKey)?.installed.orEmpty()

/** Thread-safe: counts started and terminal hooks per transaction. */
private class CountingMiddleware : TreeMiddleware() {
    val started = ConcurrentHashMap<String, Int>()
    val terminal = ConcurrentHashMap<String, Int>()

    override fun onTransactionStarted(
        node: StoreNode,
        context: Middleware.MiddlewareContext<*>,
    ) {
        started.merge(key(context), 1, Int::plus)
    }

    override fun onTransactionCompleted(
        node: StoreNode,
        context: Middleware.MiddlewareContext<*>,
    ) {
        terminal.merge(key(context), 1, Int::plus)
    }

    override fun onTransactionError(
        node: StoreNode,
        context: Middleware.MiddlewareContext<*>,
        error: Throwable,
    ) {
        terminal.merge(key(context), 1, Int::plus)
    }

    private fun key(context: Middleware.MiddlewareContext<*>): String =
        "${context.store.lockOrderKey}/${context.transaction.id}@${context.transaction.hashCode()}"
}

private class Recording : TreeMiddleware() {
    val events = ConcurrentLinkedQueue<String>()

    override fun onTransactionStarted(
        node: StoreNode,
        context: Middleware.MiddlewareContext<*>,
    ) {
        events += "started ${node.name}"
    }

    override fun onTransactionCompleted(
        node: StoreNode,
        context: Middleware.MiddlewareContext<*>,
    ) {
        events += "completed ${node.name}"
    }
}

/** The tree ring against concurrent member actions, creates and disposes: never a deadlock, never a lost terminal. */
class TreeMiddlewareLockTest {
    @Test
    fun installCompletesWhileAMemberActionIsInFlightAndDoesNotRetroApply() =
        completesWithin(30, "install beside an in-flight member action") {
            val parent = LkParent()
            val a = parent.a
            val inside = CountDownLatch(1)
            val release = CountDownLatch(1)
            val worker =
                daemon("in-flight") {
                    a action {
                        inside.countDown()
                        release.await()
                    }
                }
            assertTrue(inside.await(10, TimeUnit.SECONDS))
            val recording = Recording()
            parent.tree.middlewares(recording)
            assertEquals(emptyList<String>(), recording.events.toList(), "installed, without waiting for the action")
            release.countDown()
            worker.join()
            assertEquals(emptyList<String>(), recording.events.toList(), "the in-flight action's chain was snapshotted before")
            a action { }
            assertEquals(listOf("started a", "completed a"), recording.events.toList())
        }

    @Test
    fun removeDuringAParkedBlockingActionStillDeliversCompleted() =
        completesWithin(30, "remove beside a parked member action") {
            val parent = LkParent()
            val a = parent.a
            val recording = Recording()
            parent.tree.middlewares(recording)
            val inside = CountDownLatch(1)
            val release = CountDownLatch(1)
            val worker =
                daemon("parked") {
                    a action {
                        inside.countDown()
                        release.await()
                    }
                }
            assertTrue(inside.await(10, TimeUnit.SECONDS))
            assertTrue(parent.tree.removeMiddleware(recording))
            release.countDown()
            worker.join()
            assertEquals(listOf("started a", "completed a"), recording.events.toList())
            a action { }
            assertEquals(2, recording.events.size, "nothing new after the removal")
        }

    @Test
    fun concurrentCreateDisposeInstallAndRemoveLeaveTheRingConsistent() =
        completesWithin(120, "ring stress") {
            val parent = LkParent()
            val tree = parent.tree
            val a = parent.a
            val b = parent.b
            val failures = ConcurrentLinkedQueue<Throwable>()
            val stop = AtomicBoolean(false)
            val middlewares = List(3) { CountingMiddleware() }
            val churn =
                daemon("churn", failures) {
                    var i = 0
                    while (!stop.get()) {
                        val k = parent.keyed.create(i)
                        k action { n mutate 1 }
                        a action { n mutate i }
                        k.dispose()
                        i++
                    }
                }
            val staleAdapters = AtomicInteger()
            val installer =
                daemon("installer", failures) {
                    repeat(200) { round ->
                        val m = middlewares[round % middlewares.size]
                        tree.middlewares(m)
                        b action { n mutate round }
                        val held = listOf(parent, a, b).flatMap { it.treeRingAdapters() }.filter { it.middleware === m }
                        tree.removeMiddleware(m)
                        // Once removal returned: its adapters are retired (no new observation starts) and off every ring.
                        if (held.any { !it.isRetired }) staleAdapters.incrementAndGet()
                        for (member in listOf(parent, a, b)) {
                            if (member.treeRingAdapters().any { it.middleware === m }) staleAdapters.incrementAndGet()
                        }
                    }
                }
            installer.join()
            stop.set(true)
            churn.join()
            assertEquals(emptyList<Throwable>(), failures.toList())
            assertEquals(0, staleAdapters.get(), "a removed middleware's adapters are retired and off every ring")
            for (m in middlewares) {
                for ((key, count) in m.started) assertEquals(count, m.terminal[key], "every started has its terminal: $key")
            }
            assertEquals(listOf<Store<*>>(parent, a, b), tree.stores(), "every keyed store left with its dispose")
            assertTrue(listOf(parent, a, b).all { it.treeRingAdapters().isEmpty() }, "nothing installed, nothing on a ring")
            tree.middlewares(middlewares[0], middlewares[1])
            assertEquals(listOf<TreeMiddleware>(middlewares[0], middlewares[1]), parent.installedTreeMiddleware())
            for (member in listOf(parent, a, b)) {
                assertEquals(parent.installedTreeMiddleware(), member.treeRingAdapters().map { it.middleware })
            }
        }

    @Test
    fun aKeyedStoreCreatedInsideAnotherMembersActionAttachesWithoutDeadlock() =
        completesWithin(30, "create inside an action under the ring") {
            val parent = LkParent()
            val recording = Recording()
            parent.tree.middlewares(recording)
            var k: LkKeyedStore? = null
            parent.a action { k = parent.keyed.create(1) }
            k!! action { n mutate 1 }
            assertEquals(listOf("started a", "completed a", "started 1", "completed 1"), recording.events.toList())
        }

    @Test
    fun disposeFromInsideItsOwnActionWhileInstallFansOutDoesNotDeadlock() =
        completesWithin(30, "dispose inside an action during install") {
            val parent = LkParent()
            val recording = Recording()
            val k = parent.keyed.create(1)
            val inside = CountDownLatch(1)
            val installed = CountDownLatch(1)
            val worker =
                daemon("self-dispose") {
                    k action {
                        inside.countDown()
                        installed.await(5, TimeUnit.SECONDS)
                        dispose()
                    }
                }
            assertTrue(inside.await(10, TimeUnit.SECONDS))
            parent.tree.middlewares(recording)
            installed.countDown()
            worker.join()
            assertTrue(k.isDisposed)
            assertEquals(listOf<Store<*>>(parent, parent.a, parent.b), parent.tree.stores())
            parent.a action { }
            assertEquals(listOf("started a", "completed a"), recording.events.toList())
        }

    @Test
    fun aTreeHookCallingSnapshotFromUnderAMemberLockDoesNotDeadlock() =
        completesWithin(30, "snapshot from a tree hook") {
            val parent = LkParent()
            val a = parent.a
            val b = parent.b
            val tree = parent.tree
            var seen: Int? = null
            tree.middlewares(
                object : TreeMiddleware() {
                    override fun onTransactionCompleted(
                        node: StoreNode,
                        context: Middleware.MiddlewareContext<*>,
                    ) {
                        seen = tree.snapshot()[b.n]
                    }
                },
            )
            val other = daemon("other") { repeat(200) { b action { n mutate it } } }
            repeat(200) { a action { n mutate it } }
            other.join()
            assertTrue(seen != null)
        }
}
