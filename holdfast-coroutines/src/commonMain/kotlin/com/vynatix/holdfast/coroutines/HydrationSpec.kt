package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store

// The `hydrator { }` DSL (issue #20, R8): `base { }`, then `refresh { } adopt { }`,
// and optionally `overlay(kv, key)` (R8/R3: the persisted UserAuthored overlay).
// Building it only records the blocks; `hydrator { }` checks the spec is
// complete, freezes it into a HydrationPlan and closes it, so a block set
// afterwards through a leaked spec fails instead of changing a live hydrator.

/**
 * What a hydrator does, declared inside [hydrator]:
 *
 * ```
 * val hydration = hydrator {
 *     base { restore(Seeds.history) }                        // in the seed transaction
 *     refresh { api.fetchHistory() } adopt { fetched ->        // `it` is the store
 *         remoteEntries mutate fetched                       // Remote states only
 *     }
 * }
 * ```
 *
 * - [base] (optional) runs in the seed transaction, which [Hydrator.hydrate]
 *   opens on a detached store: it may read and write any state of the store,
 *   restore a snapshot, reset.
 * - [refresh] (required) fetches, suspending, once the seed has committed;
 *   `adopt` (required, attached to it with the infix [HydrationRefresh.adopt])
 *   writes what it fetched into the store, as a savepoint of the adopt
 *   transaction, and may write only `StateTag.Remote` states.
 * - [overlay] (optional) persists the store's `StateTag.UserAuthored` states
 *   under one key of a key-value store, and applies them in the seed
 *   transaction, after `base { }`: base, then overlay, then refresh.
 *
 * Each block is set once.
 *
 * Experimental (issue #20, R8).
 */
@ExperimentalStoreApi
class HydrationSpec<V : Store<V>> internal constructor(
    private val storeName: String,
) {
    private var base: (V.() -> Unit)? = null
    private var refresh: HydrationRefresh<V, *>? = null
    private var overlay: OverlaySpec? = null
    private var closed = false

    /**
     * Seed the store: [block] runs inside the seed transaction (id
     * `HydrationSeed`) that [Hydrator.hydrate] opens on a detached store,
     * before the phase moves to [Hydration.Seeded] in that same transaction.
     * Anything an action body may do, it may do — typically
     * `restore(snapshot)` of bundled seed data. It runs again after every
     * [Hydrator.invalidate] (or `reset()`), never on a retry from
     * [Hydration.Failed]. A throwing [block], or a middleware rejecting the
     * seed, rolls the seed back: the phase stays [Hydration.Detached],
     * nothing is fetched, and [Hydrator.hydrate] throws.
     *
     * @throws IllegalStateException when set twice, or after [hydrator] has
     *   built its hydrator.
     */
    fun base(block: V.() -> Unit) {
        checkOpen("base { }")
        check(base == null) { "hydrator { } on $storeName sets base { } twice; give it one base { }" }
        base = block
    }

    /**
     * Fetch what the store adopts: [fetch] runs, suspending, in a coroutine
     * that [Hydrator.hydrate] launches on its scope once the seed (or the
     * decision to retry) has committed — never before, and never when that
     * transaction rolled back — given the store. Attach the adoption with
     * the infix `adopt`: `refresh { store -> api.fetch() } adopt { fetched -> … }`.
     *
     * A [fetch] that throws — a `CancellationException` from the launching
     * scope's cancellation included — moves the phase to
     * [Hydration.Failed] with what it threw.
     *
     * @throws IllegalStateException when set twice, or after [hydrator] has
     *   built its hydrator.
     */
    fun <F> refresh(fetch: suspend (V) -> F): HydrationRefresh<V, F> {
        checkOpen("refresh { }")
        check(refresh == null) {
            "hydrator { } on $storeName sets refresh { } twice; give it one refresh { } adopt { }"
        }
        return HydrationRefresh<V, F>(storeName, fetch).also { refresh = it }
    }

    /**
     * Persist the store's `StateTag.UserAuthored` states — what the user
     * authored: pins, drafts, read markers — under [key] of [kv], and put
     * them back when the store is seeded, over whatever `base { }` seeded
     * (issue #20, R8's ordering: base, then overlay, then refresh).
     *
     * **Inbound.** Until the overlay has loaded, a [Hydrator.hydrate] that
     * seeds the store first reads [key] from [kv] — suspending, before it
     * takes the store; a failing `get` makes it throw that, with nothing
     * changed — and the seed transaction (id `HydrationSeed`) restores the
     * blob's `UserAuthored` entries AFTER `base { }` has run, as a `restore`
     * savepoint (id `Restore`) of that transaction: base, the overlay and
     * the move to [Hydration.Seeded] commit together, and the overlay wins
     * over anything `base { }` wrote or restored into those states. Only the
     * entries of states and keyed state families the store declares
     * `UserAuthored` are restored — after a
     * [com.vynatix.holdfast.SchemaVersioned] store's `migrate` upcast an
     * older blob, so a renamed state is restored under its new name — and a
     * name the store no longer declares is ignored. The overlay replaces the
     * live entries of each `UserAuthored` keyed family the blob holds: an
     * entry `base { }` created that the blob does not hold is evicted in the
     * seed, so an entry the user evicted never comes back from base's seed
     * data (a family the blob does not list keeps its entries). Later seeds
     * of the same hydrator (after [Hydrator.invalidate] or the store's
     * `reset()`) put back the `UserAuthored` values the store held when they
     * began, without reading [kv] — the store is then what the overlay
     * persists — into what `base { }` changed (the states it wrote, the
     * entries it evicted; the entries it created are evicted), so a value a
     * bridge delivered from another thread meanwhile is kept. No key, no
     * blob: the seed is `base { }` alone.
     *
     * **Outbound.** Once that seed has committed, every commit that changes
     * a `UserAuthored` state (or evicts an entry of a `UserAuthored` family)
     * — an action, a frame, a `suspendAction`, a `reset()`, a bridge's
     * inbound value — is written: a writer on the store's `Store.scope`
     * (never the `hydrate()` call's scope) encodes
     * `snapshot(SnapshotScope.UserAuthored)` and puts the text — exactly the
     * store envelope `StoreSnapshot.encode()` writes — under [key]. It
     * coalesces: while it writes, later commits wait for one more write, of
     * the latest values, and a blob identical to the last one put is not put
     * again. It writes behind the commit, so a process that dies before it
     * has run loses that commit's change. The seed's own commit is not
     * written: it holds what the overlay (or `base { }`) put there. The
     * writer learns of a change from the commit's observer fanout, so a
     * commit whose fanout skips the overlay's observer — a `Transformer.get`
     * that throws for the new value (in a commit's fanout, or on a bridge's
     * inbound value, which then notifies no observer), an earlier observer
     * failing through an `uncaughtObserverHandler` that throws, which ends
     * the fanout — is written only with the next change of a `UserAuthored`
     * state. A state needs a codec to persist
     * (`state(codec = …, tags = setOf(UserAuthored))`): the blob lists one
     * without a codec as skipped, and a restore leaves it as it is. `Remote`
     * states are never written, and `Secret` ones cannot be `UserAuthored`.
     *
     * **Never overwritten when unreadable.** A blob the seed cannot apply —
     * text `StoreSnapshot.decode` rejects, a snapshot of a newer schema
     * version than the store's (or one its `migrate` throws on), or one the
     * restore rejects (a type mismatch, a codec that cannot decode an entry)
     * — is reported as an [OverlayException] through the store's
     * `uncaughtObserverHandler` (logged while none is set), naming the store
     * and the key and never a value; the seed commits `base { }` without it;
     * and nothing is written under [key] until [Hydrator.clearOverlay]
     * removes the blob, or a later seed (after [Hydrator.invalidate]) reads
     * one it can apply. What the user writes meanwhile stays in memory.
     *
     * **Size.** A blob longer than [sizeLimit] characters — by default 8192,
     * the most a `java.util.prefs` value holds — is reported as an
     * [OverlayException] and not written, so a size-limited store never
     * throws: [key] keeps the last blob written. A failing `put` is reported
     * the same way, naming the exception's class only (a store's message may
     * quote the value), as are a capture or codec that fails while writing
     * and a writer whose scope was cancelled; the next change writes again.
     * No report is made while the hydration gate holds the store, and a
     * handler that throws for any of these reports is ignored.
     *
     * The writer stops when the store is disposed. [key] is pinned: it never
     * derives from the store's class or state names, so renaming either
     * cannot orphan a blob; `Hydrator.overlayKey` returns it.
     *
     * Experimental (issue #20, R8 and R3).
     *
     * @throws IllegalStateException when set twice, or after [hydrator] has
     *   built its hydrator.
     * @throws IllegalArgumentException when [key] is empty, or [sizeLimit] is
     *   not positive.
     */
    fun overlay(
        kv: SuspendingKvStore,
        key: String,
        sizeLimit: Int = OVERLAY_SIZE_LIMIT,
    ) {
        checkOpen("overlay(kv, key)")
        check(overlay == null) { "hydrator { } on $storeName sets overlay(kv, key) twice; give it one overlay" }
        require(key.isNotEmpty()) { "hydrator { } on $storeName: overlay(kv, key) needs a non-empty key" }
        require(sizeLimit > 0) { "hydrator { } on $storeName: overlay's sizeLimit must be positive, not $sizeLimit" }
        overlay = OverlaySpec(kv, key, sizeLimit)
    }

    /**
     * The complete spec, frozen; closes this spec.
     *
     * @throws IllegalStateException when `refresh { } adopt { }` is missing.
     */
    internal fun build(): HydrationPlan<V, *> {
        closed = true
        val declared =
            checkNotNull(refresh) {
                "hydrator { } on $storeName needs refresh { … } adopt { … }: a hydrator seeds the store (base { }), " +
                    "then fetches (refresh) and adopts what it fetched (adopt)"
            }
        return declared.plan(base ?: {}, overlay)
    }

    private fun checkOpen(block: String) {
        check(!closed) { "$block set on the spec of a hydrator of $storeName after hydrator { } built it" }
    }
}

/**
 * The refresh a [HydrationSpec.refresh] declared, waiting for its adoption:
 * attach it with the infix [adopt].
 *
 * Experimental (issue #20, R8).
 */
@ExperimentalStoreApi
class HydrationRefresh<V : Store<V>, F> internal constructor(
    private val storeName: String,
    private val fetch: suspend (V) -> F,
) {
    private var adopt: (V.(F) -> Unit)? = null

    /**
     * Adopt what the refresh fetched: [block] runs with it once it arrives,
     * as a SAVEPOINT (id `Adopt`) of the adopt transaction (id
     * `HydrationAdopt`), which then moves the phase to [Hydration.Hydrated] —
     * unless the savepoint failed, and the adopt transaction moves the phase
     * to [Hydration.Failed] instead, with [block]'s writes rolled back.
     *
     * [block] may write only this store's `StateTag.Remote` states (and
     * evict entries of `Remote` keyed state families), so an adoption can
     * never clobber what the user wrote: a write to (or an eviction from)
     * anything else fails the adoption, naming the state, and rolls back all
     * of it. `removeState`/`clearStates` throw inside it — they drop states at
     * once, outside the rollback. A refresh that was invalidated (or reset)
     * while in flight is not adopted.
     *
     * @throws IllegalStateException when set twice.
     */
    infix fun adopt(block: V.(fetched: F) -> Unit) {
        check(adopt == null) { "hydrator { } on $storeName sets adopt { } twice; give its refresh { } one adopt { }" }
        adopt = block
    }

    /** The plan with [base] and [overlay], once [adopt] is set. */
    internal fun plan(
        base: V.() -> Unit,
        overlay: OverlaySpec?,
    ): HydrationPlan<V, F> {
        val adoption =
            checkNotNull(adopt) {
                "hydrator { } on $storeName declares refresh { } without adopt { }: attach one — " +
                    "refresh { … } adopt { fetched -> … } — to write what it fetched into the store"
            }
        return HydrationPlan(base, fetch, adoption, overlay)
    }
}

/** A complete, frozen [HydrationSpec]. */
internal class HydrationPlan<V : Store<V>, F>(
    val base: V.() -> Unit,
    val fetch: suspend (V) -> F,
    val adopt: V.(F) -> Unit,
    val overlay: OverlaySpec?,
)

/** What [HydrationSpec.overlay] declared: where the overlay persists, and the longest blob it writes. */
internal class OverlaySpec(
    val kv: SuspendingKvStore,
    val key: String,
    val sizeLimit: Int,
)

/** [HydrationSpec.overlay]'s default size limit: 8 KiB, the longest value `java.util.prefs` stores. */
private const val OVERLAY_SIZE_LIMIT = 8192
