package com.vynatix.holdfast.debug

import com.vynatix.holdfast.Disposable
import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreSnapshot
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.restore
import com.vynatix.holdfast.snapshot
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.time.Clock

/** Tunables shared by every store a [StoreRegistry] manages. */
@ExperimentalStoreApi
data class DebugOptions(
    /** Journal ring size per store. */
    val journalCapacity: Int = 128,
    /** Longest rendered value; longer `toString()`s are cut with an `…(+N)` tail. */
    val valueMaxChars: Int = 200,
    /** In-process checkpoints kept per store for `rewind`. */
    val checkpointCapacity: Int = 8,
)

/**
 * A named debug verb: an app-defined action the console can invoke by name
 * (`run <name> [args…]`). Verbs are how the console mutates anything `set`
 * cannot express — they run as ordinary transactions in app code, with the
 * app's own types, validation and middleware.
 */
@ExperimentalStoreApi
class DebugVerb(
    val name: String,
    val description: String,
    /** Receives the remaining argv; the return value's `toString()` is printed (`Unit`/`null` print `OK`). */
    val body: (List<String>) -> Any?,
)

/**
 * Process-wide (or test-local) registry of the stores the console can see.
 * Core has no store registry — the library never learns which stores exist —
 * so apps opt each store in with [register], typically right after creating it.
 *
 * Registering installs two middlewares on the store (a [JournalMiddleware] and
 * a [QuarantineMiddleware]) and attaches a per-state observer for the journal.
 * Core has no `removeMiddleware`, so [unregister] leaves them attached but
 * inert.
 *
 * Disposed stores are purged lazily: every lookup drops entries whose store
 * reports `isDisposed`.
 */
@ExperimentalStoreApi
class StoreRegistry(
    val options: DebugOptions = DebugOptions(),
) {
    /** One in-process snapshot taken by `checkpoint`; never serialized. */
    @ExperimentalStoreApi
    class Checkpoint internal constructor(
        val id: Int,
        val takenAt: Long,
        val snapshot: StoreSnapshot,
    )

    /** A registered store and the debug machinery attached to it. */
    @ExperimentalStoreApi
    class Entry internal constructor(
        val name: String,
        val store: Store<*>,
        val journal: JournalMiddleware<*>,
        internal val quarantine: QuarantineMiddleware<*>,
        val redact: Set<String>,
        internal val renderer: ValueRenderer,
        private val checkpointCapacity: Int,
        // `snapshot()`/`restore()` need the store's self-type, which only the
        // generic `register<S>` knows; captured here, erased for the console.
        private val takeSnapshot: () -> StoreSnapshot,
        private val restoreSnapshot: (StoreSnapshot) -> TransactionResult<Unit>,
    ) {
        private val lock = SynchronizedObject()
        private val checkpoints = ArrayDeque<Checkpoint>()
        private var nextCheckpointId = 1

        /** Whether every action on this store is currently being rejected. */
        val isQuarantined: Boolean get() = quarantine.enabled

        val className: String get() = store::class.simpleName ?: "Store"

        /** Snapshot the store under its transaction lock (never a torn, mid-commit cut) and keep it. */
        fun checkpoint(): Checkpoint {
            val snap = store.runUnderLock(takeSnapshot)
            return synchronized(lock) {
                val cp = Checkpoint(nextCheckpointId++, Clock.System.now().toEpochMilliseconds(), snap)
                checkpoints.addLast(cp)
                while (checkpoints.size > checkpointCapacity) checkpoints.removeFirst()
                cp
            }
        }

        fun checkpoints(): List<Checkpoint> = synchronized(lock) { checkpoints.toList() }

        fun checkpoint(id: Int): Checkpoint? = synchronized(lock) { checkpoints.firstOrNull { it.id == id } }

        /** Restore [checkpoint] atomically via `Store.restore` (raw values, no `Transformer.set`). */
        fun rewind(checkpoint: Checkpoint): TransactionResult<Unit> = restoreSnapshot(checkpoint.snapshot)

        internal fun setQuarantined(enabled: Boolean) {
            quarantine.enabled = enabled
        }

        internal fun close() {
            quarantine.enabled = false
            journal.detach()
            synchronized(lock) { checkpoints.clear() }
        }
    }

    private val lock = SynchronizedObject()
    private val entries = linkedMapOf<String, Entry>()
    private val verbs = linkedMapOf<String, DebugVerb>()

    /**
     * Make [store] visible to the console as [name] (defaults to the class's
     * simple name). Re-registering a name replaces the previous entry;
     * registering the same store instance under a second name moves it.
     *
     * [redact] names states whose values must never be printed — the console
     * shows `<redacted>` for them everywhere (dump, journal, diff). `State.value`
     * is the post-`transformer.get` view, so an encrypted state prints
     * plaintext unless it is listed here.
     *
     * Returns a [Disposable] that unregisters the store.
     */
    fun <S : Store<S>> register(
        store: S,
        name: String = store::class.simpleName ?: "Store",
        redact: Set<String> = emptySet(),
    ): Disposable {
        require(name.isNotBlank()) { "store name must not be blank" }
        require(!store.isDisposed) { "cannot register a disposed store" }
        val renderer = ValueRenderer(redact, options.valueMaxChars)
        val journal = JournalMiddleware<S>(renderer, options.journalCapacity)
        val quarantine = QuarantineMiddleware<S>(name)
        val entry =
            Entry(
                name = name,
                store = store,
                journal = journal,
                quarantine = quarantine,
                redact = redact,
                renderer = renderer,
                checkpointCapacity = options.checkpointCapacity,
                takeSnapshot = { store.snapshot() },
                restoreSnapshot = { store.restore(it) },
            )
        val displaced =
            synchronized(lock) {
                val old = entries.values.filter { it.name == name || it.store === store }
                old.forEach { entries.remove(it.name) }
                entries[name] = entry
                old
            }
        displaced.forEach { it.close() }
        // Quarantine innermost, journal outermost: the journal records rejected attempts.
        store.middlewares(quarantine, journal)
        journal.attach(store)
        return Disposable { unregister(name) }
    }

    /** Forget [name]; returns `false` if it was not registered. */
    fun unregister(name: String): Boolean {
        val removed = synchronized(lock) { entries.remove(name) } ?: return false
        removed.close()
        return true
    }

    /** Every live entry, in registration order. */
    fun entries(): List<Entry> {
        purgeDisposed()
        return synchronized(lock) { entries.values.toList() }
    }

    /** Exact name first, then a unique case-insensitive match; `null` otherwise. */
    fun find(name: String): Entry? {
        purgeDisposed()
        return synchronized(lock) {
            entries[name] ?: entries.values.filter { it.name.equals(name, ignoreCase = true) }.singleOrNull()
        }
    }

    /** Register (or replace) a verb the console can `run`. */
    fun verb(
        name: String,
        description: String = "",
        body: (List<String>) -> Any?,
    ): Disposable {
        require(name.isNotBlank() && name.none { it.isWhitespace() }) { "verb name must be one word" }
        synchronized(lock) { verbs[name] = DebugVerb(name, description, body) }
        return Disposable { synchronized(lock) { verbs.remove(name) } }
    }

    fun verbs(): List<DebugVerb> = synchronized(lock) { verbs.values.toList() }

    fun verb(name: String): DebugVerb? = synchronized(lock) { verbs[name] }

    /** Remove every store and verb. */
    fun clear() {
        val removed =
            synchronized(lock) {
                entries.values.toList().also {
                    entries.clear()
                    verbs.clear()
                }
            }
        removed.forEach { it.close() }
    }

    private fun purgeDisposed() {
        val dead =
            synchronized(lock) {
                val disposed = entries.values.filter { it.store.isDisposed }
                disposed.forEach { entries.remove(it.name) }
                disposed
            }
        dead.forEach { it.close() }
    }
}
