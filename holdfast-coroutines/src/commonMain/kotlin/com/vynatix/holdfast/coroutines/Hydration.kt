package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.ExperimentalStoreApi

// The hydration lifecycle's phases (issue #20, R8; plan decision D19): the
// public four a Hydrator's state holds, and the internal phase the hydrator
// decides on, which also records WHICH refresh is in flight.

/**
 * Where a store's hydration stands: what [Hydrator.state] holds. A hydrator
 * starts [Detached]; [Hydrator.hydrate] seeds the store and moves it to
 * [Seeded] while its refresh is in flight, then to [Hydrated] once the
 * refresh is adopted, or to [Failed] when the refresh or its adoption fails.
 *
 * ```
 * Detached ──hydrate()──▶ Seeded ──adopted──▶ Hydrated
 *    ▲                     │  ▲
 *    │                  failed│hydrate() (refresh only)
 *    │                     ▼  │
 *    └──invalidate()/reset()── Failed
 * ```
 *
 * [Hydrator.invalidate] (or `stageInvalidate()` inside an action) and the
 * store's `reset()` bring it back to [Detached] from any phase.
 *
 * Experimental (issue #20, R8).
 */
@ExperimentalStoreApi
sealed interface Hydration {
    /**
     * Not hydrated: the phase a hydrator starts in, and returns to through
     * [Hydrator.invalidate] or the store's `reset()`. [Hydrator.hydrate] runs
     * `base { }` from here.
     */
    data object Detached : Hydration

    /**
     * `base { }` has committed — or, after a failure, the store keeps what it
     * held — and a refresh is in flight. [Hydrator.hydrate] does nothing
     * here: the refresh already running is the one that counts.
     */
    data object Seeded : Hydration

    /** The last refresh was adopted. [Hydrator.hydrate] does nothing here. */
    data object Hydrated : Hydration

    /**
     * The last refresh failed with [cause]: `refresh { }` threw (a
     * `CancellationException` too, when the scope running it was cancelled),
     * or `adopt { }` threw or wrote a state that is not `StateTag.Remote`
     * (rolled back whole), or a middleware rejected the adoption.
     * [Hydrator.hydrate] retries the refresh only — `base { }` does not run
     * again — moving back to [Seeded] in the same transaction that decides
     * to.
     */
    data class Failed(
        val cause: Throwable,
    ) : Hydration
}

/**
 * The phase a hydrator decides on, held in its sealed phase state: the public
 * [Hydration], plus which refresh is in flight while [Seeded], by its
 * [Seeded.refresh] number — so a refresh that finishes after an invalidate
 * (or a reset) is told apart from the one a later hydrate launched, and
 * discarded.
 */
@OptIn(ExperimentalStoreApi::class)
internal sealed interface HydrationPhase {
    /** What [Hydrator.state] shows for this phase. */
    val public: Hydration

    data object Detached : HydrationPhase {
        override val public: Hydration get() = Hydration.Detached
    }

    /** A refresh is in flight: the one numbered [refresh]. */
    data class Seeded(
        val refresh: Long,
    ) : HydrationPhase {
        override val public: Hydration get() = Hydration.Seeded
    }

    data object Hydrated : HydrationPhase {
        override val public: Hydration get() = Hydration.Hydrated
    }

    data class Failed(
        val cause: Throwable,
    ) : HydrationPhase {
        override val public: Hydration get() = Hydration.Failed(cause)
    }
}
