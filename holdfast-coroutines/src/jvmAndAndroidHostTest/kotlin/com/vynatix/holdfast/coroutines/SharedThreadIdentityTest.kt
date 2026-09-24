@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.Disposable
import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.SettleScopes
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.derivedState
import com.vynatix.holdfast.effect
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

private class SharedLeft : Store<SharedLeft>() {
    val a by state { 0 }
}

private class SharedRight : Store<SharedRight>() {
    val b by state { 0 }
}

private class SharedHost : Store<SharedHost>() {
    val y by state { 0 }
}

/**
 * The wasmJs model on the JVM (issue #20, R9): every coroutine of a
 * `runBlocking` shares one thread, as everything shares thread id `0` on
 * wasmJs. A `suspendAction` parked there holds its store, its pending writes
 * and its settle scope while another coroutine on the same thread commits:
 * that coroutine's derived states settle at its own entry's exit — not in the
 * parked action's scope, which is uninstalled while it is parked — and never
 * read the parked action's pending writes, although its transaction's owner
 * thread is this very thread.
 */
class SharedThreadIdentityTest {
    private val disposables = mutableListOf<Disposable>()

    @AfterTest fun cleanup() {
        disposables.forEach { it.dispose() }
    }

    @Test fun aParkedSuspendActionsScopeAndWritesStayItsOwnOnASharedThread() {
        val left = SharedLeft()
        val right = SharedRight()
        val host = SharedHost()
        val pair = host.derivedState(left.a, right.b) { left.a.value to right.b.value }
        val seen = mutableListOf<Pair<Int, Int>>()
        disposables += listOf(pair effect { seen += this }, pair)
        val parked = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        var parkedScope: Any? = null
        var otherThread: Thread? = null
        var afterOtherEntry: Pair<Int, Int>? = null
        var fresh: Pair<Int, Int>? = null

        runBlocking {
            val thread = Thread.currentThread()
            launch {
                left
                    .suspendAction {
                        a mutate 1
                        parkedScope = SettleScopes.current()
                        parked.complete(Unit)
                        resume.await()
                    }.getOrThrow()
            }
            launch {
                parked.await()
                otherThread = Thread.currentThread()
                // The parked action's scope is not installed for this coroutine.
                assertNull(SettleScopes.current())
                right.action { b mutate 1 }.getOrThrow()
                afterOtherEntry = pair.value
                // Created while the parked action holds a pending write on this
                // same thread: settles from committed values at once.
                val created = host.derivedState(left.a, right.b) { left.a.value to right.b.value }
                fresh = created.value
                created.dispose()
                resume.complete(Unit)
            }.join()
            assertSame(thread, otherThread, "both coroutines ran on one thread")
        }

        assertNotNull(parkedScope, "the parked action had a scope of its own")
        assertEquals(0 to 1, afterOtherEntry, "settled at the other entry's exit, without the parked pending write")
        assertEquals(0 to 1, fresh, "a derived state created meanwhile catches up to committed values")
        assertEquals(1 to 1, pair.value, "the parked action settled its own commit once it resumed")
        assertEquals(listOf(0 to 0, 0 to 1, 1 to 1), seen)
    }
}
