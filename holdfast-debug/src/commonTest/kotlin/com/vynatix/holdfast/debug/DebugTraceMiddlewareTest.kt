@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast.debug

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.StateTag
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.bridge.StringCodec
import com.vynatix.holdfast.keyedState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalStoreApi::class)
private class TracedStore : Store<TracedStore>() {
    val count by state(initialize = { 0 })
    val apiKey by state(codec = StringCodec, tags = setOf(StateTag.Secret), initialize = { "" })

    val notes by keyedState<String, String> { key -> "note-$key" }
}

private fun idOf(line: String): String = line.substringAfter("\"id\":\"").substringBefore('"')

class DebugTraceMiddlewareTest {
    private val lines = mutableListOf<String>()

    private fun newStore(): TracedStore =
        TracedStore().apply {
            middlewares(DebugTraceMiddleware("Traced", TraceSink { line -> lines += line }))
        }

    @Test
    fun committedTransactionEmitsStartAndEnd() {
        val store = newStore()
        store action { count mutate 1 }
        assertEquals(expected = 2, actual = lines.size)
        val started = lines[0]
        val ended = lines[1]
        assertTrue("\"e\":\"txn-started\"" in started, started)
        assertTrue("\"label\":\"Traced\"" in started, started)
        assertTrue("\"savepoint\":false" in started, started)
        assertTrue("\"parent\":null" in started, started)
        assertTrue("\"e\":\"txn-ended\"" in ended, ended)
        assertTrue("\"status\":\"committed\"" in ended, ended)
        assertTrue("\"states\":[\"count\"]" in ended, ended)
        assertTrue("\"dur_ns\":" in ended, ended)
        assertTrue("\"err\":null" in ended, ended)
        assertEquals(expected = idOf(started), actual = idOf(ended))
    }

    @Test
    fun rolledBackTransactionCarriesTheError() {
        val store = newStore()
        val result =
            store action {
                count mutate 1
                error("boom")
            }
        assertTrue(result is com.vynatix.holdfast.TransactionResult.Error, "the fixture action must fail")
        val ended = lines.last()
        assertTrue("\"status\":\"rolled-back\"" in ended, ended)
        assertTrue("\"err\":\"IllegalStateException: boom\"" in ended, ended)
    }

    @Test
    fun savepointLinksItsParent() {
        val store = newStore()
        store action {
            count mutate 1
            action {
                count mutate 2
            }
        }
        val starts = lines.filter { "\"txn-started\"" in it }
        val ends = lines.filter { "\"txn-ended\"" in it }
        assertEquals(expected = 2, actual = starts.size)
        assertEquals(expected = 2, actual = ends.size)
        val outer = starts.first()
        val inner = starts.last()
        assertTrue("\"savepoint\":false" in outer, outer)
        assertTrue("\"savepoint\":true" in inner, inner)
        assertEquals(expected = idOf(outer), actual = inner.substringAfter("\"parent\":\"").substringBefore('"'))
    }

    @Test
    fun keyedEntryIsNamedByFamilyNeverByKey() {
        val store = newStore()
        store action { notes["k1"] mutate "alpha" }
        val ended = lines.last()
        assertTrue("\"states\":[\"notes[*]\"]" in ended, ended)
        assertTrue("k1" !in ended, "a keyed entry's key must never reach the trace: $ended")
    }

    @Test
    fun secretStateIsNamedButNeverValued() {
        val store = newStore()
        store action { apiKey mutate "hunter2" }
        val ended = lines.last()
        assertTrue("\"apiKey\"" in ended, ended)
        assertTrue("hunter2" !in ended, "a Secret value must never reach the trace: $ended")
    }

    @Test
    fun traceLinesAreValidJsonObjects() {
        val store = newStore()
        store action {
            count mutate 1
            apiKey mutate "with \"quotes\" and \\ backslash"
        }
        lines.forEach { line ->
            assertTrue(line.startsWith("{\"v\":1,"), line)
            assertTrue(line.endsWith("}"), line)
        }
        // The error field is where a user-thrown message would appear; here it stays null.
        assertTrue(lines.last().contains("\"err\":null"))
    }
}
