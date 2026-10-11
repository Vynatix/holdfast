@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.RestorePolicy
import com.vynatix.holdfast.RestoreRejectedException
import com.vynatix.holdfast.SnapshotFormatException
import com.vynatix.holdfast.SnapshotMigrationException
import com.vynatix.holdfast.SnapshotScope
import com.vynatix.holdfast.State
import com.vynatix.holdfast.StateTag
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.StoreSnapshot
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.internalRestoreTagged
import com.vynatix.holdfast.snapshot
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

// The persisted UserAuthored overlay (issue #20, R8 and R3; plan PR 14): a
// hydrator's `overlay(kv, key)`. What the user authored — the store's
// StateTag.UserAuthored states and keyed families — persists under one pinned
// key, and is put back over `base { }` in the seed transaction: base, then the
// overlay, then the refresh.
//
// Where the overlay stands ([OverlayStatus]) decides both directions:
//
//  - UNLOADED (a new hydrator): nothing is written. The next seed reads the key
//    — suspending, before the gate, outside every lock — and applies the blob.
//  - LOADED (a seed applied the blob, or found none): the store IS what the
//    overlay persists. The writer (OverlayWriter.kt) writes after every commit
//    that changes a UserAuthored state, and a later seed (after invalidate()
//    or reset()) puts back the values the store held when it began, captured
//    in the seed transaction, without reading the key: a read could miss a
//    write still in flight, and put an older blob over the user's latest. It
//    puts back only what `base { }` changed (a state it wrote, an entry it
//    evicted or created), so a UserAuthored value another thread's bridge
//    delivered while the seed ran is kept.
//  - POISONED (a seed could not apply the blob): the seed committed `base { }`
//    without it, the failure was reported, and nothing is written — the blob
//    is never overwritten — until clearOverlay() removes it (LOADED), or a
//    later seed reads a blob it can apply.
//
// Every change of standing happens under the hydration gate — a seed's in its
// transaction, clearOverlay()'s in a hold of its own — and makes a new
// [Standing]. A seed puts back what was read under the standing it finds, or
// it is read again ([OverlayBinding.isCurrent]): so a clearOverlay() between
// the read and the seed never lets a removed blob be applied.
//
// The blob replaces each UserAuthored keyed family it lists
// (internalRestoreTagged): an entry `base { }` created that the blob — or the
// held capture — does not hold is evicted in the seed, so an entry the user
// evicted never comes back from base's seed data.
//
// The writer is never signalled under the gate (see OverlayWriter.kt): the
// seed's caller and clearOverlay() signal it once the gate has released.

/**
 * What a hydrator's persisted overlay ([HydrationSpec.overlay]) reports
 * through its store's `uncaughtObserverHandler` (logged while none is set):
 *
 * - a blob the seed could not apply — unreadable, of a newer schema version,
 *   or rejected by the restore — which is then kept as it is: nothing is
 *   written under [key] until `Hydrator.clearOverlay()` removes it, or a
 *   later seed applies a blob;
 * - a blob longer than the overlay's size limit, which is not written;
 * - a write whose capture, encoding or put failed, or that could not run
 *   because the store's scope was cancelled.
 *
 * None is reported while the hydration gate holds the store, where a
 * handler that opens an action on it would wait forever.
 *
 * The message names the store and [key], never a state's value. [cause] is
 * set only to one of Holdfast's own exceptions that never quote a value — a
 * `SnapshotFormatException`, a `SnapshotMigrationException`, a
 * `RestoreRejectedException`, or the `IllegalStateException` a codec failing
 * in `encode()` is reported with — and otherwise the message names the
 * exception's class only: a key-value store's message may quote the text it
 * failed to write.
 *
 * Experimental (issue #20, R8 and R3).
 */
@ExperimentalStoreApi
class OverlayException internal constructor(
    /** The key the overlay persists under. */
    val key: String,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/** Where an overlay stands: see the top of this file. */
internal enum class OverlayStatus { Unloaded, Loaded, Poisoned }

/**
 * An overlay's standing: its [status], as one instance per change, compared by
 * identity — what was read under one is current only while it stands.
 */
internal class Standing(
    val status: OverlayStatus,
)

/**
 * Where a seed left its overlay ([OverlayBinding.settle]): the report of a
 * blob it could not apply ([failure]), and whether the overlay stands LOADED
 * ([loaded]), so the writer is signalled — both once the gate has released
 * the store.
 */
internal class OverlaySettled(
    val failure: OverlayException?,
    val loaded: Boolean,
) {
    companion object {
        /** A seed that did not apply: nothing to report, nothing to signal. */
        val NONE = OverlaySettled(failure = null, loaded = false)
    }
}

/** What a seed puts back, read before the gate under [standing]. */
internal sealed class OverlayInbound(
    val standing: Standing,
) {
    /** The overlay has loaded: put back the store's own UserAuthored values, captured in the seed transaction. */
    class Held(
        standing: Standing,
    ) : OverlayInbound(standing)

    /** What the key held: [blob] is `null` for no key, else the snapshot decoded — or why it could not be. */
    class Stored(
        standing: Standing,
        val blob: Result<StoreSnapshot>?,
    ) : OverlayInbound(standing)
}

/**
 * One hydrator's overlay: its standing, the seed's part in it, and
 * [Hydrator.clearOverlay]. See the top of this file.
 */
internal class OverlayBinding<V : Store<V>>(
    private val store: V,
    private val spec: OverlaySpec,
    private val gate: HydrationGate,
    private val storeName: String,
) {
    /** The key the overlay persists under. */
    val key: String get() = spec.key

    private val standing = atomic(Standing(OverlayStatus.Unloaded))

    val writer = OverlayWriter(store, spec, storeName) { standing.value.status == OverlayStatus.Loaded }

    /** Where the overlay stands now. */
    val status: OverlayStatus get() = standing.value.status

    /**
     * What a seed would put back now: the store's own values once the overlay
     * has loaded; else the blob under [key], read now (suspending, outside
     * every lock of the store) and decoded.
     *
     * @throws Throwable what the key-value store's `get` threw.
     */
    suspend fun read(): OverlayInbound {
        val at = standing.value
        if (at.status == OverlayStatus.Loaded) return OverlayInbound.Held(at)
        val text = writer.read()
        return OverlayInbound.Stored(at, text?.let { runCatching { StoreSnapshot.decode(it) } })
    }

    /** Whether [inbound] was read under the standing that holds now: a seed may put it back. Under the gate. */
    fun isCurrent(inbound: OverlayInbound?): Boolean = inbound != null && inbound.standing === standing.value

    /** [previous] while it is current, else what [read] reads now. */
    suspend fun current(previous: OverlayInbound?): OverlayInbound = previous?.takeIf { isCurrent(it) } ?: read()

    /**
     * In the seed transaction, BEFORE `base { }`: begin loading [inbound] —
     * for [OverlayInbound.Held], capture the store's UserAuthored values
     * now, before `base { }` writes any — and watch the store's
     * UserAuthored states from now on.
     *
     * @throws IllegalStateException when one of the store's UserAuthored
     *   states is also Secret (see [OverlayWatch.watchAll]).
     */
    fun open(inbound: OverlayInbound): OverlayLoad<V> = OverlayLoad(store, inbound, writer)

    /**
     * Under the gate, after the seed transaction: when it [applied], the
     * overlay stands where [load] left it — LOADED, or POISONED with the
     * failure returned. The caller reports the failure, or signals the writer
     * ([OverlayWriter.signalIfBehind]) when the overlay stands LOADED, once
     * the gate has released the store. A seed rolled back changes nothing.
     */
    fun settle(
        load: OverlayLoad<V>,
        applied: Boolean,
    ): OverlaySettled {
        load.watch.endSeed()
        if (!applied) return OverlaySettled.NONE
        val failure = load.failure?.let { poisonedReport(it) }
        val was = standing.value.status
        val now = if (failure != null) OverlayStatus.Poisoned else OverlayStatus.Loaded
        standing.value = Standing(now)
        val loaded = now == OverlayStatus.Loaded
        if (loaded) writer.seeded(load.baseline, wasLoaded = was == OverlayStatus.Loaded)
        return OverlaySettled(failure, loaded)
    }

    /**
     * [Hydrator.clearOverlay]: remove the blob — dropping the writes signalled
     * so far — then, under the gate, stand anew: a POISONED overlay loads, as
     * there is no blob left to protect, and — once the gate has released the
     * store — writes a change that landed since the removal. Once the blob is
     * removed, the rest runs even if the caller is cancelled, so the standing
     * always follows the removal.
     */
    suspend fun clear() {
        refuseInsideEntry(store, "clearOverlay()", ::clearInsideEntryMessage)
        writer.remove()
        withContext(NonCancellable) {
            val loadedNow =
                gate.hold {
                    // The removal covers every change counted until it: while
                    // the overlay stood POISONED nothing was written since.
                    val poisoned = standing.value.status == OverlayStatus.Poisoned
                    standing.value = Standing(if (poisoned) OverlayStatus.Loaded else standing.value.status)
                    poisoned
                }
            if (loadedNow) writer.signalIfBehind()
        }
    }

    /** The store was disposed: stop the writer. */
    fun onDisposed() {
        writer.cancel()
    }

    /** The report for a blob the seed could not apply ([OverlayLoad.failure]): kept, and not written over. */
    private fun poisonedReport(failure: Throwable): OverlayException {
        val valueFree =
            failure is SnapshotFormatException ||
                failure is SnapshotMigrationException ||
                failure is RestoreRejectedException
        val why =
            when (failure) {
                is SnapshotFormatException -> "it is not an encoded store snapshot this version reads"
                is SnapshotMigrationException -> "its schema version cannot be brought to $storeName's"
                is RestoreRejectedException -> "the restore rejected it"
                else -> "restoring it threw ${failure::class.simpleName ?: "an exception"}"
            }
        return OverlayException(
            spec.key,
            "The persisted overlay of $storeName under key '${spec.key}' could not be applied: $why. " +
                "$storeName was seeded without it, and the blob is kept as it is: nothing is written under " +
                "'${spec.key}' until hydrator.clearOverlay() removes it, or a later seed (after invalidate()) " +
                "applies a blob. What the user writes until then stays in memory only.",
            failure.takeIf { valueFree },
        )
    }
}

/**
 * One seed's load of an overlay: opened in the seed transaction before
 * `base { }` ([OverlayBinding.open]), [apply]ed after it.
 */
internal class OverlayLoad<V : Store<V>>(
    private val store: V,
    private val inbound: OverlayInbound,
    writer: OverlayWriter<V>,
) {
    /** The store's UserAuthored values as they stood before `base { }`, for [OverlayInbound.Held]. */
    private val held: StoreSnapshot? =
        if (inbound is OverlayInbound.Held) store.snapshot(SnapshotScope.UserAuthored) else null

    /**
     * What watches the store's UserAuthored states for the writer — from now
     * on, before `base { }`, so a bridge's inbound value from another thread
     * while `base { }` runs is counted — told that this seed began.
     *
     * @throws IllegalStateException when one of the store's UserAuthored
     *   states is also Secret (see [OverlayWatch.watchAll]): the seed is
     *   refused.
     */
    val watch: OverlayWatch =
        writer.watch.also { watch ->
            watch.watchAll()
            watch.beginSeed(checkNotNull(store.activeTransaction) { "a seed loads the overlay" })
        }

    /** The writer's count of changes when this seed began: what a newly loaded overlay has written. */
    val baseline: Long = writer.changeCount

    /** Why the blob could not be applied, once [apply] has run; `null` when it was, or there was none. */
    var failure: Throwable? = null
        private set

    /**
     * In the seed transaction, AFTER `base { }`: put back the UserAuthored
     * entries — the held values, or the blob's — over whatever `base { }`
     * wrote, as a `restore` savepoint that replaces each UserAuthored keyed
     * family the snapshot lists (an entry `base { }` created that it does not
     * hold is evicted). The held values go back only into what `base { }`
     * changed — the states it wrote and the entries it evicted — so a value
     * a bridge delivered from another thread meanwhile is kept. A blob that
     * cannot be applied is left, and [failure] says why; nothing it holds is
     * restored, and no entry is evicted.
     */
    fun apply() {
        val stored = (inbound as? OverlayInbound.Stored)?.blob
        stored?.exceptionOrNull()?.let { unreadable -> failure = unreadable }
        val snapshot = held ?: stored?.getOrNull()
        if (snapshot != null) {
            val targets = if (held != null) changedByBase() else null
            val restored =
                store.internalRestoreTagged(snapshot, StateTag.UserAuthored, RestorePolicy.IgnoreUnknown, targets)
            failure = (restored as? TransactionResult.Error)?.exception
        }
    }

    /** The states `base { }` wrote and the keyed entries it evicted, in the seed transaction open on this thread. */
    private fun changedByBase(): (State<*>) -> Boolean {
        val seed = checkNotNull(store.activeTransaction) { "a seed loads the overlay" }
        val changed = seed.modifiedStates + seed.stagedEvictions
        return { it in changed }
    }
}

/** Why `clearOverlay()` may not run inside an entry: [refuseInsideEntry]. */
private fun clearInsideEntryMessage(
    store: Store<*>,
    call: String,
): String {
    val name = store::class.simpleName ?: "Store"
    return "Cannot run $call on $name's hydrator here: it takes $name under the hydration gate, so it may not run " +
        "inside an action, an atomic frame, a suspendAction or a suspendAtomic — body or commit (an observer, a " +
        "bridge publish, an event collector) — of any store. Inside one of $name's it would wait forever for the " +
        "transaction that waits for it. Fix: call it once that transaction has returned."
}
