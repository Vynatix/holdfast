package com.vynatix.holdfast.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val traceText =
    """
    {"v":1,"e":"txn-started","id":"t1","label":"Counter","parent":null,"savepoint":false,"frame":null}
    {"v":1,"e":"txn-ended","id":"t1","label":"Counter","parent":null,"status":"committed","dur_ns":12400,"states":["count"],"err":null}
    {"v":1,"e":"txn-started","id":"t2","label":"Counter","parent":"t1","savepoint":true,"frame":null}
    {"v":1,"e":"txn-ended","id":"t2","label":"Counter","parent":"t1","status":"committed","dur_ns":3100,"states":["title"],"err":null}
    {"v":1,"e":"txn-started","id":"t3","label":"Counter","parent":null,"savepoint":false,"frame":"f9"}
    {"v":1,"e":"txn-ended","id":"t3","label":"Counter","parent":null,"status":"rolled-back","dur_ns":500,"states":["count","notes[*]"],"err":"IllegalStateException: boom"}
    {"v":1,"e":"txn-started","id":"t4","label":"Other","parent":null,"savepoint":false,"frame":"f9"}
    {"v":1,"e":"txn-ended","id":"t4","label":"Other","parent":null,"status":"committed","dur_ns":900,"states":[],"err":null}
    {"v":1,"e":"txn-started","id":"t5","label":"Counter","parent":null,"savepoint":false,"frame":null}
    {"v":1,not json
    {"v":1,"e":"bogus","id":"t9"}
    """.trimIndent()

class TraceRenderTest {
    @Test
    fun parseMergesStartedAndEndedAndSkipsMalformed() {
        val trace = parseTrace(traceText)
        assertEquals(expected = 5, actual = trace.txns.size)
        assertEquals(expected = 2, actual = trace.malformedLines)
        assertEquals(expected = 11, actual = trace.totalLines)
        val t1 = trace.txns.first { it.id == "t1" }
        assertEquals(expected = "committed", actual = t1.status)
        assertEquals(expected = 12400L, actual = t1.durNs)
        assertEquals(expected = listOf("count"), actual = t1.states)
        assertTrue(!trace.txns.first { it.id == "t5" }.ended)
    }

    @Test
    fun renderShowsTreeStatusesFramesAndOrphans() {
        val report = renderTrace(parseTrace(traceText))
        assertTrue("trace: 11 lines (2 malformed skipped), 5 transactions, 1 rolled back" in report, report)
        assertTrue("✓ t1 (12.4µs) count" in report, report)
        assertTrue("✓ t2 (3.1µs) title" in report, report)
        assertTrue("✗ t3" in report, report)
        assertTrue("IllegalStateException: boom" in report, report)
        assertTrue("frames (cross-store):" in report, report)
        assertTrue("f9: Counter/t3, Other/t4" in report, report)
        assertTrue("orphans (started, never ended" in report, report)
        assertTrue("t5 (Counter)" in report, report)
    }

    @Test
    fun statsAggregatesCountsDurationsAndRollbackRate() {
        val report = statsTrace(parseTrace(traceText))
        assertTrue("stores: 2 (Counter, Other)" in report, report)
        assertTrue("transactions: 5 (3 committed, 1 rolled back, 1 savepoints)" in report, report)
        assertTrue("duration: total" in report, report)
        assertTrue("slowest:" in report, report)
        assertTrue("rollback rate: 25%" in report, report)
        assertTrue("malformed lines: 2" in report, report)
    }

    @Test
    fun formatsNanosAcrossScales() {
        assertEquals(expected = "980ns", actual = formatNanos(980))
        assertEquals(expected = "3.1µs", actual = formatNanos(3_100))
        assertEquals(expected = "31.0µs", actual = formatNanos(31_000))
        assertEquals(expected = "124µs", actual = formatNanos(124_000))
        assertEquals(expected = "2.5ms", actual = formatNanos(2_500_000))
        assertEquals(expected = "1.2s", actual = formatNanos(1_200_000_000))
    }
}
