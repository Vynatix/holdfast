package com.vynatix.holdfast

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
 */
internal class PostCommitQueue {
    private val lock = StoreLock()
    private val tasks = mutableListOf<() -> Unit>()

    /** Queue [task] unless this exact instance is already queued. */
    fun enqueue(task: () -> Unit) {
        lock.withLock {
            if (tasks.none { it === task }) tasks.add(task)
        }
    }

    /**
     * Take [task] back out of the queue if it is still there. A task about to
     * do its work withdraws itself first, so a submission that arrives after
     * the withdrawal queues a fresh run instead of being absorbed by this one.
     */
    fun withdraw(task: () -> Unit) {
        lock.withLock { tasks.removeAll { it === task } }
    }

    /**
     * Run every queued task, including tasks queued by the tasks it runs, on
     * the calling thread and outside the queue lock. A throwing task is
     * swallowed; callers that need its failure report it themselves.
     *
     * This pass runs each task instance at most once and leaves one that comes
     * back queued, because whoever queued it again owns its next run: either
     * the task found the store busy and handed itself to the current holder
     * (see [Store.tryTopLevelAction]), which drains after it releases, or
     * [Store.postCommit] queued it behind an active transaction, whose holder
     * drains when it ends (and `postCommit` drains itself if that holder
     * already has). Re-running a handed-off task here would spin against its
     * holder — and when the holder is a coroutine waiting for this very
     * thread, spin forever.
     */
    fun drain() {
        // Allocated on the first non-empty batch only: every top-level action
        // drains, and almost always finds the queue empty.
        var ran: MutableList<() -> Unit>? = null
        while (true) {
            val batch = lock.withLock { takeAllExcept(ran) }
            if (batch.isEmpty()) return
            val seen = ran ?: mutableListOf<() -> Unit>().also { ran = it }
            for (task in batch) {
                seen += task
                runCatching { task() }
            }
        }
    }

    private fun takeAllExcept(ran: List<() -> Unit>?): List<() -> Unit> {
        if (ran == null) return tasks.toList().also { tasks.clear() }
        val batch = tasks.filter { task -> ran.none { it === task } }
        tasks.removeAll { task -> batch.any { it === task } }
        return batch
    }
}
