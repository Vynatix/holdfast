package com.vynatix.holdfast

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class LoudStore : Store<LoudStore>() {
    val trigger by state { 0 }
    val echo by state { 0 }
}

/**
 * The default for post-commit failures with no `uncaughtObserverHandler`
 * installed: logged loudly on standard error, where they used to be dropped
 * without a trace. Without it, the write refused by [FanoutWriteTest]'s cases
 * would still vanish for every store that never set a handler — which is
 * nearly all of them.
 *
 * JVM/Android host only: it swaps `System.err` to capture the output.
 */
class FanoutWriteDefaultLogTest {
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

    /** Run [body] with `System.err` captured, and return what it printed. */
    private fun captureStdErr(body: () -> Unit): String {
        val original = System.err
        val buffer = ByteArrayOutputStream()
        System.setErr(PrintStream(buffer, true, Charsets.UTF_8))
        try {
            body()
        } finally {
            System.setErr(original)
        }
        return buffer.toString(Charsets.UTF_8)
    }

    @Test fun observerWriteIntoItsOwnCommittingStoreIsLoggedWithoutAHandler() {
        val s = LoudStore()
        onCommit(s.trigger) { value -> s { echo mutate value } }
        val seen = mutableListOf<Int>()
        onCommit(s.trigger) { seen += it }
        var r: TransactionResult<*>? = null

        val err = captureStdErr { r = s action { trigger mutate 1 } }

        assertIs<TransactionResult.Success<*>>(r)
        assertEquals(1, s.trigger.value)
        assertEquals(listOf(1), seen, "the failing observer does not stop the next one")
        assertEquals(0, s.echo.value)
        assertTrue("Holdfast: a post-commit side effect of LoudStore failed" in err, "no default log line: $err")
        assertTrue("uncaughtObserverHandler" in err, "the log line must point at the handler: $err")
        assertTrue("java.lang.IllegalStateException: Cannot write LoudStore.echo" in err, "no stack trace: $err")
    }

    @Test fun throwingObserverIsLoggedWithoutAHandler() {
        val s = LoudStore()
        onCommit(s.trigger) { error("observer failed on $it") }

        val err = captureStdErr { s action { trigger mutate 7 } }

        assertEquals(7, s.trigger.value)
        assertTrue("Holdfast: a post-commit side effect of LoudStore failed" in err, "no default log line: $err")
        assertTrue("observer failed on 7" in err, "the observer's exception is missing: $err")
    }

    @Test fun throwingBridgePublishIsLoggedWithoutAHandler() {
        val s = LoudStore()
        s {
            trigger bridge
                object : Bridge<Int> {
                    override fun observe(observer: (Int) -> Unit): Disposable = Disposable {}

                    override fun publish(value: Int): Boolean = error("disk full at $value")
                }
        }

        var r: TransactionResult<*>? = null
        val err = captureStdErr { r = s action { trigger mutate 3 } }

        assertIs<TransactionResult.Success<*>>(r, "a failed publish cannot undo the commit")
        assertTrue("disk full at 3" in err, "the publish failure is missing: $err")
    }

    @Test fun throwingDerivedRecomputeIsLoggedWithoutAHandler() {
        val s = LoudStore()
        val (derivedValue, subscription) =
            s.derived(s.trigger) {
                check(trigger.value < 5) { "derived failed on ${trigger.value}" }
                trigger.value
            }
        disposables += subscription

        var r: TransactionResult<*>? = null
        val err = captureStdErr { r = s action { trigger mutate 5 } }

        assertIs<TransactionResult.Success<*>>(r, "the source commit itself is unaffected")
        assertEquals(0, derivedValue.value, "a failed recompute keeps the previous value")
        assertTrue("Holdfast: a post-commit side effect of LoudStore failed" in err, "no default log line: $err")
        assertTrue("derived failed on 5" in err, "the recompute's exception is missing: $err")
    }

    @Test fun anInstalledHandlerReplacesTheDefaultLog() {
        val s = LoudStore()
        val handled = mutableListOf<Throwable>()
        s.uncaughtObserverHandler = { handled += it }
        onCommit(s.trigger) { value -> s { echo mutate value } }

        val err = captureStdErr { s action { trigger mutate 1 } }

        assertEquals(1, handled.size)
        assertTrue("Holdfast:" !in err, "a handler must replace the default log, not add to it: $err")
    }
}
