@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.wasm

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.coroutines.suspendAction
import com.vynatix.holdfast.coroutines.suspendAtomic
import com.vynatix.holdfast.keyedState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class IsolationStore : Store<IsolationStore>() {
    val n by state { 0 }
    val docs by keyedState<String, Int> { 0 }
}

private class IsolationRollback : RuntimeException("boom")

/**
 * Issue #28: while a `suspendAction`/`suspendAtomic` body is parked, its
 * transaction stays installed with the opener's thread id as owner; on wasmJs
 * every coroutine has thread id 0, so another coroutine's plain read peeked
 * the parked body's pending writes and staged evictions — which then rolled
 * back. Only the body itself (its settle scope) reads them now.
 *
 * The holder runs on Dispatchers.Default: another thread on the JVM (the
 * control), the one and only thread on wasmJs. The `...OnOneThread` tests run
 * both coroutines on runTest's own thread, which shares one thread id on the
 * JVM too.
 */
class SuspendingIsolationTest {
    // MutableState.value's owner-thread pending peek.
    @Test
    fun aParkedSuspendActionsWriteIsNotReadByAnotherCoroutine() =
        runTest {
            val s = IsolationStore()
            val parked = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            var seenInBody = -1
            var result: TransactionResult<Unit>? = null
            val holder =
                launch(Dispatchers.Default) {
                    result =
                        s.suspendAction {
                            n mutate 5
                            seenInBody = n.value
                            parked.complete(Unit)
                            gate.await()
                            throw IsolationRollback()
                        }
                }
            parked.await()
            val seenOutside = s.n.value
            gate.complete(Unit)
            holder.join()
            assertEquals(5, seenInBody, "read-your-own-writes inside the body")
            assertIs<TransactionResult.Error>(result)
            assertEquals(0, s.n.value, "the parked write rolled back")
            assertEquals(0, seenOutside, "another coroutine read a parked suspendAction's uncommitted write")
        }

    // evictionView: contains / getOrNull / entries.
    @Test
    fun aParkedSuspendActionsEvictionIsNotSeenByAnotherCoroutine() =
        runTest {
            val s = IsolationStore()
            s.docs["a"] // created and committed
            val parked = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            var hiddenInBody = false
            val holder =
                launch(Dispatchers.Default) {
                    s.suspendAction {
                        docs.evict("a")
                        hiddenInBody = "a" !in docs
                        parked.complete(Unit)
                        gate.await()
                        throw IsolationRollback()
                    }
                }
            parked.await()
            val containsOutside = "a" in s.docs
            val getOrNullOutside = s.docs.getOrNull("a")
            val keysOutside =
                s.docs.entries.keys
                    .toList()
            gate.complete(Unit)
            holder.join()
            assertTrue(hiddenInBody, "the body sees its own staged eviction")
            assertTrue("a" in s.docs, "the eviction rolled back")
            assertTrue(containsOutside, "contains saw a parked suspendAction's uncommitted eviction")
            assertNotNull(getOrNullOutside, "getOrNull saw a parked suspendAction's uncommitted eviction")
            assertEquals(listOf("a"), keysOutside, "entries saw a parked suspendAction's uncommitted eviction")
        }

    // Same peek through a suspendAtomic frame root (SuspendAtomic.kt createForExternal).
    @Test
    fun aParkedSuspendAtomicsWriteIsNotReadByAnotherCoroutine() =
        runTest {
            val a = IsolationStore()
            val b = IsolationStore()
            val parked = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            var seenInBody = -1 to -1
            val holder =
                launch(Dispatchers.Default) {
                    runCatching {
                        suspendAtomic(a, b) {
                            a { n mutate 7 }
                            b { n mutate 8 }
                            seenInBody = a.n.value to b.n.value
                            parked.complete(Unit)
                            gate.await()
                            throw IsolationRollback()
                        }
                    }
                }
            parked.await()
            val seenOutside = a.n.value to b.n.value
            gate.complete(Unit)
            holder.join()
            assertEquals(7 to 8, seenInBody, "read-your-own-writes inside the frame body")
            assertEquals(0 to 0, a.n.value to b.n.value, "the frame rolled back")
            assertEquals(0 to 0, seenOutside, "another coroutine read a parked suspendAtomic's uncommitted writes")
        }

    // Not wasm-only: both coroutines on runTest's one thread, so
    // the JVM shares the owner thread id as well.
    @Test
    fun aParkedSuspendActionsWriteIsNotReadByAnotherCoroutineOnOneThread() =
        runTest {
            val s = IsolationStore()
            s.docs["a"]
            val parked = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val holder =
                launch {
                    s.suspendAction {
                        n mutate 5
                        docs.evict("a")
                        parked.complete(Unit)
                        gate.await()
                        throw IsolationRollback()
                    }
                }
            parked.await()
            val seenOutside = s.n.value
            val containsOutside = "a" in s.docs
            gate.complete(Unit)
            holder.join()
            assertEquals(0, s.n.value)
            assertTrue("a" in s.docs)
            assertEquals(0, seenOutside, "another coroutine on the same thread read a parked uncommitted write")
            assertTrue(containsOutside, "another coroutine on the same thread saw a parked uncommitted eviction")
        }

    // The body keeps read-your-own-writes and its own
    // eviction view across suspension points on its own thread.
    @Test
    fun theBodyKeepsReadingItsOwnWritesAcrossSuspensionsOnOneThread() =
        runTest {
            val s = IsolationStore()
            s.docs["a"]
            val other = CompletableDeferred<Int>()
            val reads = mutableListOf<Int>()
            var hiddenAfterResume = false
            var entriesAfterResume: List<String>? = null
            val holder =
                launch {
                    val r =
                        s.suspendAction {
                            n mutate 5
                            docs.evict("a")
                            reads += n.value
                            yield()
                            reads += n.value
                            other.await()
                            reads += n.value
                            n.update { it + 1 }
                            reads += n.value
                            hiddenAfterResume = s.docs.getOrNull("a") == null
                            entriesAfterResume = docs.entries.keys.toList()
                        }
                    assertIs<TransactionResult.Success<*>>(r)
                }
            yield()
            other.complete(s.n.value)
            holder.join()
            assertEquals(listOf(5, 5, 5, 6), reads)
            assertTrue(hiddenAfterResume)
            assertEquals(emptyList(), entriesAfterResume)
            assertEquals(6, s.n.value)
            assertNull(s.docs.getOrNull("a"))
        }

    // A suspendAction on another store nested in the body
    // (it joins the body's settle scope) still reads the body's pending write.
    @Test
    fun aSuspendActionNestedInTheBodyReadsTheBodysWrites() =
        runTest {
            val a = IsolationStore()
            val b = IsolationStore()
            var nested = -1
            val r =
                a.suspendAction {
                    n mutate 3
                    yield()
                    b.suspendAction {
                        yield()
                        nested = a.n.value
                        n mutate nested
                    }
                }
            assertIs<TransactionResult.Success<*>>(r)
            assertEquals(3, nested)
            assertEquals(3, a.n.value)
            assertEquals(3, b.n.value)
        }

    // Control: after the holder commits, everyone reads the committed value.
    @Test
    fun aCommittedSuspendActionIsVisibleToEveryone() =
        runTest {
            val s = IsolationStore()
            s.suspendAction { n mutate 9 }
            assertEquals(9, s.n.value)
            s.action { n mutate 10 }
            assertEquals(10, s.n.value)
        }
}
