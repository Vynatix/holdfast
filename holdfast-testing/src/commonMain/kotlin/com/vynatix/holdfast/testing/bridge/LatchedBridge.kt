package com.vynatix.holdfast.testing.bridge

import com.vynatix.holdfast.Bridge
import com.vynatix.holdfast.Disposable
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CompletableDeferred

/**
 * Test [Bridge] that records publish attempts and lets the test gate further
 * progress on each attempt — useful for asserting concurrency invariants
 * around the commit/publish boundary without producing a deadlock.
 *
 * Spec deviation (documented v1 limit): the spec calls for `publish()` to
 * **suspend** until [releasePublish] is called. The [com.vynatix.holdfast.Publisher.publish]
 * contract is a non-suspending `fun publish(value: T): Boolean`, so a literal
 * suspending implementation isn't possible. A blocking-thread implementation
 * (e.g. `runBlocking { gate.await() }` inside `publish`) would deadlock the
 * test: vault commits run on the calling thread, so a blocked publish blocks
 * the same thread that needs to call [releasePublish].
 *
 * Practical implementation:
 *  - [publish] records the attempt synchronously and returns `true` immediately.
 *  - [awaitPublishAttempt] is a suspending hook that resumes on the **next**
 *    publish call after subscription. Tests use it to gate coroutines on a
 *    publish having occurred.
 *  - [releasePublish] is kept for API parity with the spec; it is a no-op in
 *    v1 because publish never suspends. Calling it is harmless.
 *
 * If a future revision of [Bridge] gains a suspending publish overload, the
 * spec's literal "publish suspends" semantics could be honoured by switching
 * the implementation; the test-author-facing API ([awaitPublishAttempt],
 * [releasePublish]) is stable across that change.
 *
 * Concurrency: every internal mutation runs under a single
 * [SynchronizedObject]. The pending-deferred list is drained under the lock
 * so a publish completes its waiters atomically with respect to a concurrent
 * `awaitPublishAttempt` call.
 */
class LatchedBridge<T : Any>(@Suppress("unused") private val initial: T) : Bridge<T> {

    private val lock = SynchronizedObject()
    private val publishedList: MutableList<T> = mutableListOf()
    private val pendingAttemptWaiters: MutableList<CompletableDeferred<T>> = mutableListOf()
    private var inboundObserver: ((T) -> Unit)? = null

    /**
     * Snapshot of every value passed to [publish] in call order. Returns a
     * defensive copy taken under the bridge's lock.
     */
    val published: List<T>
        get() = synchronized(lock) { publishedList.toList() }

    /**
     * The most recent [publish] argument, or `null` if [publish] has not been
     * called yet.
     */
    val lastPublished: T?
        get() = synchronized(lock) { publishedList.lastOrNull() }

    /**
     * Store-driven inbound subscription. Records [observer] but does NOT
     * replay any initial value — load-on-attach is a per-bridge convention,
     * and a [LatchedBridge] is typically used to test publish semantics in
     * isolation, where an unexpected initial inbound delivery can muddle the
     * timeline. If you want load-on-attach behaviour, use
     * [com.vynatix.holdfast.testing.bridge.RecordingBridge] instead.
     */
    override fun observe(observer: (T) -> Unit): Disposable {
        synchronized(lock) {
            inboundObserver = observer
        }
        return Disposable {
            synchronized(lock) {
                if (inboundObserver === observer) {
                    inboundObserver = null
                }
            }
        }
    }

    /**
     * Append [value] to [published] and return `true`. Resumes any coroutines
     * currently suspended in [awaitPublishAttempt] with [value]. Non-blocking;
     * see the class KDoc for the spec deviation rationale.
     */
    override fun publish(value: T): Boolean {
        val waitersToComplete: List<CompletableDeferred<T>> = synchronized(lock) {
            publishedList.add(value)
            val drained = pendingAttemptWaiters.toList()
            pendingAttemptWaiters.clear()
            drained
        }
        for (waiter in waitersToComplete) {
            waiter.complete(value)
        }
        return true
    }

    /**
     * Suspend until the next [publish] call, then return its argument. If
     * publish has already been called multiple times before this call, this
     * still waits for the **next** call — it does not consume from the
     * recorded history. Use [published] / [lastPublished] for past-state
     * inspection.
     *
     * Cancellation: cancelling the calling coroutine removes the deferred
     * from the pending list and propagates [kotlinx.coroutines.CancellationException]
     * the way `await()` normally would.
     */
    suspend fun awaitPublishAttempt(): T {
        val deferred = CompletableDeferred<T>()
        synchronized(lock) {
            pendingAttemptWaiters.add(deferred)
        }
        try {
            return deferred.await()
        } finally {
            // Idempotent removal: if publish() already drained the list,
            // the remove is a no-op. If the coroutine was cancelled, this
            // releases our slot.
            synchronized(lock) {
                pendingAttemptWaiters.remove(deferred)
            }
        }
    }

    /**
     * No-op in v1 — kept for API parity with the spec. See class KDoc.
     */
    fun releasePublish() {
        // Intentionally empty.
    }

    /**
     * Synthesise an inbound update through the registered observer (set by
     * the vault's bridge attachment). If no observer is attached, the call
     * is a silent no-op.
     */
    fun simulateInbound(value: T) {
        val observer = synchronized(lock) { inboundObserver }
        observer?.invoke(value)
    }
}
