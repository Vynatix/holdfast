@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.completesWithin
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertTrue

private class KodThreadStore : Store<KodThreadStore>() {
    val n by state { 0 }
}

private class KodParent : Store<KodParent>() {
    val n by state { 0 }
    val threads by keyed<Int, KodThreadStore> { KodThreadStore() }
}

/**
 * A parent disposed from inside its own action disposes its keyed stores
 * without deadlocking — also while another thread holds one of those
 * stores in an action of its own (decision 5: the keyed stores are disposed
 * after the parent's action released every lock, never under it).
 */
class KeyedOwnershipDeadlockTest {
    @Test
    fun aParentDisposedInsideItsOwnActionDisposesItsKeyedStoresWithoutDeadlock() {
        completesWithin(10, "a parent disposed inside its own action") {
            val parent = KodParent()
            val stores = (0 until 8).map { parent.threads.create(it) }
            parent action {
                n mutate 1
                stores.first() action { n mutate 1 }
                parent.dispose()
            }
            assertTrue(stores.all { it.isDisposed })
        }
    }

    @Test
    fun aKeyedStoreBusyOnAnotherThreadIsDisposedOnceItsHolderReleases() {
        completesWithin(10, "a parent dispose racing a keyed store's holder") {
            val parent = KodParent()
            val busy = parent.threads.create(1)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val holder =
                thread {
                    busy action {
                        entered.countDown()
                        release.await(5, TimeUnit.SECONDS)
                        n mutate 1
                    }
                }
            entered.await(5, TimeUnit.SECONDS)
            val disposer = thread { parent action { parent.dispose() } }
            Thread.sleep(50)
            release.countDown()
            holder.join()
            disposer.join()
            assertTrue(busy.isDisposed)
        }
    }

    @Test
    fun manyParentsDisposeTheirKeyedStoresFromInsideTheirActionsConcurrently() {
        completesWithin(20, "concurrent parents disposing inside their actions") {
            val parents = (0 until 8).map { KodParent() }
            val all = parents.flatMap { p -> (0 until 16).map { p.threads.create(it) } }
            parents.map { p -> thread { p action { p.dispose() } } }.forEach { it.join() }
            assertTrue(all.all { it.isDisposed })
        }
    }
}
