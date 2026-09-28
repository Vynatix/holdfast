@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.testing.matcher

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.StateTag
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.keyedState
import com.vynatix.holdfast.testing.storeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

private const val KEY = "user@example.com"
private const val SECRET = "hunter2-5ECRET"

private class DraftsVault : Store<DraftsVault>() {
    val title by state { "t" }
    val drafts by keyedState<String, Int> { 0 }
    val tokens by keyedState<String, String>(tags = setOf(StateTag.Secret)) { "" }
}

/**
 * `shouldMatchSnapshotOf` compares keyed state families entry by entry
 * (issue #20, R7): a snapshot lists a family under its name, which
 * `Store.getState` never resolves, so the matcher reads the families
 * themselves — and its failure lines never name a key, nor a Secret family's
 * value.
 */
class KeyedSnapshotMatcherTest {
    @Test
    fun identicalFamiliesMatch() =
        storeTest {
            val mine = track(DraftsVault())
            val other = DraftsVault()
            mine.action { drafts[KEY] mutate 1 }.shouldBeSuccess()
            other.action { drafts[KEY] mutate 1 }

            mine shouldMatchSnapshotOf other
        }

    @Test
    fun familiesWithDifferentKeysFailWithoutNamingAKey() =
        storeTest {
            val mine = track(DraftsVault())
            val other = DraftsVault()
            mine.action { drafts[KEY] mutate 1 }.shouldBeSuccess()

            val failure = assertFailsWith<AssertionError> { mine shouldMatchSnapshotOf other }

            val message = failure.message.orEmpty()
            assertContains(message, "drafts: keyed entries differ (this has 1 entries, other has 0; keys withheld)")
            assertFalse(KEY in message, message)
        }

    @Test
    fun familiesWithTheSameKeysButDifferentValuesFail() =
        storeTest {
            val mine = track(DraftsVault())
            val other = DraftsVault()
            mine.action { drafts[KEY] mutate 1 }.shouldBeSuccess()
            other.action { drafts[KEY] mutate 2 }

            val failure = assertFailsWith<AssertionError> { mine shouldMatchSnapshotOf other }

            val message = failure.message.orEmpty()
            assertContains(message, "drafts: an entry's value differs (key withheld): this=1 other=2")
            assertFalse(KEY in message, message)
        }

    @Test
    fun aSecretFamilysMismatchShowsNeitherKeyNorValue() =
        storeTest {
            val mine = track(DraftsVault())
            val other = DraftsVault()
            mine.action { tokens[KEY] mutate SECRET }.shouldBeSuccess()
            other.action { tokens[KEY] mutate "other" }

            val failure = assertFailsWith<AssertionError> { mine shouldMatchSnapshotOf other }

            val message = failure.message.orEmpty()
            assertContains(message, "tokens: does not match (a Secret state: values withheld)")
            assertFalse(KEY in message || SECRET in message || "other" in message.substringAfter("tokens"), message)
        }
}
