@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.cli

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.snapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiffTest {
    @Test
    fun identicalBodiesAreEqual() {
        val a = encodedFixture { visits mutate 3 }
        val b = encodedFixture { visits mutate 3 }
        val diff = diffSnapshots(decoded(a), decoded(b), equalByValue = canonicalForm(a) == canonicalForm(b))
        assertTrue(diff.equalByValue)
        val report = diffReport(diff)
        assertTrue("decoded values: equal" in report)
        assertTrue("state names: identical" in report)
    }

    @Test
    fun valueChangeDiffsRenderedLines() {
        val a =
            encodedFixture {
                title mutate "before"
                visits mutate 1
            }
        val b =
            encodedFixture {
                title mutate "after"
                visits mutate 1
            }
        val diff = diffSnapshots(decoded(a), decoded(b), equalByValue = canonicalForm(a) == canonicalForm(b))
        assertFalse(diff.equalByValue)
        val report = diffReport(diff)
        assertTrue("decoded values: differ" in report)
        assertTrue("− " in report, "report should show removed lines:\n$report")
        assertTrue("+ " in report, "report should show added lines:\n$report")
        assertTrue("before" in report)
        assertTrue("after" in report)
    }

    @Test
    fun addedAndRemovedStatesAreNamed() {
        val a = encodedFixture()
        val b = FixtureStoreWithToken().snapshot().encode()
        val diff = diffSnapshots(decoded(a), decoded(b), equalByValue = canonicalForm(a) != canonicalForm(b))
        assertEquals(expected = listOf("token"), actual = diff.statesAdded)
        assertTrue("visits" in diff.statesRemoved)
        assertTrue("apiKey" in diff.statesRemoved)
        val report = diffReport(diff)
        assertTrue("states added: token" in report)
        assertTrue("states removed:" in report)
    }

    @Test
    fun schemaDriftIsReported() {
        val a = encodedFixture()
        val b = VersionedStore().snapshot().encode()
        val diff = diffSnapshots(decoded(a), decoded(b), equalByValue = canonicalForm(a) == canonicalForm(b))
        val report = diffReport(diff)
        assertTrue("schema version: 1 → 2" in report)
    }

    @Test
    fun secretValuesNeverAppearInDiffOutput() {
        val a = encodedFixture { apiKey mutate "first-secret" }
        val b = encodedFixture { apiKey mutate "second-secret" }
        val diff = diffSnapshots(decoded(a), decoded(b), equalByValue = canonicalForm(a) == canonicalForm(b))
        val report = diffReport(diff)
        assertTrue("first-secret" !in report)
        assertTrue("second-secret" !in report)
        // Both bodies encode the secret as null, so the encodable projection is equal.
        assertTrue(diff.equalByValue)
    }

    @Test
    fun lineDiffWalksLongestCommonSubsequence() {
        val changes =
            lineDiff(
                listOf("alpha", "beta", "gamma"),
                listOf("alpha", "delta", "gamma"),
            )
        assertEquals(
            expected =
                listOf(
                    LineChange.Unchanged("alpha"),
                    LineChange.Removed("beta"),
                    LineChange.Added("delta"),
                    LineChange.Unchanged("gamma"),
                ),
            actual = changes,
        )
    }

    @Test
    fun lineDiffHandlesEmptySides() {
        assertEquals(
            expected = listOf(LineChange.Added("only")),
            actual = lineDiff(emptyList(), listOf("only")),
        )
        assertEquals(
            expected = listOf(LineChange.Removed("only")),
            actual = lineDiff(listOf("only"), emptyList()),
        )
    }
}
