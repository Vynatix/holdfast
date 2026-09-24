package com.vynatix.holdfast

import com.vynatix.holdfast.platform.currentThreadId
import kotlinx.atomicfu.locks.SynchronousMutex

// Materialization: running a declared state's initializer exactly once and
// publishing the result (issue #20, plan decision D3).
//
// The initializer is user code, so materializing takes no store lock to run
// it: the old registry ran it under `propertiesLock`, and two stores whose
// initializers read each other's states deadlocked AB-BA on those locks.
// (It still runs under whatever its caller holds: first needed inside an
// action, it runs under that action's `transactionLock`.) Instead each
// declaration has its own latch. The first thread to need the state holds the
// latch while the initializer runs; every other thread blocks on it (parked,
// not spinning) and then reads what was published. A thread that would wait
// for itself — directly, or through a chain of other threads' latches — gets
// a teaching error naming the cycle instead of a deadlock. The initializer
// runs in a no-write region: it may read states — committed values only,
// never the pending writes of an action on its thread — but every store write
// entrypoint refuses to run inside it.

/**
 * The live state of [decl], created from its initializer on first need. At
 * most one thread runs the initializer at a time; the others wait for it. A
 * throwing initializer propagates to its caller and publishes nothing, so the
 * next read runs it again.
 *
 * @throws IllegalStateException when the store is disposed, or when the
 *   initializer would (transitively) need this very state: an initializer
 *   cycle, on one thread or across threads.
 */
internal fun <T : Any> materialize(decl: StateDeclaration<T>): MutableState<T> =
    decl.materialized ?: decl.store.initializerGraph.materialize(decl)

/**
 * Materialize every [StateKind.Declared] state of this store that is not live
 * yet, in declaration order, taking no store lock (a caller inside an action
 * still holds its `transactionLock`).
 */
internal fun Store<*>.materializeDeclaredStates() {
    checkNotDisposed()
    for (decl in registry.declarationsInOrder()) {
        if (decl.kind == StateKind.Declared && decl.materialized == null) materialize(decl)
    }
}

/**
 * Run [decl]'s initializer in a [NoWriteRegion] and publish the state it
 * seeds. The caller holds [StateDeclaration.latch].
 */
private fun <T : Any> create(decl: StateDeclaration<T>): MutableState<T> {
    val initial = NoWriteRegion.run(decl, decl.initializer)
    val state = MutableState(initial, decl.transformer, decl.store, decl.distinct)
    state.declaration = decl
    decl.store.registry.publish(decl, state)
    return state
}

/**
 * The latches of every declaration that shares this graph, and which thread
 * waits for which latch: the wait-for graph that initializer cycles are found
 * in. Every store uses [Process] — a cycle can span stores — except in tests.
 *
 * Every ownership change and every wait registration happens under [lock], so
 * the graph is always consistent: a latch's owner holds it until its
 * initializer returns, and a registered waiter cannot proceed until the latch
 * it waits for is released. A cycle found in it is therefore a real deadlock,
 * and the thread whose wait would close the cycle is the one that finds it.
 *
 * [threadId] is injectable so a JVM test can model wasmJs, where every thread
 * id is `0`. On that single-threaded target only a thread waiting for itself
 * can occur, which the owner check catches before any latch is touched.
 */
internal class InitializerGraph(
    private val threadId: () -> Long,
) {
    private val lock = SynchronousMutex()
    private val waitingFor = HashMap<Long, StateDeclaration<*>>()

    /** [materialize]'s latch protocol. */
    fun <T : Any> materialize(decl: StateDeclaration<T>): MutableState<T> {
        while (true) {
            decl.materialized?.let { return it }
            // Also after a wait: the store may have been disposed meanwhile,
            // and no initializer should run for a disposed store.
            decl.store.checkNotDisposed()
            val me = threadId()
            if (claim(decl, me)) {
                return try {
                    decl.materialized ?: create(decl)
                } finally {
                    release(decl)
                }
            }
            awaitRelease(decl, me)
        }
    }

    /** Number of threads registered as waiting for a latch; for tests. */
    val waitingCount: Int
        get() = locked { waitingFor.size }

    /**
     * Take [decl]'s latch for this thread (`true`), or register this thread as
     * waiting for it (`false`). The owner check comes first: the latch is
     * reentrant on the JVM, so a thread re-entering its own latch must be
     * caught before `tryLock` would let it in.
     */
    private fun claim(
        decl: StateDeclaration<*>,
        me: Long,
    ): Boolean =
        locked {
            if (decl.latchOwner == me) throw IllegalStateException(sameThreadCycleMessage(decl))
            if (decl.latch.tryLock()) {
                decl.latchOwner = me
                return@locked true
            }
            waitingFor[me] = decl
            val cycle = cycleThrough(decl, me)
            if (cycle != null) {
                waitingFor.remove(me)
                throw IllegalStateException(crossThreadCycleMessage(cycle))
            }
            false
        }

    private fun release(decl: StateDeclaration<*>) {
        locked {
            decl.latchOwner = NO_LATCH_OWNER
            decl.latch.unlock()
        }
    }

    /** Block until [decl]'s latch is released, then stop waiting for it. */
    private fun awaitRelease(
        decl: StateDeclaration<*>,
        me: Long,
    ) {
        try {
            decl.latch.lock()
            decl.latch.unlock()
        } finally {
            locked { waitingFor.remove(me) }
        }
    }

    /**
     * Walk from [start] — owner, the latch that owner waits for, its owner, … —
     * and return the declarations passed on the way if the walk comes back to
     * [me], or `null` if it ends. The walk is bounded: a cycle among other
     * threads, which one of them is about to report, must not trap this one.
     */
    private fun cycleThrough(
        start: StateDeclaration<*>,
        me: Long,
    ): List<StateDeclaration<*>>? {
        val chain = mutableListOf<StateDeclaration<*>>()
        var decl: StateDeclaration<*>? = start
        var steps = waitingFor.size + 1
        while (decl != null && steps-- > 0) {
            chain += decl
            val owner = decl.latchOwner
            if (owner == me) return chain
            decl = if (owner == NO_LATCH_OWNER) null else waitingFor[owner]
        }
        return null
    }

    private inline fun <R> locked(block: () -> R): R {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    companion object {
        /** The graph every store uses. */
        val Process = InitializerGraph(::currentThreadId)
    }
}
