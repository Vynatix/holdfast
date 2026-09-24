package com.vynatix.holdfast

import com.vynatix.holdfast.platform.currentComputeReadsLocal
import com.vynatix.holdfast.platform.setComputeReadsLocal

// What a derived state's compute reads (issue #20, R9; plan decision D17).
//
// A `derivedState`/`merged` compute reads its sources from ONE committed cut,
// taken just before it runs (readConsistent, ConsistentRead.kt): never one
// source after another thread's frame has applied and another before, since a
// frame applies every participant inside one write bracket. Its recompute
// reads nothing uncommitted at all (the ComputingFrame of NoWriteRegion hides
// every pending write), so it never commits a torn pair or a value built from
// writes that may roll back. The initial compute reads the way any read on its
// thread does — an action's pending writes included — but its sources come
// from the cut too wherever the thread has no pending write of its own, and a
// read of any uncommitted value is noted, so the derived state recomputes from
// committed values once the entry holding that value settles.
//
// MutableState.value consults the cut only while one is taken over the state
// ([MutableState.cutReaders]), so an ordinary read pays one volatile read.

/**
 * The committed cut of the sources of the derived-state compute running on
 * this thread, and — for an initial compute — whether it read a value no
 * commit has made yet.
 */
internal class ComputeReads private constructor(
    private val sources: List<MutableState<*>>,
    private val values: List<Any>,
    private val notesUncommitted: Boolean,
) {
    /** Whether the compute read a value no commit has made: a pending write, or a reset's value. */
    var readUncommitted = false
        private set

    private fun cutValueOf(state: MutableState<*>): Any? {
        val at = sources.indexOfFirst { it === state }
        return if (at < 0) null else values[at]
    }

    companion object {
        /**
         * Run a recompute's [compute], reading [sources] from one committed
         * cut. The caller runs it inside the recompute's ComputingFrame, which
         * hides every pending write, so the compute reads committed values
         * only.
         */
        fun <R> recompute(
            sources: List<MutableState<*>>,
            compute: () -> R,
        ): R = run(ComputeReads(sources, readConsistent(sources), notesUncommitted = false), compute)

        /**
         * Run a derived state's initial [compute], reading [sources] from one
         * committed cut unless this thread holds a pending write for one (read
         * as any read here does). Returns the value, and whether the compute
         * read anything uncommitted — a pending write of any state, or a
         * reset's value — that may still roll back.
         */
        fun <R> initial(
            sources: List<MutableState<*>>,
            compute: () -> R,
        ): Pair<R, Boolean> {
            val reads = ComputeReads(sources, readConsistent(sources), notesUncommitted = true)
            val value = run(reads, compute)
            return value to reads.readUncommitted
        }

        /**
         * [state]'s value in the cut of the compute running on this thread, or
         * `null` when none runs or [state] is not one of its sources. Only the
         * innermost compute's cut applies. Called by [MutableState.value] while
         * a cut is taken over [state].
         */
        fun cutValueOf(state: MutableState<*>): Any? = (currentComputeReadsLocal() as ComputeReads?)?.cutValueOf(state)

        /**
         * [value], which a read on this thread returns although no commit has
         * made it (a pending write, a reset's value): noted when an initial
         * compute is running here, so its derived state catches up once that
         * value is committed or dropped.
         */
        fun <T> uncommitted(value: T): T {
            val reads = currentComputeReadsLocal() as ComputeReads?
            if (reads != null && reads.notesUncommitted) reads.readUncommitted = true
            return value
        }

        private fun <R> run(
            reads: ComputeReads,
            compute: () -> R,
        ): R {
            reads.sources.forEach { it.cutReaders.incrementAndGet() }
            val prior = currentComputeReadsLocal()
            setComputeReadsLocal(reads)
            try {
                return compute()
            } finally {
                setComputeReadsLocal(prior)
                reads.sources.forEach { it.cutReaders.decrementAndGet() }
            }
        }
    }
}
