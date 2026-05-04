package com.vynatix.holdfast.testing.internal

import com.vynatix.holdfast.testing.HoldfastTestScope
import com.vynatix.holdfast.testing.concurrency.Barrier

/**
 * Backing list for barriers created via [HoldfastTestScope.barrier]. Held inside
 * the scope so its lifetime mirrors the test body; [cancelAll] is called from
 * `tearDown` to resume any leaked waiters with a cancellation.
 */
internal class BarrierRegistry {
    private val barriers: MutableList<Barrier> = mutableListOf()

    fun add(barrier: Barrier) {
        barriers.add(barrier)
    }

    fun cancelAll() {
        for (barrier in barriers) barrier.cancelIfPending()
        barriers.clear()
    }
}

/**
 * Bridge from public-API extension functions in `concurrency/` back to the
 * scope's `internal` registry. Co-located here so the seam is obvious and so
 * [Barrier]'s constructor can stay `internal` without leaking.
 */
internal fun HoldfastTestScope.registerBarrier(barrier: Barrier) {
    barrierRegistry().add(barrier)
}
