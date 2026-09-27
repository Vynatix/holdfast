@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.SnapshotScope
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.snapshot
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// The overlay's outbound half (issue #20, R8/R3; plan PR 14): once the overlay
// has LOADED (OverlayBinding.kt), every commit that changes a UserAuthored
// state is written — `snapshot(SnapshotScope.UserAuthored).encode()`, put
// under the overlay's key.
//
// The writer is CONFLATED: a change moves it from idle to running — launching
// one coroutine on the store's `Store.scope`, never the scope a `hydrate()`
// was called with — and while it runs, further changes only mark it dirty, so
// it writes once more, the latest values, then stops: commits that arrive
// while a write runs cost one more write, of the latest values. Between bursts
// no coroutine is left running, so a store's scope has nothing of the overlay
// to wait for.
//
// The writer is never signalled while the hydration gate holds the store: on
// a dispatcher that runs a launch at once (`Dispatchers.Unconfined`,
// `Dispatchers.Main.immediate` on the main thread) it would write — and report
// — inline, under the gate, where a handler that opens an action on the store
// waits forever for the gate's serializer. Under the gate the seed only moves
// the counts ([OverlayWriter.seeded]), and clearOverlay() only the standing;
// each signals ([OverlayWriter.signalIfBehind]) once the gate has released
// the store.
//
// Every key-value call of the overlay — the seed's read, a write,
// clearOverlay()'s removal — runs under one Mutex, so a removal never lands
// between a write's encode and its put, and the writes signalled before a
// removal are dropped by it ([OverlayWriter.remove]).
//
// Nothing the writer meets may crash the scope or pass silently: a blob longer
// than the size limit, a failing capture or codec (an initializer, or a
// `StateCodec.encode`, that throws), a failing `put`, a scope cancelled before
// the latest values were written — each goes to the store's
// `uncaughtObserverHandler` as an OverlayException (logged while none is set;
// a handler that throws there is ignored). A failed write is retried by the
// next change; an oversize blob is not written, and the key keeps the last
// blob written.
//
// The writer learns of a change from the commit's observer fanout
// (OverlayWatch.kt), so a commit whose fanout skips the overlay's observer — a
// `Transformer.get` that throws for the new value, an earlier observer failing
// through a rethrowing `uncaughtObserverHandler`, which ends the fanout — is
// written only with the next change of a UserAuthored state.

/** The writer is not running. */
private const val IDLE = 0

/** The writer is running, and has seen no change since it began its write. */
private const val RUNNING = 1

/** The writer is running, and a change arrived since it began its write: it writes once more. */
private const val DIRTY = 2

/**
 * The outbound half of one hydrator's overlay: see the top of this file.
 * [loaded] tells whether the overlay stands LOADED, the only standing it
 * writes in.
 */
internal class OverlayWriter<V : Store<V>>(
    private val store: V,
    private val spec: OverlaySpec,
    private val storeName: String,
    private val loaded: () -> Boolean,
) {
    /** Serializes this overlay's key-value calls: the seed's read, a write, a removal. */
    private val kvLock = Mutex()

    /** Changes of UserAuthored states seen since the hydrator was created (not a seed's own). */
    private val changes = atomic(0L)

    /** The count of [changes] the last write — or removal, or load — covers. */
    private val written = atomic(0L)

    /** The text last put under the key, not put again; `null` after a removal. Guarded by [kvLock]. */
    private var lastWritten: String? = null

    /** [IDLE], [RUNNING] or [DIRTY]. */
    private val running = MutableStateFlow(IDLE)

    /** The coroutine writing now, if any: cancelled when the store is disposed. */
    private val job = atomic<Job?>(null)

    /** What tells this writer of the changes: installed by the seeds. */
    val watch = OverlayWatch(store, storeName, ::changed)

    /** How many changes of UserAuthored states it has been told of: a seed's baseline. */
    val changeCount: Long get() = changes.value

    /** The blob under the key, read under the key-value lock. */
    suspend fun read(): String? = kvLock.withLock { spec.kv.get(spec.key) }

    /**
     * Under the gate: a seed that began when [changeCount] was [baseline]
     * has committed, and the overlay stands LOADED. Signals nothing: the
     * writes it calls for are made by [signalIfBehind], once the gate has
     * released the store.
     *
     * - The overlay has just loaded ([wasLoaded] false): what came before is
     *   covered by the load, and a change since — from a holder the gate
     *   does not keep out, such as another thread's bridge — is left to
     *   write.
     * - It stood LOADED already: a change counted while the seed ran was
     *   written as it came, and the seed's commit may have put other values
     *   over it since, so the latest values are written once more (not put
     *   again when the blob is the same).
     */
    fun seeded(
        baseline: Long,
        wasLoaded: Boolean,
    ) {
        when {
            !wasLoaded -> written.value = baseline
            changes.value != baseline -> changes.incrementAndGet()
        }
    }

    /**
     * Once the gate has released the store: write the changes no write, load
     * or removal covers yet, if the overlay stands LOADED.
     */
    fun signalIfBehind() {
        if (loaded() && changes.value != written.value) signal()
    }

    /**
     * [Hydrator.clearOverlay]'s removal: remove the key, and drop every write
     * signalled until now; the next change writes again.
     *
     * @throws Throwable what the key-value store's `remove` threw: nothing
     *   changed then.
     */
    suspend fun remove() {
        kvLock.withLock {
            spec.kv.remove(spec.key)
            lastWritten = null
            written.value = changes.value
        }
    }

    /** The store was disposed: stop writing. */
    fun cancel() {
        job.value?.cancel()
    }

    /** Wait until nothing is left to write (tests). */
    suspend fun awaitIdle() {
        running.first { it == IDLE }
    }

    /** A UserAuthored state changed ([OverlayWatch]): count it, and write it once the overlay has loaded. */
    private fun changed() {
        changes.incrementAndGet()
        if (loaded()) signal()
    }

    /** Start the writer, or mark it dirty while it runs. */
    private fun signal() {
        val wasIdle = !store.isDisposed && running.getAndUpdate { if (it == IDLE) RUNNING else DIRTY } == IDLE
        if (wasIdle) launchWriter()
    }

    /** Launch the writer on the store's scope, as it resolves now. */
    private fun launchWriter() {
        runCatching { store.scope.launch { drain() } }
            .onFailure { failure ->
                running.value = IDLE
                store.reportOverlay(stoppedReport(spec.key, storeName, failure))
            }.onSuccess { writing ->
                job.value = writing
                writing.invokeOnCompletion { cause ->
                    // Ended before it wrote the latest values: its scope was
                    // cancelled (or the store disposed, which is no failure).
                    // The next change launches it again.
                    if (cause != null && running.value != IDLE) {
                        running.value = IDLE
                        if (!store.isDisposed) store.reportOverlay(stoppedReport(spec.key, storeName, cause))
                    }
                }
            }
    }

    /** Write the latest values until no change arrived during the last write. */
    private suspend fun drain() {
        do {
            running.value = RUNNING
            writeLatest()
        } while (!running.compareAndSet(RUNNING, IDLE))
    }

    /**
     * Encode the store's UserAuthored values now and put them under the key —
     * unless nothing changed since the last write, the overlay no longer
     * stands LOADED, the store is disposed, or the blob is what was last put.
     */
    private suspend fun writeLatest() {
        kvLock.withLock {
            val target = changes.value
            if (target == written.value || !loaded() || store.isDisposed) return@withLock
            val blob = store.encodeOverlay(spec, storeName)
            if (blob != null && blob != lastWritten) {
                val put = runCatching { spec.kv.put(spec.key, blob) }
                val failure = put.exceptionOrNull()
                if (failure is CancellationException) throw failure
                if (failure != null) {
                    store.reportOverlay(putFailedReport(spec.key, storeName, failure))
                    return@withLock
                }
                lastWritten = blob
            }
            written.value = target
        }
    }
}

/**
 * This store's UserAuthored values as the overlay's blob, or `null` — reported
 * — when the capture or a codec fails, or the blob is longer than the size
 * limit.
 */
private fun <V : Store<V>> V.encodeOverlay(
    spec: OverlaySpec,
    storeName: String,
): String? {
    // Outer: the capture, whose failure (an initializer's) may quote anything;
    // inner: encode(), whose IllegalStateException names the state and the
    // codec's exception class, never a value.
    val encoded = runCatching { snapshot(SnapshotScope.UserAuthored) }.map { runCatching { it.encode() } }
    val blob = encoded.getOrNull()?.getOrNull()
    when {
        blob == null -> {
            val failure = encoded.exceptionOrNull() ?: checkNotNull(encoded.getOrThrow().exceptionOrNull())
            val cause = failure.takeIf { encoded.isSuccess && it is IllegalStateException }
            if (!isDisposed) reportOverlay(encodeFailedReport(spec.key, storeName, failure, cause))
        }
        blob.length > spec.sizeLimit -> reportOverlay(tooLargeReport(spec.key, storeName, blob.length, spec.sizeLimit))
        else -> return blob
    }
    return null
}

/** Report [failure] through this store's `uncaughtObserverHandler`; a handler that throws is ignored. */
internal fun Store<*>.reportOverlay(failure: OverlayException) {
    runCatching { internalReportUncaughtFailure(failure) }
}

private fun tooLargeReport(
    key: String,
    storeName: String,
    length: Int,
    limit: Int,
) = OverlayException(
    key,
    "The persisted overlay of $storeName under key '$key' was not written: the blob is $length characters long, " +
        "over the overlay's size limit of $limit. The key keeps the last blob written. Fix: persist less as " +
        "UserAuthored (evict keyed entries, trim what a state holds), or raise the limit with " +
        "overlay(kv, key, sizeLimit = …) when the key-value store takes longer values (java.util.prefs takes 8192).",
)

private fun putFailedReport(
    key: String,
    storeName: String,
    failure: Throwable,
) = OverlayException(
    key,
    "The persisted overlay of $storeName under key '$key' was not written: the key-value store's put threw " +
        "${failure::class.simpleName ?: "an exception"} (its message is not shown: it may quote the blob). The " +
        "next change of a UserAuthored state writes again.",
)

private fun encodeFailedReport(
    key: String,
    storeName: String,
    failure: Throwable,
    cause: Throwable?,
) = OverlayException(
    key,
    "The persisted overlay of $storeName under key '$key' was not written: capturing or encoding the store's " +
        "UserAuthored states failed (${failure::class.simpleName ?: "an exception"}). The key keeps the last blob " +
        "written, and the next change of a UserAuthored state tries again.",
    cause,
)

private fun stoppedReport(
    key: String,
    storeName: String,
    cause: Throwable,
) = OverlayException(
    key,
    "The persisted overlay of $storeName under key '$key' could not write the latest UserAuthored values: its " +
        "writer, on $storeName's Store.scope, ended with ${cause::class.simpleName ?: "an exception"} — the scope " +
        "was cancelled? The next change of a UserAuthored state tries again on the store's scope.",
)
