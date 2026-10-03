package com.vynatix.holdfast

import com.vynatix.holdfast.platform.currentConstructionLocal
import com.vynatix.holdfast.platform.setConstructionLocal

/**
 * The stores constructed ON THIS THREAD while one run of user code ran (a
 * tree child lambda or a keyed factory, `tree/RunBuilt.kt`), by
 * [Store.lockOrderKey]. Runs nest (a lambda first-reading another child):
 * a store is recorded in every log open on its constructing thread, so a
 * store an inner run built also counts as built during the outer run.
 *
 * Unlike a bare "key above a mark" test, a store a racing thread happened
 * to construct while the run ran (a lazily initialized shared store the
 * lambda returns) is NOT in the log. A store the run had built on another
 * thread (a `runBlocking(Dispatchers.Default) { … }` inside the lambda) is
 * not in it either: the log errs towards "not built here", which only ever
 * keeps a store alive.
 */
internal class ConstructionLog private constructor(
    private val enclosing: ConstructionLog?,
) {
    /** Written only by the owning thread while open; read once [record]'s run returned. */
    private val keys = HashSet<Long>()

    /** Whether the store keyed [lockOrderKey] was constructed on this log's thread while it was open. */
    operator fun contains(lockOrderKey: Long): Boolean = lockOrderKey in keys

    companion object {
        /** Run [body] with a fresh log open on this thread; answer its result and the closed log. */
        fun <R> record(body: () -> R): Pair<R, ConstructionLog> {
            val log = ConstructionLog(currentConstructionLocal() as ConstructionLog?)
            setConstructionLocal(log)
            try {
                return body() to log
            } finally {
                setConstructionLocal(log.enclosing)
            }
        }

        /** A store keyed [lockOrderKey] is being constructed on this thread: note it in every open log. */
        fun noteConstructed(lockOrderKey: Long) {
            var log = currentConstructionLocal() as ConstructionLog?
            while (log != null) {
                log.keys.add(lockOrderKey)
                log = log.enclosing
            }
        }
    }
}
