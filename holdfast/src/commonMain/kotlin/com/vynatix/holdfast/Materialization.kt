package com.vynatix.holdfast

import com.vynatix.holdfast.platform.currentMaterializingLocal
import com.vynatix.holdfast.platform.currentThreadId
import com.vynatix.holdfast.platform.setMaterializingLocal
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
 * most one thread materializes it at a time; the others wait for it. (The
 * experimental `reset()`, and a sterile `restore()`, re-run the initializer
 * without this latch, so that re-run can overlap a materialization on another
 * thread.) A throwing initializer propagates to its caller and publishes
 * nothing, so the next read runs it again.
 *
 * Outside any entry, the outermost materialization on this thread is an
 * entry of its own: it opens a settle scope (SettleScope.kt), which settles
 * once the state is published and its latch released. So a change its
 * initializer makes that a derived state follows as a whole — a keyed entry
 * it creates (StoreEdges.kt) — recomputes that derived state once, after
 * the initializer, never in its middle. Nested in an entry, or in another
 * materialization, it joins the open scope.
 *
 * @throws IllegalStateException when the store is disposed, or when the
 *   initializer would (transitively) need this very state: an initializer
 *   cycle, on one thread or across threads.
 */
internal fun <T : Any> materialize(decl: StateDeclaration<T>): MutableState<T> =
    decl.materialized ?: settling { decl.store.initializerGraph.materialize(decl) }

/**
 * Materialize every [StateKind.Declared] state of this store that is not live
 * yet — or only those [select] picks — in declaration order, taking no store
 * lock (a caller inside an action still holds its `transactionLock`).
 */
internal fun Store<*>.materializeDeclaredStates(select: (StateDeclaration<*>) -> Boolean = { true }) {
    checkNotDisposed()
    for (decl in registry.declarationsInOrder()) {
        if (decl.kind == StateKind.Declared && decl.materialized == null && select(decl)) materialize(decl)
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
 * Something whose one-time work runs behind a latch of an [InitializerGraph]:
 * a state declaration (its initializer) or a tree child declaration (its
 * child lambda). The graph's lock guards [latchOwner]; [latch] is held by the
 * one thread running the work.
 */
internal interface Latched {
    /** Held by the thread running the work; waiters block on it. */
    val latch: SynchronousMutex

    /** The thread holding [latch], or [NO_LATCH_OWNER]. Guarded by the graph's lock. */
    var latchOwner: Long

    /** How a cycle message names this latch ("state initializer 'App.x'"). */
    fun describeForCycle(): String

    /** Throw when the work may not run (its store is disposed); checked before every claim. */
    fun checkMayRun()
}

/**
 * The latches of every declaration that shares this graph, and which thread
 * waits for which latch: the wait-for graph that materialization cycles are
 * found in. Every store uses [Process] — a cycle can span stores, and state
 * initializers and tree child declarations — except in tests.
 *
 * Every ownership change and every wait registration happens under [lock], so
 * the graph is always consistent: a latch's owner holds it until its work
 * returns, and a registered waiter cannot proceed until the latch it waits
 * for is released. A cycle found in it is therefore a real deadlock, and the
 * thread whose wait would close the cycle is the one that finds it.
 *
 * [threadId] is injectable so a JVM test can model wasmJs, where every thread
 * id is `0`. On that single-threaded target only a thread waiting for itself
 * can occur, which the owner check catches before any latch is touched.
 */
internal class InitializerGraph(
    private val threadId: () -> Long,
) {
    private val lock = SynchronousMutex()
    private val waitingFor = HashMap<Long, Latched>()

    /** [materialize]'s latch protocol. */
    fun <T : Any> materialize(decl: StateDeclaration<T>): MutableState<T> =
        decl.materialized ?: hold(decl) { decl.materialized ?: create(decl) }

    /**
     * Run [body] holding [latched]'s latch: at most one thread at a time, the
     * others waiting (parked) for it, a same-thread re-entry or a wait that
     * would close a cycle across threads failing with a teaching error
     * instead of deadlocking. [Latched.checkMayRun] runs before every claim,
     * also after a wait. [body] does its own "already done" short-circuit:
     * a waiter that wakes after the owner finished takes the latch and runs
     * [body] again.
     */
    fun <R> hold(
        latched: Latched,
        body: () -> R,
    ): R {
        while (true) {
            // Also after a wait: the store may have been disposed meanwhile,
            // and no work should run for a disposed store.
            latched.checkMayRun()
            val me = threadId()
            if (claim(latched, me)) {
                return try {
                    body()
                } finally {
                    release(latched)
                }
            }
            awaitRelease(latched, me)
        }
    }

    /** Number of threads registered as waiting for a latch; for tests. */
    val waitingCount: Int
        get() = locked { waitingFor.size }

    /**
     * Take [latched]'s latch for this thread (`true`), or register this
     * thread as waiting for it (`false`). The owner check comes first: the
     * latch is reentrant on the JVM, so a thread re-entering its own latch
     * must be caught before `tryLock` would let it in.
     */
    private fun claim(
        latched: Latched,
        me: Long,
    ): Boolean =
        locked {
            if (latched.latchOwner == me) throw IllegalStateException(sameThreadCycleMessage(latched))
            if (latched.latch.tryLock()) {
                latched.latchOwner = me
                MaterializingStack.push(latched)
                return@locked true
            }
            waitingFor[me] = latched
            val cycle = cycleThrough(latched, me)
            if (cycle != null) {
                waitingFor.remove(me)
                throw IllegalStateException(crossThreadCycleMessage(cycle))
            }
            false
        }

    private fun release(latched: Latched) {
        locked {
            MaterializingStack.pop(latched)
            latched.latchOwner = NO_LATCH_OWNER
            latched.latch.unlock()
        }
    }

    /** Block until [latched]'s latch is released, then stop waiting for it. */
    private fun awaitRelease(
        latched: Latched,
        me: Long,
    ) {
        try {
            latched.latch.lock()
            latched.latch.unlock()
        } finally {
            locked { waitingFor.remove(me) }
        }
    }

    /**
     * Walk from [start] — owner, the latch that owner waits for, its owner, … —
     * and return the latches passed on the way if the walk comes back to
     * [me], or `null` if it ends. The walk is bounded: a cycle among other
     * threads, which one of them is about to report, must not trap this one.
     */
    private fun cycleThrough(
        start: Latched,
        me: Long,
    ): List<Latched>? {
        val chain = mutableListOf<Latched>()
        var latched: Latched? = start
        var steps = waitingFor.size + 1
        while (latched != null && steps-- > 0) {
            chain += latched
            val owner = latched.latchOwner
            if (owner == me) return chain
            latched = if (owner == NO_LATCH_OWNER) null else waitingFor[owner]
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

/**
 * The latches this thread holds through [InitializerGraph.hold], innermost
 * on top (`platform/MaterializingLocal`): state declarations and tree child
 * declarations alike, so a cycle message prints an interleaved chain in
 * order.
 */
internal object MaterializingStack {
    private class Frame(
        val latched: Latched,
        val parent: Frame?,
    )

    fun push(latched: Latched) {
        setMaterializingLocal(Frame(latched, currentMaterializingLocal() as Frame?))
    }

    /** Pop [latched]'s frame (the innermost one, as holds nest). */
    fun pop(latched: Latched) {
        val top = currentMaterializingLocal() as Frame? ?: return
        if (top.latched === latched) setMaterializingLocal(top.parent)
    }

    /** The latches held on this thread, outermost first. */
    fun stack(): List<Latched> {
        val innermostFirst =
            generateSequence(currentMaterializingLocal() as Frame?) { it.parent }
                .map { it.latched }
                .toList()
        return innermostFirst.asReversed()
    }
}
