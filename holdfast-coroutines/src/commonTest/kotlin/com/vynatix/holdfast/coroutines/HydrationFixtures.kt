@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.StateTag
import com.vynatix.holdfast.Store
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CompletableDeferred

/**
 * A stand-in for a remote source (issue #20, R8 tests): counts its fetches,
 * and answers the n-th (1-based) with [answer] — which may suspend, or throw.
 */
internal class FakeRemote<T>(
    private val answer: suspend (call: Int) -> T,
) {
    private val count = atomic(0)

    /** How many fetches have started. */
    val fetches: Int get() = count.value

    suspend fun fetch(): T = answer(count.incrementAndGet())
}

/** A remote whose every fetch waits for [release], then answers with its value (or throws its failure). */
internal class GatedRemote<T> {
    private val count = atomic(0)
    val release = CompletableDeferred<T>()
    val fetches: Int get() = count.value

    suspend fun fetch(): T {
        count.incrementAndGet()
        return release.await()
    }
}

/** A feed a user pins items of, whose items sync adopts. */
internal class FeedStore(
    remote: suspend () -> List<String>,
) : Store<FeedStore>() {
    val pinned by state(tags = setOf(StateTag.UserAuthored)) { emptySet<String>() }
    val items by state(tags = setOf(StateTag.Remote)) { emptyList<String>() }

    /** How many times `base { }` has run. */
    var baseRuns = 0

    val hydration =
        hydrator {
            base {
                baseRuns++
                items mutate listOf("seed")
            }
            refresh { remote() } adopt { fetched -> items mutate fetched }
        }
}

/**
 * Records each transaction's id as middleware sees it — started, then
 * completed or errored — and can reject the ones [reject] picks from
 * `onTransactionCompleted`, which rolls them back.
 */
internal class HydrationMiddlewareLog<V : Store<V>>(
    private val reject: (id: String) -> Boolean = { false },
) : Middleware<V>() {
    private val lock = SynchronizedObject()
    private val events = mutableListOf<String>()

    /** What it has recorded so far. */
    fun events(): List<String> = synchronized(lock) { events.toList() }

    override fun onTransactionStarted(context: MiddlewareContext<V>) {
        record("started ${context.transaction.id}")
    }

    override fun onTransactionCompleted(context: MiddlewareContext<V>) {
        record("completed ${context.transaction.id}")
        check(!reject(context.transaction.id)) { "rejected ${context.transaction.id}" }
    }

    override fun onTransactionError(
        context: MiddlewareContext<V>,
        error: Throwable,
    ) {
        record("error ${context.transaction.id}")
    }

    private fun record(event: String) {
        synchronized(lock) { events += event }
    }
}
