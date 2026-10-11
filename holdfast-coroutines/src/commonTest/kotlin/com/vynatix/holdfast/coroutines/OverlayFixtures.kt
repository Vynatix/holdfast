@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.SnapshotScope
import com.vynatix.holdfast.StateCodec
import com.vynatix.holdfast.StateTag
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreSnapshot
import com.vynatix.holdfast.bridge.StringCodec
import com.vynatix.holdfast.keyedState
import com.vynatix.holdfast.snapshot
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

// Fixtures of the persisted-overlay tests (issue #20, R8/R3; plan PR 14).

/** The key every overlay test persists under. */
internal const val OVERLAY_KEY = "reader.overlay"

/** A set of strings, one per line; text starting with `!` cannot be decoded (a poisoned entry). */
internal object PinsCodec : StateCodec<Set<String>> {
    override fun encode(value: Set<String>): String = value.sorted().joinToString("\n")

    override fun decode(string: String): Set<String> {
        require(!string.startsWith("!")) { "unreadable pins" }
        return if (string.isEmpty()) emptySet() else string.split("\n").toSet()
    }
}

/** A list of strings, one per line. */
internal object ItemsCodec : StateCodec<List<String>> {
    override fun encode(value: List<String>): String = value.joinToString("\n")

    override fun decode(string: String): List<String> = if (string.isEmpty()) emptyList() else string.split("\n")
}

/**
 * A [SuspendingKvStore] that counts its calls and can be told to fail them:
 * [failGet] / [failPut] / [failRemove] are thrown by the next calls while
 * set. [afterRemove] runs inside a `remove`, once the key is gone.
 */
internal class RecordingKv(
    initial: Map<String, String> = emptyMap(),
) : SuspendingKvStore {
    private val lock = SynchronizedObject()
    private val map = initial.toMutableMap()
    private val getCount = atomic(0)
    private val putCount = atomic(0)
    private val removeCount = atomic(0)
    var failGet: Throwable? = null
    var failPut: Throwable? = null
    var failRemove: Throwable? = null
    var afterRemove: (() -> Unit)? = null

    val gets: Int get() = getCount.value
    val puts: Int get() = putCount.value
    val removes: Int get() = removeCount.value

    /** What [key] holds now. */
    fun value(key: String = OVERLAY_KEY): String? = synchronized(lock) { map[key] }

    override suspend fun get(key: String): String? {
        getCount.incrementAndGet()
        failGet?.let { throw it }
        return synchronized(lock) { map[key] }
    }

    override suspend fun put(
        key: String,
        value: String,
    ) {
        putCount.incrementAndGet()
        failPut?.let { throw it }
        synchronized(lock) { map[key] = value }
    }

    override suspend fun remove(key: String) {
        removeCount.incrementAndGet()
        failRemove?.let { throw it }
        synchronized(lock) { map.remove(key) }
        afterRemove?.invoke()
    }

    override suspend fun snapshot(): Map<String, String> = synchronized(lock) { map.toMap() }
}

/**
 * A reader's feed with a persisted overlay: pins and a note the user
 * authored, items sync adopts (Remote), and a session token (Secret).
 * [seed] is its `base { }`.
 */
internal class Reader(
    kv: SuspendingKvStore,
    remote: suspend () -> List<String> = { listOf("fresh") },
    sizeLimit: Int = 8192,
    seed: Reader.() -> Unit = { items mutate listOf("seed") },
) : Store<Reader>() {
    val pinned by state(codec = PinsCodec, tags = setOf(StateTag.UserAuthored)) { emptySet<String>() }
    val note by state(codec = StringCodec, tags = setOf(StateTag.UserAuthored)) { "" }
    val items by state(codec = ItemsCodec, tags = setOf(StateTag.Remote)) { emptyList<String>() }
    val token by state(codec = StringCodec, tags = setOf(StateTag.Secret)) { "" }
    val drafts by keyedState<String, String>(
        codec = StringCodec,
        keyCodec = StringCodec,
        tags = setOf(StateTag.UserAuthored),
    ) { "" }

    /** How many times `base { }` has run. */
    var baseRuns = 0

    val hydration =
        hydrator {
            base {
                baseRuns++
                seed()
            }
            overlay(kv, OVERLAY_KEY, sizeLimit)
            refresh { remote() } adopt { fetched -> items mutate fetched }
        }
}

/** The blob a [Reader] holding what [write] wrote persists: its UserAuthored snapshot, encoded. */
internal fun readerBlob(write: Reader.() -> Unit): String {
    val donor = Reader(RecordingKv())
    donor.action { write() }
    return donor.snapshot(SnapshotScope.UserAuthored).encode().also { donor.dispose() }
}

/** [kv]'s blob, decoded. */
internal fun persisted(kv: RecordingKv): StoreSnapshot = StoreSnapshot.decode(checkNotNull(kv.value()) { "no blob" })

/** Wait — at most 10 s — until the overlay's writer has nothing left to write. */
internal suspend fun Hydrator<*>.overlayWritten() {
    withTimeout(10.seconds) { checkNotNull(engine.overlay).writer.awaitIdle() }
}

/** Where this hydrator's overlay stands. */
internal val Hydrator<*>.overlayStatus: OverlayStatus get() = checkNotNull(engine.overlay).status
