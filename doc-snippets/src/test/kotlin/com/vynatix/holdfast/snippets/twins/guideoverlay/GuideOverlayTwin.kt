// Twin of GUIDE §16.8 (the persisted overlay). The block is embedded at top
// level; the test runs the two processes its comments describe.
package com.vynatix.holdfast.snippets.twins.guideoverlay

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.StateTag
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreSnapshot
import com.vynatix.holdfast.bridge.StringCodec
import com.vynatix.holdfast.coroutines.Hydration
import com.vynatix.holdfast.coroutines.InMemorySuspendingKvStore
import com.vynatix.holdfast.coroutines.SuspendingKvStore
import com.vynatix.holdfast.coroutines.hydrator
import com.vynatix.holdfast.restore
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

// Scaffold: the bundled seed data the example restores.
@OptIn(ExperimentalStoreApi::class)
object Seeds {
    val composer: StoreSnapshot =
        StoreSnapshot.decode("""{"format":"holdfast.store","v":1,"schema":1,"states":{"draft":"Hello,"}}""")
}

// DOC-SNIPPET holdfast/GUIDE.md#80
interface InboxApi {
    suspend fun fetchInbox(): List<String>
}

@OptIn(ExperimentalStoreApi::class)
class ComposerStore(prefs: SuspendingKvStore, api: InboxApi) : Store<ComposerStore>() {
    val draft by state(codec = StringCodec, tags = setOf(StateTag.UserAuthored)) { "" }
    val inbox by state(tags = setOf(StateTag.Remote)) { emptyList<String>() }
    val hydration =
        hydrator {
            base { restore(Seeds.composer) }                    // bundled seed data, a draft included
            overlay(prefs, key = "composer.overlay")            // then what the user wrote, over it
            refresh { api.fetchInbox() } adopt { fetched -> inbox mutate fetched }
        }
}

// First run: no blob yet, so the seed is base { } alone.
//   composer.hydration.hydrate()                   // draft: "Hello," (bundled)
//   composer action { draft mutate "Dear Ada," }   // written under "composer.overlay" soon after
// After process death, a new ComposerStore on the same prefs:
//   composer.hydration.hydrate()                   // base restores "Hello,"; the overlay puts "Dear Ada," over it
// DOC-SNIPPET-END

class GuideOverlayTwin {
    private val api =
        object : InboxApi {
            override suspend fun fetchInbox() = listOf("mail")
        }

    @OptIn(ExperimentalStoreApi::class)
    @Test
    fun theOverlaySurvivesARestartAndWinsOverBase() =
        runBlocking {
            val prefs = InMemorySuspendingKvStore()

            val composer = ComposerStore(prefs, api)
            composer.bindToScope(this)
            composer.hydration.hydrate(this)
            assertEquals("Hello,", composer.draft.value)
            composer action { draft mutate "Dear Ada," }
            withTimeout(5.seconds) { while (prefs.get("composer.overlay") == null) delay(1) }
            assertEquals(Hydration.Hydrated, composer.hydration.awaitSettled())
            composer.dispose()

            val restarted = ComposerStore(prefs, api)
            restarted.bindToScope(this)
            restarted.hydration.hydrate(this)
            assertEquals("Dear Ada,", restarted.draft.value)
            assertEquals(Hydration.Hydrated, restarted.hydration.awaitSettled())
            assertEquals(listOf("mail"), restarted.inbox.value)
            restarted.dispose()
        }
}
