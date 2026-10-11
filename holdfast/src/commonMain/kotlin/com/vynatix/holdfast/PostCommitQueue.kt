@file:OptIn(StoreInternalApi::class)

package com.vynatix.holdfast

import com.vynatix.holdfast.platform.currentThreadId

/**
 * One store's queue of deferred work: tasks that must run only after the
 * store's current top-level transaction has committed, fanned out and
 * released its locks. [Store.postCommit] feeds it; every top-level holder of
 * the store drains it after releasing (see [Store.tryTopLevelAction] for the
 * full list and why the drain is what makes a hand-off safe).
 *
 * Tasks are deduplicated by identity (`===`). A caller that re-submits the
 * same task instance while it is still queued gets one run, not two — this
 * is what lets `derived` coalesce several sources changing in one commit into
 * a single recompute. Tasks are compared by identity rather than `equals`, so
 * two equal-but-distinct function references still run separately.
 *
 * Guarded by its own lock rather than by the transaction lock: the drain runs
 * OUTSIDE the store's serializer bracket and `transactionLock`, so the queue is
 * reachable from a thread that holds neither.
 *
 * A drain never nests in a drain of the same queue on the same thread, and
 * ends even when the tasks it runs keep feeding it (see [drain]).
 */
internal class PostCommitQueue(
    /** The store whose queue this is: where a drain's cut is reported (see [drain]). */
    private val owner: Store<*>,
) {
    private val lock = StoreLock()
    private val tasks = mutableListOf<Queued>()

    /** A queued task and the thread that queued it, or `null` once more than one thread has. */
    private class Queued(
        val task: () -> Unit,
        var by: Long?,
    )

    /**
     * The threads draining this queue now, by thread id (see [drain]).
     * Guarded by [lock]; allocated on the first non-empty batch.
     */
    private var draining: HashMap<Long, Draining>? = null

    /** What one thread's drain of this queue has been handed while it runs. Guarded by [lock]. */
    private class Draining {
        /**
         * Everything that was queued when a drain nested in this one was
         * called: that drain's first batch, which this drain runs for it.
         */
        var owed: MutableList<() -> Unit>? = null

        /** Tasks this drain must leave queued for the store's next holder. */
        var cut: MutableList<() -> Unit>? = null
    }

    /**
     * Queue [task] unless this exact instance is already queued. With
     * [forNextHolder], the drain running on this thread (if any) leaves it
     * queued for the store's next holder: a settle's feedback-loop cut hands
     * its task back this way, so the drain that ran the settle does not start
     * the loop again.
     */
    fun enqueue(
        task: () -> Unit,
        forNextHolder: Boolean = false,
    ) {
        val me = currentThreadId()
        lock.withLock {
            val queued = tasks.firstOrNull { it.task === task }
            if (queued == null) {
                tasks.add(Queued(task, me))
            } else if (queued.by != me) {
                queued.by = null
            }
            if (forNextHolder) draining?.get(me)?.let { mine -> mine.cut = mine.cut.plusOnce(task) }
        }
    }

    /**
     * Take [task] back out of the queue if it is still there. A task about to
     * do its work withdraws itself first, so a submission that arrives after
     * the withdrawal queues a fresh run instead of being absorbed by this one.
     */
    fun withdraw(task: () -> Unit) {
        lock.withLock { tasks.removeAll { it.task === task } }
    }

    /**
     * Run every queued task, including tasks queued by the tasks it runs, on
     * the calling thread and outside the queue lock. A throwing task is
     * swallowed; callers that need its failure report it themselves.
     *
     * A drain runs each task instance at most once and leaves one that comes
     * back queued, because whoever queued it again owns its next run: either
     * the task found the store busy and handed itself to the current holder
     * (see [Store.tryTopLevelAction]), which drains after it releases, or
     * [Store.postCommit] queued it behind an active transaction, whose holder
     * drains when it ends (and `postCommit` drains itself if that holder
     * already has). Re-running a handed-off task here would spin against its
     * holder — and when the holder is a coroutine waiting for this very
     * thread, spin forever.
     *
     * The one exception is a drain nested in this drain on the same thread: a
     * task this drain runs took the store and released it, as a derived
     * state's top-level attempt does, and that holder drains. It runs nothing
     * in place. It hands this drain everything queued at that moment — what it
     * would have run first — and this drain runs those tasks, as one more
     * pass, once it has run everything else it took. So the stack stays
     * bounded however many times another thread hands a task back to this
     * thread's hold. Running the nested drain in place added a stack level per
     * hand-back (issue #37), until a StackOverflowError that the `runCatching`
     * here or in [SettleScope.settle] swallowed, after it had struck wherever
     * the stack ran out: between `NoWriteRegion`'s slot write and its restore
     * (leaving a compute frame that refused every later write on the thread),
     * or between a lock's acquire and its release.
     *
     * Two things end such passes when the drain itself keeps feeding them:
     * - A task a settle cut and handed back on this thread for the store's
     *   next holder (`enqueue(forNextHolder = true)`) stays queued for that
     *   holder; this drain does not run it, and so does not start the settle's
     *   loop again.
     * - A pass made only of tasks this thread queued is fed by the drain
     *   itself — an observer of a legacy `derived` state writing one of its
     *   sources on another store re-queues the recompute on every run. After
     *   [MAX_SETTLE_RUNS] such passes in a row, the drain cuts the next one:
     *   leaves its tasks queued for the store's next holder and reports the
     *   cut once, through [owner]'s `uncaughtObserverHandler` (a handler that
     *   throws there is ignored: a drain never throws). It reports while still
     *   draining, so a handler that takes the store again hands its drain to
     *   this one, which skips what it cut. A pass with any task another thread
     *   queued starts the count again: that loop ends when the other thread
     *   stops.
     */
    fun drain() {
        val me = currentThreadId()
        var batch = beginDrain(me) ?: return
        val run = Run(me)
        try {
            while (batch.isNotEmpty()) {
                for (task in batch) {
                    run.ran = run.ran.plusOnce(task)
                    runCatching { task() }
                }
                batch = lock.withLock { run.next() }
                if (batch.isEmpty() && run.reportCutOnce()) batch = lock.withLock { run.next() }
            }
        } finally {
            lock.withLock {
                val active = draining
                if (active != null) {
                    active.remove(me)
                    // An idle queue's drain then skips the registration lookup.
                    if (active.isEmpty()) draining = null
                }
            }
        }
    }

    /**
     * This thread's first batch, registering it as draining. `null` when there
     * is nothing to run here: an empty queue, or a drain nested in this
     * thread's own drain of this queue, which hands that drain everything
     * queued now instead of running it.
     */
    private fun beginDrain(me: Long): List<() -> Unit>? =
        lock.withLock {
            val mine = draining?.get(me)
            when {
                mine != null -> {
                    for (queued in tasks) mine.owed = mine.owed.plusOnce(queued.task)
                    null
                }
                tasks.isEmpty() -> null
                else -> {
                    (draining ?: HashMap<Long, Draining>(2).also { draining = it })[me] = Draining()
                    tasks.map { it.task }.also { tasks.clear() }
                }
            }
        }

    /** One [drain] call that found work. [next] runs under [lock]. */
    private inner class Run(
        private val me: Long,
    ) {
        /** Every task this drain has run. */
        var ran: MutableList<() -> Unit>? = null

        /** Owed passes in a row made only of tasks this thread queued. */
        private var selfFed = 0

        /** Whether this drain has cut a pass it has not reported yet ([reported]: whether it reported one). */
        private var cutPending = false
        private var reported = false

        /**
         * The next batch: the queued tasks this drain has not run yet; when
         * there are none, a pass of the tasks nested drains handed it; else
         * nothing, and the drain ends. Never a task this drain must leave
         * queued.
         */
        fun next(): List<() -> Unit> {
            val mine = draining?.get(me) ?: Draining()
            return takeWhere { task -> !ran.has(task) && !mine.cut.has(task) }.ifEmpty { owedPass(mine) }
        }

        /**
         * The queued tasks nested drains handed this drain, as one pass —
         * unless the drain has fed too many such passes itself, when it cuts
         * this one: leaves its tasks queued and takes nothing.
         */
        private fun owedPass(mine: Draining): List<() -> Unit> {
            val owed = mine.owed.orEmpty().also { mine.owed = null }
            val pass = tasks.filter { q -> owed.has(q.task) && !mine.cut.has(q.task) }
            selfFed = if (pass.isNotEmpty() && pass.all { it.by == me }) selfFed + 1 else 0
            val cutNow = selfFed > MAX_SETTLE_RUNS
            if (cutNow) {
                for (queued in pass) mine.cut = mine.cut.plusOnce(queued.task)
                cutPending = true
            } else {
                tasks.removeAll { q -> pass.any { it === q } }
            }
            return if (cutNow) emptyList() else pass.map { it.task }
        }

        /**
         * Report, value-free, the first cut this drain made, outside the lock
         * and while it is still registered; whether it reported now.
         */
        fun reportCutOnce(): Boolean {
            if (!cutPending || reported) return false
            reported = true
            if (!owner.isDisposed) {
                val name = owner.displayName
                runCatching {
                    owner.internalReportUncaughtFailure(
                        IllegalStateException(
                            "a post-commit task of $name (typically a derived recompute) came back to one " +
                                "drain of its queue $MAX_SETTLE_RUNS times in a row from that drain's own work — a " +
                                "feedback loop (an observer of a derived state writing one of its sources on another " +
                                "store?); it is left in $name's post-commit queue for its next holder, so a derived " +
                                "state it hosts may lag its sources until then",
                        ),
                    )
                }
            }
            return true
        }

        /** Take the queued tasks [wanted] picks, in queue order. */
        private fun takeWhere(wanted: (() -> Unit) -> Boolean): List<() -> Unit> {
            val batch = tasks.filter { wanted(it.task) }
            tasks.removeAll { q -> batch.any { it === q } }
            return batch.map { it.task }
        }
    }
}

/** Whether this list holds this exact [task] instance. */
private fun List<() -> Unit>?.has(task: () -> Unit): Boolean = this?.any { it === task } == true

/** This list with [task] added unless this exact instance is already in it (allocated on first use). */
private fun MutableList<() -> Unit>?.plusOnce(task: () -> Unit): MutableList<() -> Unit> =
    (this ?: ArrayList(2)).also { list -> if (list.none { it === task }) list += task }
