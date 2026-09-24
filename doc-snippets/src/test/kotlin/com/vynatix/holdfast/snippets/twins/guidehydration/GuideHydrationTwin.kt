// Twin of GUIDE §16.7 (hydration). The blocks are embedded at top level; the
// test drives them and asserts the output the first one's comments claim.
package com.vynatix.holdfast.snippets.twins.guidehydration

import com.vynatix.holdfast.DerivedState
import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.StateTag
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.coroutines.Hydration
import com.vynatix.holdfast.coroutines.hydrator
import com.vynatix.holdfast.derivedState
import com.vynatix.holdfast.effect
import com.vynatix.holdfast.snippets.capturePrintln
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

// DOC-SNIPPET holdfast/GUIDE.md#77
interface HistoryApi {
    suspend fun fetchHistory(): List<String>
}

@OptIn(ExperimentalStoreApi::class)
class HistoryStore(api: HistoryApi) : Store<HistoryStore>() {
    val pinned by state(tags = setOf(StateTag.UserAuthored)) { emptySet<String>() }
    val entries by state(tags = setOf(StateTag.Remote)) { emptyList<String>() }
    val hydration =
        hydrator {
            base { entries mutate listOf("bundled") }          // in the seed transaction
            refresh { api.fetchHistory() } adopt { fetched ->   // on hydrate()'s scope, once seeded
                entries mutate fetched                         // Remote states only
            }
        }
}

@OptIn(ExperimentalStoreApi::class)
suspend fun openHistory(store: HistoryStore, scope: CoroutineScope) {
    store.hydration.state effect { println("phase: $this") }   // "phase: Detached"
    store.hydration.hydrate(scope)                              // "phase: Seeded"
    store.hydration.hydrate(scope)                              // in flight already: does nothing
    println(store.entries.value)                                // "[bundled]"
    println(store.hydration.awaitSettled())                     // "phase: Hydrated", then "Hydrated"
    println(store.entries.value)                                // "[a, b]"
}
// DOC-SNIPPET-END

// Scaffold: what the health-flag example names but does not define.
class SessionExpired : Exception("session expired")

class AppStore : Store<AppStore>()

@OptIn(ExperimentalStoreApi::class)
class ProfileStore(
    fetch: suspend () -> String,
) : Store<ProfileStore>() {
    val name by state(tags = setOf(StateTag.Remote)) { "" }
    val hydration = hydrator { refresh { fetch() } adopt { name mutate it } }
}

// DOC-SNIPPET holdfast/GUIDE.md#78
@OptIn(ExperimentalStoreApi::class)
fun sessionExpired(app: AppStore, history: HistoryStore, profile: ProfileStore): DerivedState<Boolean> =
    app.derivedState(history.hydration.state, profile.hydration.state) {
        listOf(history.hydration.current, profile.hydration.current)
            .any { (it as? Hydration.Failed)?.cause is SessionExpired }
    }
// DOC-SNIPPET-END

class GuideHydrationTwin {
    @Test
    fun openHistoryPrintsWhatItsCommentsClaim() {
        val store = HistoryStore(
            object : HistoryApi {
                override suspend fun fetchHistory() = listOf("a", "b")
            },
        )
        val printed = capturePrintln { runBlocking { openHistory(store, this) } }
        assertEquals(
            listOf("phase: Detached", "phase: Seeded", "[bundled]", "phase: Hydrated", "Hydrated", "[a, b]"),
            printed,
        )
    }

    @OptIn(ExperimentalStoreApi::class)
    @Test
    fun theHealthFlagFollowsBothHydrations() {
        runBlocking {
            var expired = true
            val history =
                HistoryStore(
                    object : HistoryApi {
                        override suspend fun fetchHistory(): List<String> = if (expired) throw SessionExpired() else listOf("a")
                    },
                )
            val profile = ProfileStore { "ada" }
            val flag = sessionExpired(AppStore(), history, profile)
            history.hydration.hydrate(this)
            profile.hydration.hydrate(this)
            history.hydration.awaitSettled()
            profile.hydration.awaitSettled()
            assertEquals(true, flag.value)

            expired = false
            history.hydration.hydrate(this)
            assertEquals(Hydration.Hydrated, history.hydration.awaitSettled())
            assertEquals(false, flag.value)
        }
    }
}
