// Twin of holdfast-coroutines/README.md's hydration example. The test drives
// it: two screen entries seed and fetch once.
package com.vynatix.holdfast.snippets.twins.coroutinesreadmehydration

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.StateTag
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.coroutines.Hydration
import com.vynatix.holdfast.coroutines.hydrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals

// Scaffold: the remote and the bundled seed data the example names.
interface FeedApi {
    suspend fun fetchFeed(): List<String>
}

object Seeds {
    val feed = listOf("bundled")
}

// DOC-SNIPPET holdfast-coroutines/README.md#6
@OptIn(ExperimentalStoreApi::class)
class FeedStore(api: FeedApi) : Store<FeedStore>() {
    val items by state(tags = setOf(StateTag.Remote)) { emptyList<String>() }
    val hydration =
        hydrator {
            base { items mutate Seeds.feed }                        // seeded in one transaction
            refresh { api.fetchFeed() } adopt { fetched -> items mutate fetched }
        }
}

// On every screen entry: only the first call seeds and fetches.
@OptIn(ExperimentalStoreApi::class)
fun onScreenEntered(store: FeedStore, viewModelScope: CoroutineScope) {
    viewModelScope.launch { store.hydration.hydrate(viewModelScope) }
}
// DOC-SNIPPET-END

class CoroutinesReadmeHydrationTwin {
    @OptIn(ExperimentalStoreApi::class)
    @Test
    fun twoScreenEntriesSeedAndFetchOnce() {
        var fetches = 0
        val store =
            FeedStore(
                object : FeedApi {
                    override suspend fun fetchFeed(): List<String> {
                        fetches++
                        return listOf("fresh")
                    }
                },
            )
        runBlocking {
            onScreenEntered(store, this)
            onScreenEntered(store, this)
            yield()
            assertEquals(Hydration.Hydrated, store.hydration.awaitSettled())
        }
        assertEquals(1, fetches)
        assertEquals(listOf("fresh"), store.items.value)
    }
}
