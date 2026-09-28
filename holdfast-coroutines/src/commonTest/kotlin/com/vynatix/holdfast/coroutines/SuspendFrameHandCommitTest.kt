@file:OptIn(StoreInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.Disposable
import com.vynatix.holdfast.State
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.Transaction
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.TransactionStatus
import com.vynatix.holdfast.effect
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private class HandCommitSource : Store<HandCommitSource>() {
    val trigger by state { 0 }
}

private class HandCommitTarget : Store<HandCommitTarget>() {
    val copy by state { 0 }
}

/** A suspending bridge that logs its awaited publishes. */
private class LoggingSuspendingBridge(
    private val label: String,
    private val log: MutableList<String>,
) : SuspendingBridge<Int> {
    override fun observe(observer: (Int) -> Unit): Disposable = Disposable { }

    override fun publish(value: Int): Boolean {
        log += "$label.publish=$value"
        return true
    }

    override suspend fun publishAwaited(value: Int) {
        log += "$label.publishAwaited=$value"
    }
}

/**
 * A blocking `commit()` by hand on a `suspendAtomic` participant that the
 * frame has applied but not yet fanned out is a no-op (issue #20, R9 and D16;
 * the blocking frame's cases are in `:holdfast`'s `FrameHandCommitTest`).
 * It used to consume the participant's fanout input, so its observers never
 * fired and its [SuspendingBridge.publishAwaited] never ran, while the frame
 * reported Success.
 */
class SuspendFrameHandCommitTest {
    private val disposables = mutableListOf<Disposable>()

    @AfterTest fun cleanup() {
        disposables.forEach { it.dispose() }
    }

    private fun <T : Any> onCommit(
        state: State<T>,
        react: (T) -> Unit,
    ) {
        var initial = true
        disposables +=
            state.effect {
                if (initial) initial = false else react(this)
            }
    }

    @Test fun handCommitOfALaterParticipantFromAnObserverIsANoOp() =
        runBlocking {
            val s = HandCommitSource()
            val t = HandCommitTarget()
            assertTrue(s.lockOrderKey < t.lockOrderKey)
            val failures = mutableListOf<Throwable>()
            s.uncaughtObserverHandler = { failures += it }
            t.uncaughtObserverHandler = { failures += it }
            val log = mutableListOf<String>()
            var root: Transaction? = null
            t { copy bridge LoggingSuspendingBridge("t", log) }
            onCommit(s.trigger) { value ->
                log += "s.trigger=$value"
                root = t.activeTransaction
                root?.commit()
            }
            onCommit(t.copy) { value -> log += "t.copy=$value" }

            val r =
                suspendAtomic(s, t) {
                    s { trigger mutate 1 }
                    t { copy mutate 2 }
                }

            assertIs<TransactionResult.Success<*>>(r)
            assertEquals(
                listOf("s.trigger=1", "t.copy=2", "t.publishAwaited=2"),
                log,
                "t's observer and awaited publish run exactly once, from the frame's suspending fanout, after s's",
            )
            assertEquals(2, t.copy.value)
            assertEquals(TransactionStatus.Committed, assertNotNull(root, "s's observer saw t's frame root").status)
            assertEquals(emptyList(), failures)
        }
}
