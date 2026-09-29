@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreAttachment
import com.vynatix.holdfast.StoreAttachmentKey
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.daemon
import com.vynatix.holdfast.internalAttachIfAbsent
import com.vynatix.holdfast.internalAttachments
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue
import kotlin.test.fail

private val slotHoldKey = StoreAttachmentKey<StoreAttachment>("test slot hold")

/**
 * Hold [store]'s attachment slot lock from a daemon thread — parked inside
 * another key's `create`, which the slot runs under its lock — until
 * [release]. A tree operation that must attach to or detach from [store]
 * (a branch declaration listing it, a root's dispose releasing it) parks
 * there, at a known point, so a race with what runs before that point is
 * deterministic rather than timed.
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

/** Whether [this] holds a tree membership right now. */
internal fun Store<*>.hasTreeMembership(): Boolean = internalAttachments().any { it is TreeLeafAttachment }

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
