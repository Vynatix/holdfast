@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast

import com.vynatix.holdfast.bridge.IntCodec
import com.vynatix.holdfast.coroutines.suspendAction
import com.vynatix.holdfast.coroutines.suspendAtomic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs

private class HopTarget : Store<HopTarget>() {
    val n by state(codec = IntCodec) { 0 }
}

private class HopEntry : Store<HopEntry>() {
    val m by state(codec = IntCodec) { 0 }
}

/**
 * `restoreInOneFrame` (issue #20 plan PR 15) refuses to run inside a
 * suspending entry — a `suspendAction` or `suspendAtomic` body, on the thread
 * it started on or one it resumed on, and a commit's fanout — as it does
 * inside a blocking one: it sees the settle scope and frame marker the entry
 * carries onto every thread it runs on (on JVM/Android, through a
 * `ThreadContextElement`, so a nested `withContext` hop keeps them).
 */
class OneFrameRestoreSuspendingTest {
    @Test fun refusesInsideSuspendingEntriesOnEveryThreadTheyRunOn() {
        val source = HopTarget()
        source action { n mutate 7 }
        val cut = captureConsistent(listOf(source)).single()
        val target = HopTarget()
        val entry = HopEntry()
        val refused = mutableListOf<Pair<String, Throwable?>>()

        fun tryRestore(where: String) {
            refused += where to runCatching { restoreInOneFrame(mapOf(target to cut)) }.exceptionOrNull()
        }

        runBlocking {
            entry.suspendAction { tryRestore("suspendAction") }.getOrThrow()
            suspendAtomic(entry, target) { tryRestore("suspendAtomic") }.getOrThrow()
            entry
                .suspendAction {
                    yield()
                    tryRestore("suspendAction, resumed")
                }.getOrThrow()
            entry
                .suspendAction {
                    withContext(Dispatchers.Default) { tryRestore("suspendAction, on another thread") }
                }.getOrThrow()
            suspendAtomic(entry) {
                withContext(Dispatchers.Default) { tryRestore("suspendAtomic, on another thread") }
            }.getOrThrow()
        }
        val watch = entry.m effect { if (this == 1) tryRestore("an observer of a suspendAction's commit") }
        runBlocking { entry.suspendAction { m mutate 1 }.getOrThrow() }
        watch.dispose()

        assertEquals(
            listOf(
                "suspendAction",
                "suspendAtomic",
                "suspendAction, resumed",
                "suspendAction, on another thread",
                "suspendAtomic, on another thread",
                "an observer of a suspendAction's commit",
            ),
            refused.map { it.first },
        )
        for ((where, failure) in refused) {
            assertContains(assertIs<IllegalStateException>(failure, where).message!!, "in one frame", message = where)
        }
        assertEquals(0, target.n.value, "nothing was restored")
        restoreInOneFrame(mapOf(target to cut)).getOrThrow()
        assertEquals(7, target.n.value, "at top level it runs")
    }
}
