@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.testing.internal

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.testing.Capture
import com.vynatix.holdfast.testing.TreeEvent
import com.vynatix.holdfast.tree.StoreNode
import com.vynatix.holdfast.tree.TreeMiddleware
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.time.Clock

/**
 * The tree fixture's recorder: a [TreeMiddleware] the fixture installs
 * through `StoreTree.middlewares` — outermost on every member, attached now
 * or later — that turns each hook into a [TreeEvent], buffered under a lock
 * with the same [Capture] policy as a store's recorder. Writes happen on the
 * transaction's thread; [snapshot] reads from any thread.
 */
internal class TreeRecorder(
    private val capture: Capture,
) : TreeMiddleware() {
    private val lock = SynchronizedObject()
    private val events = mutableListOf<Recorded>()

    /** One event with the node chain it was recorded under: its node, then each ancestor up to the root. */
    private class Recorded(
        val event: TreeEvent,
        val ancestry: List<StoreNode>,
    )

    /** Defensive copy; safe to iterate after return. */
    fun snapshot(): List<TreeEvent> = synchronized(lock) { events.map { it.event } }

    /**
     * The events recorded at [node] or beneath it — judged by the ancestry
     * each event was recorded under, never by today's parent links: a store
     * released since (its parent disposed, it is a subtree root now) keeps
     * its earlier events under its former ancestors.
     */
    fun recordedUnder(node: StoreNode): List<TreeEvent> =
        synchronized(lock) { events.filter { recorded -> recorded.ancestry.any { it === node } }.map { it.event } }

    override fun onTransactionStarted(
        node: StoreNode,
        context: Middleware.MiddlewareContext<*>,
    ) {
        push(node, context, TreeEvent.Phase.Started, null)
    }

    override fun onTransactionCompleted(
        node: StoreNode,
        context: Middleware.MiddlewareContext<*>,
    ) {
        push(node, context, TreeEvent.Phase.Completed, null)
    }

    override fun onTransactionError(
        node: StoreNode,
        context: Middleware.MiddlewareContext<*>,
        error: Throwable,
    ) {
        push(node, context, TreeEvent.Phase.Errored, error)
    }

    /** Drop every recorded event. Does not uninstall the middleware (the fixture's teardown does). */
    fun dispose() {
        synchronized(lock) { events.clear() }
    }

    private fun push(
        node: StoreNode,
        context: Middleware.MiddlewareContext<*>,
        phase: TreeEvent.Phase,
        cause: Throwable?,
    ) {
        if (capture is Capture.None) return
        val now = Clock.System.now().toEpochMilliseconds()
        val event = TreeEvent(node, context.store, phase, context.transaction, cause, now)
        val ancestry = generateSequence(node) { it.parent }.toList()
        synchronized(lock) {
            events.add(Recorded(event, ancestry))
            if (capture is Capture.RingBuffer) {
                while (events.size > capture.size) events.removeAt(0)
            }
        }
    }
}
