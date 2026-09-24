@file:OptIn(StoreInternalApi::class)

package com.vynatix.holdfast

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class QuietStore : Store<QuietStore>()

/** Throws from [onStoreDisposed], after logging that it was told. */
private class FailingOnDispose(
    private val told: MutableList<String>,
) : StoreAttachment {
    override fun onStoreDisposed() {
        told += "failing"
        error("teardown failed")
    }
}

/** Logs that it was told. */
private class ToldOnDispose(
    private val told: MutableList<String>,
) : StoreAttachment {
    override fun onStoreDisposed() {
        told += "told"
    }
}

/**
 * A throwing `onStoreDisposed` with no `uncaughtObserverHandler` set: logged
 * with a dispose line of its own — not the post-commit one, since there was
 * no commit — that names the store and the attachment.
 *
 * JVM/Android host only: it swaps `System.err` to capture the output.
 */
class StoreAttachmentDefaultLogTest {
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

    @Test fun aThrowingOnStoreDisposedIsLoggedAsADisposeFailureWithoutAHandler() {
        val store = QuietStore()
        val told = mutableListOf<String>()
        store.internalAttachIfAbsent(StoreAttachmentKey<FailingOnDispose>("failing")) { FailingOnDispose(told) }
        store.internalAttachIfAbsent(StoreAttachmentKey<ToldOnDispose>("told")) { ToldOnDispose(told) }

        val err = captureStdErr { store.dispose() }

        assertTrue(store.isDisposed)
        assertEquals(listOf("failing", "told"), told, "the failure does not stop the next attachment")
        assertTrue(
            "Holdfast: library machinery attached to QuietStore (FailingOnDispose) failed in onStoreDisposed" in err,
            "no dispose log line: $err",
        )
        assertTrue("uncaughtObserverHandler" in err, "the log line must point at the handler: $err")
        assertTrue("teardown failed" in err, "the stack trace follows the line: $err")
        assertFalse("post-commit side effect" in err, "a dispose failure is not a post-commit one: $err")
    }
}
