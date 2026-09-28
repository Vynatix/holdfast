@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.coroutines

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.StateTag
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.derivedState
import com.vynatix.holdfast.effect
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/** What a remote throws when the session expired. */
private class AuthFailure : Exception("session expired")

/** A synced store; its remote answers the n-th fetch with [answer]. */
private class History(
    answer: (Int) -> List<String>,
) : Store<History>() {
    val entries by state(tags = setOf(StateTag.Remote)) { emptyList<String>() }
    private var calls = 0
    val hydrator = hydrator { refresh { answer(++calls) } adopt { entries mutate it } }
}

private class Profile(
    answer: (Int) -> String,
) : Store<Profile>() {
    val name by state(tags = setOf(StateTag.Remote)) { "" }
    private var calls = 0
    val hydrator = hydrator { refresh { answer(++calls) } adopt { name mutate it } }
}

/** Hosts the app-wide flags, and nothing else. */
private class AppHealth : Store<AppHealth>()

/**
 * A consumer health flag over several stores' hydrations (issue #20, R8
 * acceptance): `derivedState(a.hydrator.state, b.hydrator.state) { … }`
 * follows both hydrators with no cross-store frame — each hydration commits
 * on its own store, and the flag settles after each.
 */
class HydrationHealthFlagTest {
    @Test fun aDerivedStateOverTwoHydratorsIsTheHealthFlagWithNoCrossStoreFrame() =
        runBlocking {
            val history = History { call -> if (call == 1) throw AuthFailure() else listOf("h") }
            val profile = Profile { "ada" }
            val app = AppHealth()
            val authFailed =
                app.derivedState(history.hydrator.state, profile.hydrator.state) {
                    listOf(history.hydrator.current, profile.hydrator.current)
                        .any { (it as? Hydration.Failed)?.cause is AuthFailure }
                }
            val allHydrated =
                app.derivedState(history.hydrator.state, profile.hydrator.state) {
                    history.hydrator.current == Hydration.Hydrated && profile.hydrator.current == Hydration.Hydrated
                }
            val flags = mutableListOf<Boolean>()
            authFailed effect { flags += this }

            // The refreshes run on each store's scope: this runBlocking.
            history.bindToScope(this)
            profile.bindToScope(this)
            hydrateEach(history.hydrator, profile.hydrator)
            history.hydrator.awaitSettled()
            profile.hydrator.awaitSettled()
            assertEquals(true, authFailed.value)
            assertEquals(false, allHydrated.value)

            history.hydrator.hydrate() // the retry
            assertEquals(Hydration.Hydrated, history.hydrator.awaitSettled())
            assertEquals(false, authFailed.value)
            assertEquals(true, allHydrated.value)
            assertEquals(listOf(false, true, false), flags, "one change per settled hydration, no torn value")
        }
}
