package com.vynatix.holdfast.debug

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.vynatix.holdfast.ExperimentalStoreApi
import java.io.FileDescriptor
import java.io.PrintWriter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The adb transport. Declared by this module's manifest (merged into the app),
 * so the framework instantiates it at process start with zero app code.
 *
 * Two ways in, both authenticated by the OS rather than by this library —
 * only the shell and system processes hold `android.permission.DUMP`:
 *
 * ```
 * # streams the console output straight back to the terminal
 * adb shell dumpsys activity provider <applicationId>/com.vynatix.holdfast.debug.HoldfastDebugProvider stores
 *
 * # same interpreter through ContentProvider.call (prints a Bundle)
 * adb shell content call --uri content://<applicationId>.holdfast-debug --method exec --arg "dump cart"
 * ```
 *
 * `dumpsys` matches the flattened component name, so the short form
 * `dumpsys activity provider HoldfastDebugProvider …` also works and reaches
 * every running app that embeds the module. Arguments pass through two shells
 * (yours and the device's); quote a whole command containing spaces once more:
 * `adb shell "dumpsys … set cart label 'Two words'"`.
 *
 * `dump()` runs on the app's main thread (ActivityThread dispatches
 * `DUMP_PROVIDER` on the main Looper), so it cannot answer while the main
 * thread is wedged — `content call` runs on a binder thread and still can.
 *
 * Argument-less `dump()` prints one summary line and no state: `adb bugreport`
 * runs argument-less `dumpsys` over every provider, and a bugreport attached
 * to a public ticket must never carry store contents.
 */
@ExperimentalStoreApi
class HoldfastDebugProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        // Mutating commands hop to the main thread unless the app installed its own policy.
        if (HoldfastDebug.mutationRunner === MutationRunner.Inline) {
            HoldfastDebug.mutationRunner = MainThreadMutationRunner()
        }
        return true
    }

    override fun dump(
        fd: FileDescriptor,
        writer: PrintWriter,
        args: Array<out String>?,
    ) {
        val output = runCatching { HoldfastDebug.execute(args?.toList() ?: emptyList()) }
        writer.println(output.getOrElse { "error: ${it::class.simpleName}: ${it.message}" })
        writer.flush()
    }

    override fun call(
        method: String,
        arg: String?,
        extras: Bundle?,
    ): Bundle? {
        if (method != METHOD_EXEC) return null
        val argv = extras?.getStringArray(EXTRA_ARGS)?.toList()
        val output =
            runCatching { if (argv != null) HoldfastDebug.execute(argv) else HoldfastDebug.execute(arg ?: "") }
                .getOrElse { "error: ${it::class.simpleName}: ${it.message}" }
        return Bundle().apply { putString(EXTRA_OUTPUT, output) }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        /** `content call --method exec`. */
        const val METHOD_EXEC = "exec"

        /** Optional `String[]` extra with pre-split argv (beats `--arg` when values contain spaces). */
        const val EXTRA_ARGS = "args"

        /** Key of the console output in the returned Bundle. */
        const val EXTRA_OUTPUT = "output"
    }
}

/**
 * Runs mutating commands on the main thread — inline when already there
 * (`dumpsys`), otherwise posted and awaited with a timeout (`content call`
 * arrives on a binder thread). On timeout the posted task is cancelled if it
 * has not started yet, so the answer is never "applied" or "not applied"
 * without knowing which.
 */
@ExperimentalStoreApi
class MainThreadMutationRunner(
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) : MutationRunner {
    private val handler = Handler(Looper.getMainLooper())

    override fun run(block: () -> String): String {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        // Whoever wins this CAS owns the outcome: the task (it runs) or the
        // timeout (the task sees `claimed` already set and does nothing).
        val claimed = AtomicBoolean(false)
        val done = CountDownLatch(1)
        var result: String? = null
        handler.post {
            if (!claimed.compareAndSet(false, true)) return@post
            result = runCatching(block).getOrElse { "error: ${it::class.simpleName}: ${it.message}" }
            done.countDown()
        }
        val finished = done.await(timeoutMillis, TimeUnit.MILLISECONDS)
        return when {
            finished -> result ?: "error: no output"
            claimed.compareAndSet(false, true) ->
                "TIMEOUT: main thread did not pick up the command within ${timeoutMillis}ms; not applied"
            else ->
                "TIMEOUT: command started on the main thread but has not finished after ${timeoutMillis}ms; outcome unknown"
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 5_000L
    }
}
