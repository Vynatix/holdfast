@file:OptIn(StoreInternalApi::class)

package com.vynatix.holdfast

import com.vynatix.holdfast.platform.currentSettleLocal
import com.vynatix.holdfast.platform.setSettleLocal
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

// Settling derivations once per outermost entry (issue #20, R9; plan decision D17).
//
// Every entry into a store — `action`, `atomic`, `suspendAction`,
// `suspendAtomic`, and the internal top-level attempt a recompute commits
// through — opens a settle scope on its thread when none is open there, and
// joins the open one otherwise. A commit fanning out inside the scope queues
// the recompute of each `derivedState`/`merged` whose source it changed into
// the scope, once per derived state however many of its sources, stores and
// nested actions the entry touched. The entry that opened the scope settles it
// when it exits, after it has released every serializer and lock it took:
// the queued recomputes run then, reading their sources from one committed
// cut (ComputeReads.kt), each committing on its host through the never-
// blocking top-level attempt. So a two-store frame, or several actions nested
// in one outer action, recompute a cross-store derived state once.
//
// The scope stays installed while it settles: a recompute's own commit joins
// it and queues the derived states downstream of it, which the same settle
// then runs — lower ranks first, so a derived state recomputes after every
// derived state it reads. `:holdfast-coroutines` carries a suspending entry's
// scope across thread hops (SettleAmbientContext.kt), so a suspending commit
// that fans out on another thread still queues into its own entry's scope.

/**
 * How many times one settle runs one piece of work — a task, or a frame's
 * drain of one store — before it leaves that work for later
 * ([SettleTask.deferPastSettle]; a store's queue to its next holder) and
 * reports the feedback loop.
 */
internal const val MAX_SETTLE_RUNS = 1_000

/** Work a [SettleScope] runs when it settles: the recompute of a derived state. */
internal interface SettleTask {
    /**
     * Where the task sits in a chain of derived states: one more than the
     * deepest derived state it reads ([StateDeclaration.settleRank]). A settle
     * runs lower ranks first.
     */
    val settleRank: Int

    /** Run the task: recompute, committing on its host (or handing off to a busy host). */
    fun settle()

    /**
     * The settle has already run this task [MAX_SETTLE_RUNS] times and it came
     * back again: a feedback loop, such as an observer of the derived state
     * writing one of its sources. Hand it to where it runs later instead of
     * running it again, so the settle ends — its host's post-commit queue,
     * which only the host's next holder drains — and, when
     * [report] (the first time in this settle), report the loop through the
     * host's [Store.uncaughtObserverHandler], handing off first.
     */
    fun deferPastSettle(report: Boolean)
}

/**
 * One settle scope: the derived-state recomputes queued by the commits of one
 * outermost entry — an action, frame, `suspendAction` or `suspendAtomic`, and
 * everything nested in it — and the post-commit drains its frames owe their
 * stores. Its entry runs them all at once, when it exits ([settle]).
 *
 * Thread-safe: a suspending entry's commit may fan out on another thread than
 * the one that opened the scope, and queue into it from there. Once settled it
 * refuses more work: a late caller runs its work the way it would with no
 * scope open.
 *
 * `@StoreInternalApi`: `:holdfast-coroutines` opens and carries the scopes of
 * suspending entries, and `:holdfast-testing` opens one around an open
 * transaction's commit.
 */
@StoreInternalApi
class SettleScope internal constructor() {
    private val lock = SynchronizedObject()

    // Allocated on first use: most entries queue nothing.
    private var drains: MutableList<Store<*>>? = null
    private var tasks: MutableList<SettleTask>? = null

    @kotlin.concurrent.Volatile
    private var settled = false

    /** Whether this scope still takes work: `false` once it has settled. */
    val isOpen: Boolean
        get() = !settled

    /**
     * Queue [task] unless this exact instance is already queued, in rank
     * order ([SettleTask.settleRank]; in queueing order within a rank).
     * `false`, queueing nothing, once the scope has settled.
     */
    internal fun enqueue(task: SettleTask): Boolean =
        synchronized(lock) {
            if (settled) return@synchronized false
            val queue = tasks ?: mutableListOf<SettleTask>().also { tasks = it }
            if (queue.none { it === task }) {
                val after = queue.indexOfFirst { it.settleRank > task.settleRank }
                if (after < 0) queue.add(task) else queue.add(after, task)
            }
            true
        }

    /**
     * Drain [store]'s post-commit queue when this scope settles, before any
     * recompute runs — the drain a frame owes a store whose root it opened.
     * `false`, queueing nothing, once the scope has settled: drain it now.
     */
    internal fun drainWhenSettled(store: Store<*>): Boolean =
        synchronized(lock) {
            if (settled) return@synchronized false
            val queue = drains ?: mutableListOf<Store<*>>().also { drains = it }
            if (queue.none { it === store }) queue.add(store)
            true
        }

    /**
     * Run everything queued — work queued meanwhile included — until nothing
     * is, then close the scope. The store drains run first, then the
     * recomputes, lower ranks first. Called once, by the entry that opened
     * the scope, after it has released everything it took, with the scope
     * still installed on its thread, so the entries a recompute opens join
     * it. A throwing task is swallowed, like a post-commit drain's: a
     * recompute reports its own failures.
     *
     * A task, or a frame's drain of one store, that this settle has already
     * run [MAX_SETTLE_RUNS] times is not run again: the task is handed to its
     * host's post-commit queue ([SettleTask.deferPastSettle]), and the store's
     * queue is left as it is. So a feedback loop — an observer of a derived
     * state writing one of its sources, or legacy `derived` observers opening
     * frames on each other's stores — cannot keep the settle going forever.
     * The work left over runs only when its store next has a holder, which
     * drains after it releases — an idle store has none, so a derived state
     * may lag its sources until then — and so the cut is reported, once per
     * piece of work and settle, through that store's
     * [Store.uncaughtObserverHandler].
     */
    fun settle() {
        // Runs per piece of work, by identity (neither Store nor a SettleTask
        // overrides equals): a SettleTask, or a frame's store drain.
        var runs: MutableMap<Any, Int>? = null
        while (true) {
            val next = synchronized(lock) { takeNext() } ?: return
            val counts = runs ?: HashMap<Any, Int>().also { runs = it }
            val times = (counts[next] ?: 0) + 1
            counts[next] = times
            val loop = times > MAX_SETTLE_RUNS
            val firstCut = times == MAX_SETTLE_RUNS + 1
            runCatching {
                when (next) {
                    // A feedback loop through frames keeps re-queuing this
                    // drain: leave the store's queue to its next holder, which
                    // drains after it releases.
                    is Store<*> ->
                        if (!loop) {
                            next.internalDrainPostCommitTasks()
                        } else if (firstCut && !next.isDisposed) {
                            next.internalReportUncaughtFailure(
                                IllegalStateException(
                                    "a frame's post-commit drain of ${next.displayName} ran $MAX_SETTLE_RUNS " +
                                        "times in one settle and came back again — a feedback loop through frames " +
                                        "(legacy derived observers opening frames on each other's stores?); its " +
                                        "queue is left to ${next.displayName}'s next holder, so a derived state " +
                                        "it hosts may lag its sources until then",
                                ),
                            )
                        }
                    is SettleTask -> if (loop) next.deferPastSettle(report = firstCut) else next.settle()
                }
            }
        }
    }

    /** The next piece of work, or `null` after closing the scope. The caller holds [lock]. */
    private fun takeNext(): Any? {
        val next = drains?.removeFirstOrNull() ?: tasks?.removeFirstOrNull()
        if (next == null) settled = true
        return next
    }
}

/**
 * The settle scope of the current thread. Cross-module surface for
 * `:holdfast-coroutines`, which installs a suspending entry's scope on every
 * thread that entry resumes on; application code has no reason to touch it.
 */
@StoreInternalApi
object SettleScopes {
    /** The open settle scope of this thread, or `null`: none, or one that has settled. */
    fun current(): SettleScope? = (currentSettleLocal() as SettleScope?)?.takeIf { it.isOpen }

    /**
     * Install [scope] (or clear the slot when `null`) and return the previous
     * value so callers can restore it — install/restore must always pair.
     */
    fun install(scope: SettleScope?): SettleScope? {
        val prior = currentSettleLocal() as SettleScope?
        setSettleLocal(scope)
        return prior
    }

    /** A new scope, installed nowhere yet. Its opener owns it and must [settle][SettleScope.settle] it. */
    fun open(): SettleScope = SettleScope()
}

/**
 * Run [block] as an entry: inside the settle scope open on this thread, or,
 * when none is, inside a new one that settles once [block] has returned or
 * thrown — so the caller must have released everything [block] took by then.
 */
internal inline fun <R> settling(block: () -> R): R {
    if (SettleScopes.current() != null) return block()
    val scope = SettleScope()
    val prior = SettleScopes.install(scope)
    try {
        return block()
    } finally {
        try {
            scope.settle()
        } finally {
            // A settled scope reads as none, so with nothing to restore it is
            // left in the slot: the next entry replaces it instead of
            // re-inserting a removed slot, which every outermost action pays.
            if (prior != null) SettleScopes.install(prior)
        }
    }
}

/**
 * [settling] for `:holdfast-testing`'s open transactions, whose commit and
 * rollback are entries of their own: run [block] in this thread's settle
 * scope, or in a new one that settles once [block] is done.
 */
@StoreInternalApi
fun <R> internalSettling(block: () -> R): R = settling(block)

/**
 * The post-commit drain an `atomic`/`suspendAtomic` frame owes this store,
 * whose root it opened (see `Store.tryTopLevelAction`), deferred to the settle
 * of this thread's scope — the end of the outermost entry the frame runs in —
 * or run now when no scope is open. Call it once the frame has released the
 * store.
 */
@StoreInternalApi
fun Store<*>.internalDrainPostCommitTasksWhenSettled() {
    if (SettleScopes.current()?.drainWhenSettled(this) != true) internalDrainPostCommitTasks()
}

/** 0 without [sources]; otherwise one more than the deepest [StateDeclaration.settleRank] among them. */
internal fun settleRankOver(sources: List<State<*>>): Int =
    if (sources.isEmpty()) 0 else 1 + sources.maxOf { it.observableBacking()?.declaration?.settleRank ?: 0 }
