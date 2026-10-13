@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.cli

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.StateTag
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.bridge.IntCodec
import com.vynatix.holdfast.bridge.StringCodec
import com.vynatix.holdfast.keyedState
import com.vynatix.holdfast.snapshot

/**
 * Real stores whose encodings are the CLI's golden inputs. Everything the CLI
 * reports on comes from these bodies through the actual public encode(), so a
 * library-side format change fails these tests, not silently drifts.
 */
internal class FixtureStore : Store<FixtureStore>() {
    val title by state(codec = StringCodec, initialize = { "untitled" })
    val visits by state(codec = IntCodec, initialize = { 0 })
    val apiKey by state(codec = StringCodec, tags = setOf(StateTag.Secret), initialize = { "" })
    val remoteNote by state(codec = StringCodec, tags = setOf(StateTag.Remote), initialize = { "" })
    val cache by state(initialize = { "never encoded" })

    val notes by keyedState<String, String>(codec = StringCodec, keyCodec = StringCodec) { key -> "note-$key" }
}

/** A second store shape, to exercise added/removed state names in diffs. */
internal class FixtureStoreWithToken : Store<FixtureStoreWithToken>() {
    val title by state(codec = StringCodec, initialize = { "untitled" })
    val token by state(codec = StringCodec, initialize = { "" })
}

/** Encode a fixture with [transform] applied inside one action. */
internal fun encodedFixture(
    includeRemote: Boolean = false,
    transform: FixtureStore.() -> Unit = {},
): String {
    val store = FixtureStore()
    store action transform
    return store.snapshot().encode(includeRemote = includeRemote)
}
