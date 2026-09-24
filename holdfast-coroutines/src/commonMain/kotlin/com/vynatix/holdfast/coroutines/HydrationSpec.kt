package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Store

// The `hydrator { }` DSL (issue #20, R8): `base { }`, then `refresh { } adopt { }`.
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
        return declared.plan(base ?: {})
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

    /** The plan with [base], once [adopt] is set. */
    internal fun plan(base: V.() -> Unit): HydrationPlan<V, F> {
        val adoption =
            checkNotNull(adopt) {
                "hydrator { } on $storeName declares refresh { } without adopt { }: attach one — " +
                    "refresh { … } adopt { fetched -> … } — to write what it fetched into the store"
            }
        return HydrationPlan(base, fetch, adoption)
    }
}

/** A complete, frozen [HydrationSpec]. */
internal class HydrationPlan<V : Store<V>, F>(
    val base: V.() -> Unit,
    val fetch: suspend (V) -> F,
    val adopt: V.(F) -> Unit,
)
