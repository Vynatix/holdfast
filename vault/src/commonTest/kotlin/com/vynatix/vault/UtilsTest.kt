package com.vynatix.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TimestampTest {

    @Test
    fun timestampNowProducesParseableEpochMillis() {
        val a = Timestamp.now()
        val b = Timestamp.now()
        assertTrue(a.toString().toLongOrNull() != null)
        assertTrue(b.toString().toLongOrNull() != null)
    }

    @Test
    fun timestampNowIsMonotonicNonDecreasingAcrossManyCalls() {
        val samples = (1..1000).map { Timestamp.now().toString().toLong() }
        // Wall-clock can occasionally repeat (same millisecond) but must never go backwards.
        var prev = samples.first()
        for (current in samples) {
            assertTrue(
                current >= prev,
                "timestamp regressed from $prev to $current",
            )
            prev = current
        }
    }

    @Test
    fun timestampToStringRoundTripsThroughLongParse() {
        val now = Timestamp.now()
        val parsed = now.toString().toLong()
        // Parsed value must be a positive epoch-millis (post-1970).
        assertTrue(parsed > 0L, "epoch millis must be positive; got $parsed")
        assertEquals(parsed.toString(), now.toString())
    }
}

class UUIDTest {

    @Test
    fun uuidRandomReturnsUniqueValuesAcrossManyCalls() {
        val ids = (1..100).map { UUID.randomUUID().toString() }.toSet()
        assertEquals(100, ids.size, "expected 100 unique UUIDs across 100 randomUUID() calls")
    }

    @Test
    fun uuidFormatHasFourDashSeparatorsAndStandardLength() {
        val uuid = UUID.randomUUID().toString()
        assertEquals(4, uuid.count { it == '-' })
        assertEquals(36, uuid.length)
    }

    @Test
    fun uuidVersionAndVariantBitsAreSetAccordingToRfc4122v4() {
        // Generate enough samples to trip on any deviation
        repeat(50) {
            val uuid = UUID.randomUUID().toString()
            // Format: xxxxxxxx-xxxx-Mxxx-Nxxx-xxxxxxxxxxxx
            //                        ^      ^
            //                   version  variant
            // Version 4: byte 6 high nibble == 0x4 → char index 14 must be '4'
            assertEquals('4', uuid[14], "UUID version nibble must be 4 (random); uuid=$uuid")
            // Variant RFC 4122: byte 8 high two bits == 10 → char index 19 must be in 8..b
            val variantChar = uuid[19]
            assertTrue(
                variantChar in setOf('8', '9', 'a', 'b', 'A', 'B'),
                "UUID variant nibble must be 8/9/a/b (RFC 4122); got '$variantChar' in $uuid",
            )
        }
    }
}
