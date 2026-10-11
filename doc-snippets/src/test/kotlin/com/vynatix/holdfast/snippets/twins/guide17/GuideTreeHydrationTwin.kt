// Twin of GUIDE §17.11 (hydrating a tree). Self-contained: its own parent
// store, since hydration needs stores with hydrators. The test drives the
// block and asserts the output its comments claim.
@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.snippets.twins.guide17

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.StateTag
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.coroutines.hydrateAll
import com.vynatix.holdfast.coroutines.hydrator
import com.vynatix.holdfast.snippets.capturePrintln
import com.vynatix.holdfast.tree.store
import com.vynatix.holdfast.tree.tree
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

// DOC-SNIPPET holdfast/GUIDE.md#88
class FeedStore(private val remote: suspend () -> List<String>) : Store<FeedStore>() {
    val items by state(tags = setOf(StateTag.Remote)) { emptyList<String>() }
    val hydration = hydrator {
        base { items mutate listOf("cached") }
        refresh { remote() } adopt { fetched -> items mutate fetched }
    }
}

class SettingsOnlyStore : Store<SettingsOnlyStore>() {
    val theme by state { "light" }
}

object Feeds : Store<Feeds>() {
    val news by store { FeedStore { listOf("headline") } }
    val sports by store { FeedStore { error("offline") } }
    val settings by store { SettingsOnlyStore() }
}

suspend fun hydrateTheTree() {
    val report = Feeds.tree.hydrateAll()                                 // the parent, then every child, in lock order
    println(report.entries.map { "${it.node.name}: ${it.outcome}" })     // "[Feeds: NoHydrator, news: Ran(Hydrated), sports: Ran(Failed(cause=java.lang.IllegalStateException: offline)), settings: NoHydrator]"
    println(report.isHealthy to report.failed.map { it.node.name })      // "(false, [sports])"
    println(Feeds.news.items.value)                                      // "[headline]"
    println(Feeds.sports.items.value)                                    // "[cached]": the seed stood, the refresh failed
    println(Feeds.tree.hydrateAll().failed.map { it.node.name })         // "[sports]": idempotent for news, a retry for sports
}
// DOC-SNIPPET-END

class GuideTreeHydrationTwin {
    @Test
    fun hydrateTheTreePrintsWhatItsCommentsClaim() {
        val printed = capturePrintln { runBlocking { hydrateTheTree() } }
        assertEquals(
            listOf(
                "[Feeds: NoHydrator, news: Ran(Hydrated), sports: Ran(Failed(cause=java.lang.IllegalStateException: offline)), " +
                    "settings: NoHydrator]",
                "(false, [sports])",
                "[headline]",
                "[cached]",
                "[sports]",
            ),
            printed,
        )
    }
}
