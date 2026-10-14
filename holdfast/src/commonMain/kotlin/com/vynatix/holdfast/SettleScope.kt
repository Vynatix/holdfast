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

    /**
     * Report [error], which a settle caught escaping [settle] past the paths
     * that report their own failures (a recompute's failed transaction is
     * reported by the task itself; this is a throw around it, from outside a
     * transaction body — such as out of the top-level attempt's lock
     * release, issue #26's wasmJs bug): through the task's host store's
     * [Store.internalReportUncaughtFailure], like every post-commit failure,
     * quoting no state value. The settle keeps going after the failure and
     * guards this call, so a throwing handler cannot strand the work still
     * queued.
     */
    fun reportUncaught(error: Throwable)
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
 * `open` for [JoinedSettleScope] alone (issue #38): the view a suspending
 * entry nested in another entry's body runs under, which forwards everything
 * to the real scope. Not part of the extension surface.
 *
 * `@StoreInternalApi`: `:holdfast-coroutines` opens and carries the scopes of
 * suspending entries, and `:holdfast-testing` opens one around an open
 * transaction's commit.
 */
@StoreInternalApi
open class SettleScope internal constructor(
    /**
     * The scope open on the opener's thread when this one opened
     * ([SettleScopes.open]): this scope hands its work there while that scope
     * takes it, so everything still settles once, with the outermost entry,
     * and keeps the work that comes once that scope has settled. It is also
     * the nesting chain [scopeChainReaches] walks: a scope and everything
     * handed to the scopes enclosing it belong to entries nested in them.
     */
    internal val within: SettleScope?,
) {
    private val lock = SynchronizedObject()

    // Allocated on first use: most entries queue nothing.
    private var drains: MutableList<Store<*>>? = null
    private var tasks: MutableList<SettleTask>? = null

    @kotlin.concurrent.Volatile
    private var settled = false

    /** Whether this scope still takes work: `false` once it has settled. */
    open val isOpen: Boolean
        get() = !settled

    /**
     * Queue [task] unless this exact instance is already queued, in rank
     * order ([SettleTask.settleRank]; in queueing order within a rank), or
     * hand it to [within] while that scope takes it. `false`, queueing
     * nothing, once the scope has settled.
     */
    internal open fun enqueue(task: SettleTask): Boolean =
        within?.enqueue(task) == true ||
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
     * recompute runs — the drain a frame owes a store whose root it opened —
     * or hand that to [within] while that scope takes it. `false`, queueing
     * nothing, once the scope has settled: drain it now.
     */
    internal open fun drainWhenSettled(store: Store<*>): Boolean =
        within?.drainWhenSettled(store) == true ||
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
     * it. A task, or a frame's drain, that throws past the paths that report
     * their own failures — a throw from outside a transaction body, never a
     * failed transaction, which its task reports — is reported, never
     * dropped: a drain through that store's [Store.uncaughtObserverHandler],
     * a task through its host's ([SettleTask.reportUncaught]). The settle
     * keeps going after such a failure.
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
     *
     * A fatal error (issue #61) — from the work, or from a handler a report
     * reaches — is never reported: the settle holds the first, keeps going
     * until nothing is queued, so the scope closes and none of its work is
     * stranded, and then throws it.
     */
    open fun settle() {
        // Runs per piece of work, by identity (neither Store nor a SettleTask
        // overrides equals): a SettleTask, or a frame's store drain.
        var runs: MutableMap<Any, Int>? = null
        val fatal = FatalHold()
        while (true) {
            val next = synchronized(lock) { takeNext() } ?: break
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
            }.onFailure { error -> if (!fatal.holdIfFatal(error)) reportUncaught(next, error, fatal) }
        }
        fatal.rethrowHeld()
    }

    /**
     * [settle] caught [error] escaping a piece of its work past the paths
     * that report their own failures (a failed transaction is reported by
     * its task; this is a throw around it): report it, never drop it — a
     * drain through that store's [internalReportUncaughtFailure], a task
     * through [SettleTask.reportUncaught] to its host. Guarded, and the
     * settle keeps going after the failure: a throwing handler must not
     * strand the work still queued, nor mask the entry's own exception
     * ([settle] runs in a finally) — but a fatal error it throws (issue #61)
     * is held in [fatal], for the settle to throw once it has settled.
     */
    private fun reportUncaught(
        next: Any,
        error: Throwable,
        fatal: FatalHold,
    ) {
        runCatching {
            when (next) {
                is Store<*> -> if (!next.isDisposed) next.internalReportUncaughtFailure(error)
                is SettleTask -> next.reportUncaught(error)
            }
        }.onFailure { fatal.holdIfFatal(it) }
    }

    /** The next piece of work, or `null` after closing the scope. The caller holds [lock]. */
    private fun takeNext(): Any? {
        val next = drains?.removeFirstOrNull() ?: tasks?.removeFirstOrNull()
        if (next == null) settled = true
        return next
    }
}

/**
 * The settle scope a suspending entry NESTED in another entry's body runs
 * under (issue #38): a view of [carried] with an identity of its own. The
 * nested entry's roots record the view as their [Transaction.openedInScope],
 * so its pending writes and staged evictions are read only by its own body —
 * not by the enclosing body or the coroutines that one launched, which carry
 * [carried] — while the nesting still counts the other way
 * ([scopeChainReaches]): the nested body's chain is the view then [carried],
 * so it keeps reading every enclosing body's pending writes.
 *
 * Everything else delegates: work queued or a drain owed inside the nested
 * entry goes to [carried] and settles once, with the outermost entry, exactly
 * as before. The view itself never settles — its [settle] is a no-op, and it
 * is open exactly while [carried] is, so a nested body that outlives its
 * opener reads [SettleScopes.current] as none once that one settled, and the
 * no-scope routing applies, as it would have without the view.
 *
 * Built by `:holdfast-coroutines` ([SettleScopes.openJoined]) around each
 * nested entry's body; never installed anywhere else.
 */
internal class JoinedSettleScope(
    private val carried: SettleScope,
) : SettleScope(within = carried) {
    override val isOpen: Boolean
        get() = carried.isOpen

    override fun enqueue(task: SettleTask): Boolean = carried.enqueue(task)

    override fun drainWhenSettled(store: Store<*>): Boolean = carried.drainWhenSettled(store)

    // A deliberate no-op: settling is owned by the outermost entry, whose
    // own scope's settle runs everything the view forwarded to it. Closing
    // this view would only strand work if the base path ever ran — it never
    // does, every member delegates.
    override fun settle() = Unit
}

/**
 * Whether the settle-scope chain of [current] — the scope itself, then each
 * scope the one before it hands its work to ([SettleScope.within]) — contains
 * [target]: the code reading runs inside the entry [target] belongs to, or
 * inside an entry nested in it. Issue #38: the chain is how
 * [Transaction.readsPendingHere] keeps a nested suspending entry reading its
 * enclosing bodies' pending writes while the enclosing bodies' other
 * coroutines (whose chain stops at their own scope) no longer read the
 * nested entry's.
 */
internal fun scopeChainReaches(
    current: SettleScope?,
    target: SettleScope,
): Boolean {
    var scope = current
    while (scope != null) {
        if (scope === target) return true
        scope = scope.within
    }
    return false
}

/**
 * The scope clause of [Transaction.readsPendingHere] (issue #38):
 * [scopeChainReaches] with the null-root rule folded in — a root that
 * recorded no scope (`Transaction.openedInScope == null`) deliberately
 * restores owner-thread semantics and passes. Issue #31's blocking-entry
 * refusal wants the opposite polarity for `null` and calls
 * [scopeChainReaches] itself.
 */
internal fun scopeChainReachesOrUnscoped(
    current: SettleScope?,
    target: SettleScope?,
): Boolean = target == null || scopeChainReaches(current, target)

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

    /**
     * A new scope, installed nowhere yet. Its opener owns it and must
     * [settle][SettleScope.settle] it. Opened while a scope is open on this
     * thread ([current]), it hands its work to that one while that one takes
     * it — so derived states still settle once, with the outermost entry —
     * and keeps, and settles itself, the work that comes after that one has
     * settled. Either way it is a scope of its own, open until its opener
     * settles it: `:holdfast-coroutines` gives a suspending entry started
     * inside a blocking one such a scope, whose identity its transactions
     * record ([Transaction.readsPendingHere]) for its whole body, which may
     * outlive the blocking entry (#28).
     */
    fun open(): SettleScope = SettleScope(within = current())

    /**
     * The [JoinedSettleScope] view of [carried] for a suspending entry nested
     * in another entry's body (issue #38): an identity of its own for the
     * nested entry's roots, everything the entry queues settled by the
     * outermost entry through [carried]. The caller (`:holdfast-coroutines`)
     * installs it around the nested body only, and never settles it.
     */
    fun openJoined(carried: SettleScope): SettleScope = JoinedSettleScope(carried)
}

/**
 * Run [block] as an entry: inside the settle scope open on this thread, or,
 * when none is, inside a new one that settles once [block] has returned or
 * thrown — so the caller must have released everything [block] took by then.
 * A fatal error (issue #61) [block] threw outranks one the settle throws,
 * which it then carries as suppressed; otherwise a fatal error the settle
 * throws replaces what [block] returned or threw (carrying a throwable as
 * suppressed). The scope has settled and closed by then.
 */
internal inline fun <R> settling(block: () -> R): R {
    if (SettleScopes.current() != null) return block()
    val scope = SettleScope(within = null)
    val fatal = FatalHold()
    var unwinding: Throwable? = null
    var prior: SettleScope? = null
    try {
        // Installed inside the try (issue #61, as NoWriteRegion.enter): a
        // fatal error striking the slot write still settles the scope.
        prior = SettleScopes.install(scope)
        return runCatching(block).onFailure { if (!fatal.holdIfFatal(it)) unwinding = it }.getOrThrow()
    } finally {
        try {
            fatal.runHoldingFatal { scope.settle() }
        } finally {
            // A settled scope reads as none, so with nothing to restore it is
            // left in the slot: the next entry replaces it instead of
            // re-inserting a removed slot, which every outermost action pays.
            if (prior != null) SettleScopes.install(prior)
        }
        fatal.rethrowHeld(alongside = unwinding)
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
