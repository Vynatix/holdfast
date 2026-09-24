@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast

import com.vynatix.holdfast.bridge.IntCodec
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
            // Only the operator runs: a later read would throw from getValue's own check.
            Entrypoint("state declaration (provideDelegate)") { p, _ ->
                p.state { 0 }.provideDelegate(null, DisposedProbe::n)
            },
            Entrypoint("state declaration with a codec (experimental overload)") { p, _ -> p.state(codec = IntCodec) { 0 } },
            Entrypoint("state declaration with tags (experimental overload)") { p, _ ->
                p.state(tags = setOf(StateTag.Secret)) { 0 }
            },
            Entrypoint("snapshot") { p, _ -> p.snapshot() },
            Entrypoint("snapshot with a scope") { p, _ -> p.snapshot(SnapshotScope.UserAuthored) },
            Entrypoint("taggedStates") { p, _ -> p.taggedStates(StateTag.Remote) },
            Entrypoint("restore") { p, _ -> p.restore(StoreSnapshot(mapOf("n" to 1))) },
            Entrypoint("restore with a policy") { p, _ -> p.restore(StoreSnapshot(mapOf("n" to 1)), RestorePolicy.BestEffort) },
            Entrypoint("sterile restore") { p, _ ->
                p.restore(StoreSnapshot(mapOf("n" to 1)), RestorePolicy.BestEffort, sterile = true)
            },
            Entrypoint("reset") { p, _ -> p.reset() },
            Entrypoint("registerDerivedBackingState") { p, _ -> p.registerDerivedBackingState("__probe", 0, emptyList()) },
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
            // A State extension, not a Store entrypoint: it reads the state's declaration.
            Entrypoint("State.tags") { _, s -> s.tags },
            Entrypoint("internalRefuseInitializerWrite (no initializer running)") { p, _ ->
                p.internalRefuseInitializerWrite("probe")
            },
            Entrypoint("dispose (idempotent)") { p, _ -> p.dispose() },
            Entrypoint("internalReportUncaughtFailure (handler)") { p, _ ->
                var reported: Throwable? = null
                p.uncaughtObserverHandler = { reported = it }
                p.internalReportUncaughtFailure(IllegalStateException("reported after dispose"))
                checkNotNull(reported) { "the handler was not called" }
            },
            Entrypoint("internalReportUncaughtFailure (default log)") { p, _ ->
                p.internalReportUncaughtFailure(IllegalStateException("expected: DisposedEntrypointTest probe"))
            },
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
