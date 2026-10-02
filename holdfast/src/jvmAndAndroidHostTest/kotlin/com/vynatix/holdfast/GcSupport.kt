package com.vynatix.holdfast

import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private const val GARBAGE_CHUNK_BYTES = 64 * 1024
private const val GARBAGE_CHUNKS_PER_ROUND = 64
private const val ROUND_PAUSE_MS = 10L

/** Written every round so the JIT cannot prove the garbage dead and skip allocating it. */
private val garbageSink = AtomicReference<Any?>(null)

/**
 * Wait until [ref]'s referent has been collected, for up to [timeout], and
 * return whether it was — the caller asserts on the reference itself.
 *
 * Each round asks for a collection and then allocates and drops a few
 * megabytes of garbage, so a JVM that ignores an explicit `System.gc()`
 * (`-XX:+DisableExplicitGC`), or clears weak references lazily, still
 * collects the referent within the budget instead of failing a test that
 * found no defect. Never `System.runFinalization()`: it is deprecated for
 * removal since JDK 18, and a no-op for objects without a finalizer anyway.
 *
 * Shared by every GC-dependent test in this source set: keep the referent
 * out of the calling frame's locals (create it in a method of its own and
 * return only the [WeakReference]), or the interpreter may keep it alive.
 */
internal fun awaitCollected(
    ref: WeakReference<*>,
    timeout: Duration = 5.seconds,
): Boolean {
    val mark = TimeSource.Monotonic.markNow()
    while (ref.get() != null) {
        if (mark.elapsedNow() >= timeout) return false
        System.gc()
        repeat(GARBAGE_CHUNKS_PER_ROUND) { garbageSink.lazySet(ByteArray(GARBAGE_CHUNK_BYTES)) }
        garbageSink.lazySet(null)
        Thread.sleep(ROUND_PAUSE_MS)
    }
    return true
}
