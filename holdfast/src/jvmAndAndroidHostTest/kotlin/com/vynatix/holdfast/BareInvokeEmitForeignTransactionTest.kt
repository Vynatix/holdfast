package com.vynatix.holdfast

import com.vynatix.holdfast.testing.storeTest
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private sealed interface ForeignDefusalEvent {
    data object A : ForeignDefusalEvent
}

private class ForeignEmitter : EventfulStore<ForeignEmitter, ForeignDefusalEvent>() {
    val n by state { 0 }
}

private class ForeignDelegating private constructor(
    private val support: EventfulSupport<ForeignDefusalEvent>,
) : Store<ForeignDelegating>(),
    Eventful<ForeignDefusalEvent> by support {
    constructor() : this(EventfulSupport())

    init {
        support.bindStore(this)
    }

    val n by state { 0 }
}

/**
 * A7 of the issue-005 matrix: emit's foreign-transaction hole, closed for
 * the bare-invoke case. `emit` used to consult the store-WIDE active
 * transaction first, so an emit inside a bare `store { }` block — while
 * another thread's action held the store — silently staged onto that
 * foreign transaction. The defusal flag is caller-thread-confined and is
 * checked BEFORE the transaction lookup, so the emit refuses regardless of
 * any concurrent action. Lives in `jvmAndAndroidHostTest` because pinning
 * the hole needs a real second thread blocked inside an action.
 */
class BareInvokeEmitForeignTransactionTest {
    @Test
    fun emitInsideBareInvokeRefusesEvenWhileAConcurrentActionHoldsTheStore() =
        storeTest {
            val e = track(ForeignEmitter()).store
            val insideAction = CountDownLatch(1)
            val release = CountDownLatch(1)
            val holder =
                Thread {
                    e action {
                        insideAction.countDown()
                        release.await()
                    }
                }
            holder.start()
            assertTrue(insideAction.await(5, java.util.concurrent.TimeUnit.SECONDS), "action never started")

            // Before the fix this staged onto the holder's transaction; now
            // it is the defusal teaching refusal, on the caller's thread.
            val thrown = assertFailsWith<IllegalStateException> { e { emit(ForeignDefusalEvent.A) } }
            assertEquals(BARE_INVOKE_MUTATION_MESSAGE, thrown.message)

            release.countDown()
            holder.join(5_000)
            assertEquals(0, e.n.value)
        }

    @Test
    fun emitInsideBareInvokeRefusesOnTheDelegatePathToo() =
        storeTest {
            val e = track(ForeignDelegating()).store
            val insideAction = CountDownLatch(1)
            val release = CountDownLatch(1)
            val holder =
                Thread {
                    e action {
                        insideAction.countDown()
                        release.await()
                    }
                }
            holder.start()
            assertTrue(insideAction.await(5, java.util.concurrent.TimeUnit.SECONDS), "action never started")

            val thrown = assertFailsWith<IllegalStateException> { e { emit(ForeignDefusalEvent.A) } }
            assertEquals(BARE_INVOKE_MUTATION_MESSAGE, thrown.message)

            release.countDown()
            holder.join(5_000)
            assertEquals(0, e.n.value)
        }
}
