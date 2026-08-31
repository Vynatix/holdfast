package com.vynatix.holdfast

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class Triple : Store<Triple>() {
    val a by state { 0 }
    val b by state { 0 }
    val c by state { 0 }
}

/**
 * A projection that fails only for one sentinel value, so the state stays
 * readable everywhere else in the test.
 */
private object PoisonProjection : Transformer<Int> {
    override fun set(value: Int): Int = value

    override fun get(value: Int): Int = if (value == 7) throw IllegalStateException("cannot project") else value
}

private class Poisoned : Store<Poisoned>() {
    val a by state { 0 }
    val poison by state(transformer = PoisonProjection) { 0 }
    val c by state { 0 }
}

/** Bridge whose `publish` always fails, as a full disk or encoder error would. */
private class ExplodingBridge<T : Any>(
    private val message: String = "disk full",
) : Bridge<T> {
    override fun publish(value: T): Boolean = throw IllegalStateException(message)

    override fun observe(observer: (T) -> Unit): Disposable = Disposable {}
}

/**
 * Commit fanout runs user code — `Bridge.publish`, `Transformer.get`, observer
 * bodies. None of it may tear the state a transaction commits.
 *
 * The guarantee is scoped deliberately: **state application is all-or-nothing**,
 * while fanout side effects are post-commit, isolated, and reported through
 * [Store.uncaughtObserverHandler]. A bridge is external sync, not a participant
 * in the transaction — `atomic`'s KDoc already says persistence publishes have
 * no crash-consistency — so a failed publish cannot roll back a committed value,
 * and must not be able to leave some states written and others not.
 */
class CommitAtomicityTest {
    @Test
    fun `a throwing bridge publish does not tear the commit`() {
        val store = Triple()
        val failures = mutableListOf<Throwable>()
        store.uncaughtObserverHandler = { failures += it }
        // Touch all three so registration order matches pendingWrites iteration order.
        store.a.value
        store.b.value
        store.c.value
        store { b bridge ExplodingBridge() }

        val result =
            store action {
                a mutate 1
                b mutate 1
                c mutate 1
            }

        assertIs<TransactionResult.Success<*>>(result, "a failed publish must not fail the commit")
        assertEquals(
            listOf(1, 1, 1),
            listOf(store.a.value, store.b.value, store.c.value),
            "every state in the transaction must be applied — a mid-fanout throw must not tear it",
        )
        assertEquals(1, failures.size, "the publish failure must be reported, not swallowed")
        assertEquals("disk full", failures.single().message)
    }

    @Test
    fun `a throwing transformer get does not tear the commit`() {
        val store = Poisoned()
        val failures = mutableListOf<Throwable>()
        store.uncaughtObserverHandler = { failures += it }
        store.a.value
        store.poison.value
        store.c.value

        val result =
            store action {
                a mutate 1
                poison mutate 7
                c mutate 1
            }

        assertIs<TransactionResult.Success<*>>(result)
        assertEquals(
            listOf(1, 1),
            listOf(store.a.value, store.c.value),
            "states either side of the failing projection must both be applied",
        )
        assertTrue(failures.any { it.message == "cannot project" }, "the projection failure must be reported")
    }

    @Test
    fun `all observers fire before any bridge publishes`() {
        val store = Triple()
        val order = mutableListOf<String>()
        store.a.value
        store.b.value
        store.c.value
        store {
            a effect { order += "observe-a" }
            c effect { order += "observe-c" }
            a bridge
                object : Bridge<Int> {
                    override fun publish(value: Int): Boolean {
                        order += "publish-a"
                        return true
                    }

                    override fun observe(observer: (Int) -> Unit): Disposable = Disposable {}
                }
        }
        order.clear()

        store action {
            a mutate 1
            c mutate 1
        }

        assertEquals(
            listOf("observe-a", "observe-c", "publish-a"),
            order,
            "commit fanout is phased: every observer runs before any bridge publish",
        )
    }
}
