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
 * One step of a materialization chain as a cycle message names it: a state
 * declaration whose initializer runs behind its latch ([Latched]), or a tree
 * child declaration or keyed construction whose user code runs holding
 * nothing, marked on this thread only ([InitializerGraph.runMarked]).
 */
internal interface CycleStep {
    /** How a cycle message names this step ("state initializer 'App.x'"). */
    fun describeForCycle(): String
}

/**
 * Something whose one-time work runs behind a latch of an [InitializerGraph]:
 * a state declaration (its initializer). The graph's lock guards
 * [latchOwner]; [latch] is held by the one thread running the work.
 */
internal interface Latched : CycleStep {
    /** Held by the thread running the work; waiters block on it. */
    val latch: SynchronousMutex

    /** The thread holding [latch], or [NO_LATCH_OWNER]. Guarded by the graph's lock. */
    var latchOwner: Long

    /** Throw when the work may not run (its store is disposed); checked before every claim. */
    fun checkMayRun()
}

/**
 * The latches of every declaration that shares this graph, and which thread
 * waits for which latch: the wait-for graph that materialization cycles are
 * found in. Every store uses [Process] — a cycle can span stores — except in
 * tests. Tree child declarations and keyed factories take no latch here;
 * they only mark this thread ([runMarked]).
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
                    // Marked inside the try: a fatal error (issue #61) the mark
                    // throws still releases the latch, which would otherwise
                    // stay held and wedge this state for every thread.
                    MaterializingStack.push(latched)
                    body()
                } finally {
                    release(latched)
                }
            }
            awaitRelease(latched, me)
        }
    }

    /**
     * Run [body] marked on this thread as running [step] — holding NO latch
     * and no lock: other threads may run the same step concurrently (tree
     * child lambdas and keyed factories race, and the first to attach
     * wins). Only a re-entry on this thread — [body] needing [step] again,
     * directly or through state initializers and other steps — is a cycle,
     * and throws the same-thread cycle message naming the chain in order.
     * A cross-thread mutual recursion cannot hang: each thread runs the
     * other step itself and meets its own mark.
     */
    fun <R> runMarked(
        step: CycleStep,
        body: () -> R,
    ): R {
        if (MaterializingStack.stack().any { it == step }) throw IllegalStateException(sameThreadCycleMessage(step))
        try {
            // Inside the try (issue #61): a mark a fatal error leaves behind
            // would fail every later run of this step on this thread as a cycle.
            MaterializingStack.push(step)
            return body()
        } finally {
            MaterializingStack.pop(step)
        }
    }

    /** Number of threads registered as waiting for a latch; for tests. */
    val waitingCount: Int
        get() = locked { waitingFor.size }

    /**
     * Take [latched]'s latch for this thread (`true`), or register this
     * thread as waiting for it (`false`). The owner check comes first: the
     * latch is reentrant on the JVM, so a thread re-entering its own latch
     * must be caught before `lock` would let it in.
     *
     * The latch is taken with [Latched.latch.lock] — under [lock], and only
     * once `latchOwner` reads [NO_LATCH_OWNER], which (every hold and
     * release of a latch happens under the graph lock) proves the latch is
     * free, so `lock` returns without blocking. `tryLock` is deliberately
     * NOT used: atomicfu's wasmJs `SynchronousMutex` is a counting no-op
     * whose `tryLock` always succeeds without counting, so a
     * `tryLock`-claim paired with `release`'s `unlock` would decrement a
     * zero counter and throw "Mutex already unlocked" on the first
     * composition — `lock`/`unlock` is the one pair balanced on every
     * target.
     */
    private fun claim(
        latched: Latched,
        me: Long,
    ): Boolean =
        locked {
            if (latched.latchOwner == me) throw IllegalStateException(sameThreadCycleMessage(latched))
            if (latched.latchOwner != NO_LATCH_OWNER) {
                waitingFor[me] = latched
                // A walk that throws (a fatal error, issue #61) unregisters this
                // thread too: a stale wait would read as a cycle to other threads.
                val cycle = runCatching { cycleThrough(latched, me) }.onFailure { waitingFor.remove(me) }.getOrThrow()
                if (cycle != null) {
                    waitingFor.remove(me)
                    throw IllegalStateException(crossThreadCycleMessage(cycle))
                }
                return@locked false
            }
            latched.latch.lock()
            latched.latchOwner = me
            true
        }

    /** Release [latched]'s latch, then drop the mark [hold] pushed: the latch first, whatever the pop throws. */
    private fun release(latched: Latched) {
        locked {
            latched.latchOwner = NO_LATCH_OWNER
            latched.latch.unlock()
            MaterializingStack.pop(latched)
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
 * The steps this thread is running, innermost on top
 * (`platform/MaterializingLocal`): the latches it holds through
 * [InitializerGraph.hold] (state declarations) and the steps it marked
 * through [InitializerGraph.runMarked] (tree child declarations, keyed
 * constructions) alike, so a cycle message prints an interleaved chain in
 * order.
 */
internal object MaterializingStack {
    private class Frame(
        val latched: CycleStep,
        val parent: Frame?,
    )

    fun push(latched: CycleStep) {
        setMaterializingLocal(Frame(latched, currentMaterializingLocal() as Frame?))
    }

    /** Pop [latched]'s frame (the innermost one, as holds and marks nest). */
    fun pop(latched: CycleStep) {
        val top = currentMaterializingLocal() as Frame? ?: return
        if (top.latched === latched) setMaterializingLocal(top.parent)
    }

    /** The steps running on this thread, outermost first. */
    fun stack(): List<CycleStep> {
        val innermostFirst =
            generateSequence(currentMaterializingLocal() as Frame?) { it.parent }
                .map { it.latched }
                .toList()
        return innermostFirst.asReversed()
    }
}
