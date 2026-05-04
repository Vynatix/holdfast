package com.vynatix.holdfast.testing.concurrency

import com.vynatix.holdfast.testing.HoldfastTestScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Spawn [n] coroutines on [on], await every one, and return their results in
 * worker-index order.
 *
 * The default dispatcher is [Dispatchers.Default] — **not** the test scheduler
 * that drives `holdfastTest`. Holdfast's `action { … }` acquires its lock with a
 * blocking spin-loop; on a single-threaded test dispatcher every worker would
 * pin the only thread at the spin-loop and the call would deadlock the moment
 * any worker tried to commit a transaction. Real threads from
 * [Dispatchers.Default] make progress.
 *
 * Pass an explicit dispatcher only if [body] is purely suspending and never
 * touches a tracked vault.
 *
 * If any worker throws, the whole call fails with that exception; siblings are
 * cancelled cooperatively via [coroutineScope]'s structured-concurrency
 * semantics.
 *
 * @param n number of workers to spawn; `0` returns an empty list immediately.
 * @param on dispatcher each worker runs on; defaults to [Dispatchers.Default].
 * @param body suspend block invoked once per worker with its zero-based index.
 */
suspend fun <R> HoldfastTestScope.parallel(
    n: Int,
    on: CoroutineDispatcher = Dispatchers.Default,
    body: suspend (workerIndex: Int) -> R,
): List<R> {
    if (n == 0) return emptyList()
    return coroutineScope { (0 until n).map { i -> async(on) { body(i) } }.awaitAll() }
}
