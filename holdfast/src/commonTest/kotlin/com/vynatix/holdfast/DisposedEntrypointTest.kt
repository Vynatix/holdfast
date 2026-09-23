@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Clock

/** The store every row runs against. */
private class DisposedProbe : Store<DisposedProbe>() {
    val n by state { 0 }
}

/**
 * One public entrypoint and a call to it. [call] gets the disposed probe and its
 * `n` state, read before `dispose()` so the state is registered and a row that
 * takes a state exercises the entrypoint's own check, not the delegate read's.
 */
private class Entrypoint(
    val name: String,
    val call: (DisposedProbe, State<Int>) -> Unit,
)

/**
 * The disposed-store contract, table-driven: every public [Store] entrypoint
 * rejects a disposed store with an [IllegalStateException] whose message says
 * "disposed", and the documented exemptions keep working.
 *
 * A new public `Store` entrypoint calls `checkNotDisposed()` and gets a row in
 * [gated], or a row in [exempt] when its KDoc says it works after dispose
 * (issue #20 plan checklist). Each row runs on a fresh probe, and a failure
 * names every offending entrypoint at once.
 */
class DisposedEntrypointTest {
    private val gated =
        listOf(
            Entrypoint("action") { p, _ -> p action { } },
            Entrypoint("mutate") { p, s -> p { s mutate 1 } },
            Entrypoint("update") { p, s -> p { s update { it + 1 } } },
            Entrypoint("effect") { _, s -> s effect { } },
            Entrypoint("bridge") { p, s -> p { s bridge null } },
            Entrypoint("observeFrom") { p, s -> p { s observeFrom Observable { error("subscribed after dispose") } } },
            Entrypoint("state delegate read") { p, _ -> p.n },
            Entrypoint("properties") { p, _ -> p.properties },
            Entrypoint("getState") { p, _ -> p.getState("n") },
            Entrypoint("hasState") { p, _ -> p.hasState("n") },
            Entrypoint("removeState") { p, _ -> p.removeState("n") },
            Entrypoint("clearStates") { p, _ -> p.clearStates() },
            Entrypoint("middlewares") { p, _ -> p.middlewares() },
            Entrypoint("clearMiddleware") { p, _ -> p.clearMiddleware() },
            Entrypoint("bindClock") { p, _ -> p.bindClock(Clock.System) },
        )

    private val exempt =
        listOf(
            Entrypoint("clock") { p, _ -> p.clock },
            Entrypoint("internalBoundClock") { p, _ -> p.internalBoundClock },
            Entrypoint("isDisposed") { p, _ -> p.isDisposed },
            Entrypoint("dispose (idempotent)") { p, _ -> p.dispose() },
        )

    @Test
    fun everyGatedEntrypointThrowsOnADisposedStore() {
        val failures =
            gated.mapNotNull { entry ->
                val thrown = runCatching { callOnDisposed(entry) }.exceptionOrNull()
                when {
                    thrown !is IllegalStateException -> "${entry.name}: expected IllegalStateException, got ${thrown ?: "no throw"}"
                    thrown.message?.contains("disposed") != true -> "${entry.name}: message lacks 'disposed': ${thrown.message}"
                    else -> null
                }
            }
        assertTrue(failures.isEmpty(), failures.joinToString("\n", prefix = "not gated on dispose:\n"))
    }

    @Test
    fun documentedExemptionsWorkOnADisposedStore() {
        val failures =
            exempt.mapNotNull { entry ->
                runCatching { callOnDisposed(entry) }.exceptionOrNull()?.let { "${entry.name}: threw $it" }
            }
        assertTrue(failures.isEmpty(), failures.joinToString("\n", prefix = "documented to work after dispose:\n"))
    }

    private fun callOnDisposed(entry: Entrypoint) {
        val probe = DisposedProbe()
        val n = probe.n
        probe.dispose()
        entry.call(probe, n)
    }
}
