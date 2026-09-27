@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.StateTag
import com.vynatix.holdfast.Store
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/** The store every row runs against, with a hydrator. */
private class DisposedProbe : Store<DisposedProbe>() {
    val remote by state(tags = setOf(StateTag.Remote)) { 0 }
    val hydration = hydrator { refresh { 1 } adopt { remote mutate it } }
}

/** One `:holdfast-coroutines` entrypoint and a call to it on a disposed probe. */
private class Entrypoint(
    val name: String,
    val call: suspend (DisposedProbe) -> Unit,
)

/**
 * The disposed-store contract for `:holdfast-coroutines`' entrypoints,
 * table-driven like core's `DisposedEntrypointTest`: every row in [gated]
 * throws an [IllegalStateException] whose message says "disposed" on a
 * disposed store, and every row in [exempt] — documented to work after
 * `dispose()` — keeps working. A new entrypoint of this module that takes a
 * store gets a row (issue #20 plan checklist).
 */
class DisposedEntrypointTest {
    private val gated =
        listOf(
            Entrypoint("hydrator { }") { p -> p.hydrator { refresh { 1 } adopt { } } },
            Entrypoint("hydratorOrNull") { p -> p.hydratorOrNull() },
            Entrypoint("Hydrator.hydrate") { p -> runBlocking { p.hydration.hydrate(this) } },
            Entrypoint("Hydrator.invalidate") { p -> p.hydration.invalidate() },
            Entrypoint("Hydrator.stageInvalidate") { p -> p.hydration.stageInvalidate() },
            Entrypoint("Hydrator.awaitSettled") { p -> p.hydration.awaitSettled() },
            Entrypoint("hydrateEach") { p -> hydrateEach(p.hydration) },
        )

    private val exempt =
        listOf(
            // Reads of the hydrator's own state: it keeps its last value.
            Entrypoint("Hydrator.state") { p -> p.hydration.state.value },
            Entrypoint("Hydrator.current") { p -> p.hydration.current },
            Entrypoint("Hydrator.toString") { p -> p.hydration.toString() },
        )

    @Test fun everyGatedEntrypointThrowsOnADisposedStore() {
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

    @Test fun documentedExemptionsWorkOnADisposedStore() {
        val failures =
            exempt.mapNotNull { entry ->
                runCatching { callOnDisposed(entry) }.exceptionOrNull()?.let { "${entry.name}: threw $it" }
            }
        assertTrue(failures.isEmpty(), failures.joinToString("\n", prefix = "documented to work after dispose:\n"))
    }

    private fun callOnDisposed(entry: Entrypoint) {
        val probe = DisposedProbe()
        probe.dispose()
        runBlocking { entry.call(probe) }
    }
}
