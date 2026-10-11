@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreAttachment
import com.vynatix.holdfast.StoreAttachmentKey
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.StoreLock
import com.vynatix.holdfast.daemon
import com.vynatix.holdfast.internalAttachIfAbsent
import com.vynatix.holdfast.internalAttachment
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue
import kotlin.test.fail

private val slotHoldKey = StoreAttachmentKey<StoreAttachment>("test slot hold")

/**
 * Hold [store]'s attachment slot lock from a daemon thread — parked inside
 * another key's `create`, which the slot runs under its lock — until
 * [release]. A tree operation that must create [store]'s tree state (a
 * group's materialization listing it, which takes each listed store's tree
 * state before linking any) parks there, at a known point, so a race with
 * what runs before that point is deterministic rather than timed.
 */
internal class SlotHold(
    store: Store<*>,
) {
    private val holding = CountDownLatch(1)
    private val released = CountDownLatch(1)
    private val holder =
        daemon("slot-hold") {
            store.internalAttachIfAbsent(slotHoldKey) {
                holding.countDown()
                released.await()
                object : StoreAttachment {}
            }
        }

    init {
        assertTrue(holding.await(10, TimeUnit.SECONDS), "the slot hold took the lock")
    }

    fun release() {
        released.countDown()
        holder.join()
    }
}

/**
 * Hold [lock] — the tree's structure lock, or a store's child registry lock
 * — from a daemon thread until [release], so an attach parks at the phase
 * that takes it: the structure lock at its link (phase 3), a registry lock
 * at its claim (phase 2) or its registration (phase 4).
 */
internal class LockHold(
    lock: StoreLock,
) {
    private val holding = CountDownLatch(1)
    private val released = CountDownLatch(1)
    private val holder =
        daemon("lock-hold") {
            lock.withLock {
                holding.countDown()
                released.await()
            }
        }

    init {
        assertTrue(holding.await(10, TimeUnit.SECONDS), "the lock hold took the lock")
    }

    fun release() {
        released.countDown()
        holder.join()
    }
}

/**
 * Whether [this] has a parent in a store tree right now: its parent edge is
 * set (taken when an attach links it, cleared when that attach is undone,
 * when it disposes, or when its parent disposes and releases it). A store's
 * tree state itself says nothing about membership — it lives for the
 * store's whole life once created.
 */
internal fun Store<*>.hasTreeMembership(): Boolean = internalAttachment(treeMembershipKey)?.parentEdge?.value != null

/** Poll [condition] until it holds; fail after ten seconds. The outcome is deterministic, only the wait is not. */
internal fun awaitUntil(
    what: String,
    condition: () -> Boolean,
) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while (!condition()) {
        if (System.nanoTime() > deadline) fail("timed out waiting for $what")
        Thread.sleep(2)
    }
}
