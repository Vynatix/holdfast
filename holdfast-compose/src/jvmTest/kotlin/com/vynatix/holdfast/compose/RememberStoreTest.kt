package com.vynatix.holdfast.compose

import androidx.compose.runtime.mutableStateOf
import com.vynatix.holdfast.Store
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class OwnedStore : Store<OwnedStore>() {
    val n by state { 0 }
}

/**
 * The composition-owned store entrypoint (ROADMAP 0.6.0, E1): made once per
 * key, reused across recompositions that keep the key, disposed when the
 * Composable leaves the composition or the key changes — and never re-made
 * because the factory lambda itself is not the key.
 */
class RememberStoreTest {
    @Test
    fun theCompositionOwnsTheStoreItRemembers() =
        runTest {
            var made = 0
            val ui = HeadlessComposition(this)
            // An unrelated snapshot read drives recomposition without
            // touching the remembered store's inputs.
            val bump = mutableStateOf(0)
            var seen: OwnedStore? = null
            ui.setContent {
                val store =
                    rememberStore {
                        made++
                        OwnedStore()
                    }
                seen = store
                bump.value
            }
            val first = seen
            assertEquals(1, made)
            requireNotNull(first)
            assertFalse(first.isDisposed)

            bump.value = 1
            ui.settle()
            assertEquals(1, made, "a recomposition that keeps the key reuses the store")
            assertSame(first, seen, "and it is the same instance")

            ui.dispose()
            assertTrue(first.isDisposed, "leaving the composition disposes the store it owns")
        }

    @Test
    fun aKeyChangeDisposesTheOldStoreAndMakesANewOne() =
        runTest {
            var made = 0
            val ui = HeadlessComposition(this)
            val key = mutableStateOf("a")
            var seen: OwnedStore? = null
            ui.setContent {
                val store =
                    rememberStore(key.value) {
                        made++
                        OwnedStore()
                    }
                seen = store
            }
            val first = requireNotNull(seen)
            assertEquals(1, made)
            assertFalse(first.isDisposed)

            key.value = "b"
            ui.settle()
            assertEquals(2, made, "the key change re-runs the factory")
            assertTrue(first.isDisposed, "and disposes the store it made before")
            val second = requireNotNull(seen)
            assertNotSame(first, second)
            assertFalse(second.isDisposed)

            ui.dispose()
            assertTrue(second.isDisposed, "the replacement is disposed with the composition too")
        }

    @Test
    fun aFactoryReturningAnAlreadyDisposedStoreFailsFast() =
        runTest {
            val ui = HeadlessComposition(this)
            try {
                ui.setContent {
                    rememberStore {
                        val gone = OwnedStore()
                        gone.dispose()
                        gone
                    }
                }
                error("expected the disposed factory result to fail fast")
            } catch (e: IllegalStateException) {
                assertTrue(
                    "already-disposed" in (e.message ?: ""),
                    "teaching message, got: ${e.message}",
                )
            }
            ui.dispose()
        }
}
