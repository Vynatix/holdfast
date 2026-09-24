@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast

import com.vynatix.holdfast.platform.currentThreadId
import kotlinx.atomicfu.atomic

// The machinery behind DerivedState (DerivedState.kt): checking its inputs,
// following its sources, staging its recomputed value, and refusing every
// other write to it.

/**
 * [source] resolved to the state that fires on its commits
 * ([observableBacking]), for [api] (`derived`, `derivedState`, …) to follow.
 *
 * @throws IllegalArgumentException for a [computed] state, or any State no
 *   store produced.
 */
internal fun State<*>.observableSourceFor(api: String): MutableState<*> =
    requireNotNull(observableBacking()) {
        "$api cannot follow this source: a source must be a state a store produced — a declared state " +
            "(`val x by state { … }`), a derived state or a derivedState/merged — whose commits it recomputes " +
            "on. A computed { } state has no commits to follow: list the states it reads as sources instead."
    }

/**
 * A [derivedState] source: [source] resolved as [observableSourceFor], on a
 * store that is not disposed.
 */
internal fun observedSource(
    source: State<*>,
    api: String,
): MutableState<*> {
    val backing = source.observableSourceFor(api)
    check(!backing.owningStore.isDisposed) {
        "store disposed: ${backing.owningStore.displayName}, the store of a $api source"
    }
    return backing
}

/**
 * One of [merged]'s inputs: [state] as a state this store declares.
 *
 * @throws IllegalArgumentException naming what [state] is instead.
 */
internal fun Store<*>.mergeInput(
    state: State<*>,
    role: String,
): MutableState<*> {
    val backing = state.observableBacking()
    val decl = backing?.declaration
    require(backing != null && decl?.kind == StateKind.Declared && backing.owningStore === this) {
        val what =
            when {
                backing == null -> "a computed { } state (or a State no store produced)"
                backing.owningStore !== this -> "a state of another store (${backing.owningStore.displayName})"
                decl == null -> "a state constructed by hand"
                else -> "a ${decl.kind.describe()}"
            }
        "merged on $displayName: its $role input is $what. merged(local, remote) merges two different states " +
            "that $displayName declares (`val x by state { … }`) and that are written directly — the user's side " +
            "and sync's side — so neither may be another store's, or computed from other states."
    }
    return backing
}

/** How a derived state's name refers to this source: its name, qualified when it is another store's. */
internal fun MutableState<*>.sourceName(host: Store<*>? = null): String {
    val decl = declaration ?: return "a state of ${owningStore.displayName}"
    return if (host == null || owningStore === host) decl.name else decl.qualifiedName
}

/**
 * Whether this thread's reads of this store's states return the pending
 * writes of its active transaction ([MutableState.value]'s read-your-own-writes):
 * this thread opened the action or frame that holds the store — including a
 * `suspendAction`/`suspendAtomic` that started here, even while its body is
 * parked. Such writes can still roll back, so a derived state's initial
 * compute, which reads the way any read on its thread does, is followed by a
 * catch-up recompute when this holds (see `createDerivedState`). The
 * recompute itself reads committed values only ([DerivedRecompute]).
 */
internal fun Store<*>.heldByThisThread(): Boolean = activeTransaction?.ownerThreadId == currentThreadId()

/**
 * Follow [sources] for a derived state of [host]: subscribe to each now, and
 * queue the recompute after each commit that changes one once the returned
 * follower is [armed][SourceFollower.arm]. Subscribing before the initial
 * compute runs, and arming after, is what keeps a source commit that lands
 * in between from being lost (see [SourceFollower]).
 */
internal fun <V : Store<V>> followSources(
    host: V,
    sources: List<MutableState<*>>,
): SourceFollower<V> = SourceFollower(host).also { it.start(sources) }

/**
 * The subscriptions of one derived state to its sources, and where a source
 * change queues its recompute.
 *
 * **Before the recompute exists.** The follower subscribes before the
 * derived state's initial compute runs and is [armed][arm] with the
 * recompute after it, so no source commit can fall between the two: one
 * whose fanout missed the new subscription applied its value before the
 * subscription was taken, so the compute (which runs after) reads it; one
 * whose fanout includes it reaches [onSourceChanged], which before arming
 * only records that a commit arrived ([MISSED]), and [arm] then queues one
 * catch-up recompute. Each subscription drops its first callback, which is
 * normally [MutableState.observe]'s initial one; when a racing commit's
 * callback overtakes it, the commit's is dropped instead and the initial one
 * counts as a change — one extra recompute, never a lost one.
 *
 * **Where a change queues it.** Inside a commit's fanout on the source's
 * store, the recompute is queued on THAT store: its post-commit queue then
 * deduplicates it across every source the commit changed (the host's would
 * run it inline once per source when the host is idle), and the store's
 * holder runs it once the commit has released the store. For a source on the
 * host, the two queues are the same one, as for `derived`. A change outside
 * any commit of the source's store — a `bridge` or `observeFrom` write,
 * which has no commit — queues it on the host instead, as `derived` does, so
 * it runs at once on an idle host rather than waiting for whoever holds the
 * source's store.
 *
 * A change that finds the host disposed releases every subscription, so a
 * disposed host does not stay referenced by another store's states.
 */
internal class SourceFollower<V : Store<V>>(
    private val host: V,
) : Disposable {
    private val released = atomic(false)

    /** [UNARMED], then [MISSED] once a commit arrived before [arm], then [ARMED]. */
    private val phase = atomic(UNARMED)

    /** Set by [arm], before [phase] turns [ARMED]. */
    @kotlin.concurrent.Volatile
    private var recompute: DerivedRecompute<V, *>? = null

    @kotlin.concurrent.Volatile
    private var subscriptions: List<Disposable> = emptyList()

    fun start(sources: List<MutableState<*>>) {
        subscriptions = sources.map { follow(it) }
        // Released while subscribing (a concurrent dispose saw the list
        // before it was assigned): drop what was just subscribed. Disposing
        // a subscription twice is harmless.
        if (released.value) subscriptions.forEach { it.dispose() }
    }

    /**
     * Start queueing [task] on source changes. It is queued once now when a
     * source commit arrived since [start], or when [catchUp] says the initial
     * compute read values that may still change without a commit (see
     * `createDerivedState`). Queued on the host: an idle host runs it at
     * once, a busy one hands it to its holder, and the recompute defers
     * itself to a blocking action or frame this thread runs on a source's
     * store.
     */
    fun arm(
        task: DerivedRecompute<V, *>,
        catchUp: Boolean,
    ) {
        recompute = task
        val missed = phase.getAndSet(ARMED) == MISSED
        // Disposed before the recompute existed: dispose saw no recompute to
        // dispose, so dispose it here (disposing it twice is harmless).
        if (released.value) {
            task.dispose()
            return
        }
        if (missed || catchUp) host.postCommit(task)
    }

    private fun follow(source: MutableState<*>): Disposable {
        val initialFire = atomic(true)
        @Suppress("UNCHECKED_CAST")
        return (source as MutableState<Any>).observe {
            // observe calls back once at once with the committed value at
            // subscription; only commits after that count (see the class KDoc
            // for a commit that overtakes it).
            if (initialFire.compareAndSet(expect = true, update = false)) return@observe
            if (host.isDisposed) {
                dispose()
            } else {
                onSourceChanged(source)
            }
        }
    }

    private fun onSourceChanged(source: MutableState<*>) {
        while (true) {
            val current = phase.value
            if (current == ARMED) {
                recompute?.let { queue(source, it) }
                return
            }
            if (current == MISSED || phase.compareAndSet(current, MISSED)) return
        }
    }

    private fun queue(
        source: MutableState<*>,
        task: DerivedRecompute<V, *>,
    ) {
        val sourceStore = source.owningStore
        val inItsCommit = sourceStore.activeTransaction?.fanningOutHere() == true
        (if (inItsCommit) sourceStore else host).postCommit(task)
    }

    override fun dispose() {
        if (!released.compareAndSet(expect = false, update = true)) return
        subscriptions.forEach { it.dispose() }
        recompute?.dispose()
    }

    private companion object {
        const val UNARMED = 0
        const val MISSED = 1
        const val ARMED = 2
    }
}

/**
 * Stage [value] as [backing]'s write in this store's active transaction:
 * the top-level transaction a derived state's recompute opened on its host.
 * The one write a [DerivedState]'s backing accepts; every other one goes
 * through the store's write entrypoints, which refuse it
 * ([refuseDerivedStateWrite]).
 */
internal fun <T : Any> Store<*>.stageRecomputedValue(
    backing: MutableState<T>,
    value: T,
) {
    val txn = checkNotNull(activeTransaction) { "a derived state's recompute runs inside its own transaction" }
    check(txn.stagePendingWrite(backing, backing.beforeSet(value))) {
        "the recompute transaction of ${backing.declaration?.qualifiedName} is closed to writes"
    }
}

/**
 * Throw when [state] is a [DerivedState], or the backing state of one: a
 * write to it (`mutate`, `update`, `bridge`, `observeFrom`) would be
 * overwritten by the next recompute, and its sources would no longer explain
 * its value.
 */
internal fun refuseDerivedStateWrite(state: State<*>) {
    val backing =
        when (state) {
            is DerivedStateNode<*> -> state.backing
            is MutableState<*> -> state.takeIf { it.declaration?.kind == StateKind.ReadOnlyDerived }
            else -> null
        } ?: return
    throw IllegalStateException(
        "Cannot write ${backing.declaration?.qualifiedName}: it is a derived state (derivedState or merged), " +
            "whose value is computed from its sources after each commit that changes one, so it is read-only — " +
            "mutate, update, bridge and observeFrom on it are refused. Write one of its sources instead, or " +
            "declare a state (`val x by state { … }`) for a value you write yourself.",
    )
}
