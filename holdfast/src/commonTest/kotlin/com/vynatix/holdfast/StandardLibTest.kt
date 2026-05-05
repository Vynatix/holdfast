package com.vynatix.holdfast

import com.vynatix.holdfast.bridge.BooleanCodec
import com.vynatix.holdfast.bridge.InMemoryKvStore
import com.vynatix.holdfast.bridge.IntCodec
import com.vynatix.holdfast.bridge.KvBridge
import com.vynatix.holdfast.bridge.LongCodec
import com.vynatix.holdfast.bridge.StringCodec
import com.vynatix.holdfast.middleware.LoggingMiddleware
import com.vynatix.holdfast.middleware.TimingMiddleware
import com.vynatix.holdfast.middleware.ValidationMiddleware
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class StdLibVault : Store<StdLibVault>() {
    val n by state { 0 }
    val s by state { "init" }
    val balance by state { 0L }
}

class LoggingMiddlewareTest {

    @Test
    fun loggingMiddlewareEmitsTwoLinesPerSuccessfulTransaction() {
        val v = StdLibVault()
        val log = mutableListOf<String>()
        v.middlewares(LoggingMiddleware("V", log::add))
        v action { n mutate 1 }
        assertEquals(2, log.size, "one started + one completed line per transaction")
        assertTrue(log[0].startsWith("V → "))
        assertTrue(log[1].startsWith("V ✓ "))
    }

    @Test
    fun loggingMiddlewareEmitsErrorLineOnFailedTransaction() {
        val v = StdLibVault()
        val log = mutableListOf<String>()
        v.middlewares(LoggingMiddleware("V", log::add))
        v action {
            n mutate 1
            error("simulated")
        }
        assertEquals(2, log.size)
        assertTrue(log[0].startsWith("V → "))
        assertTrue(log[1].startsWith("V ✗ "))
        assertTrue(log[1].contains("simulated"))
    }

    @Test
    fun loggingMiddlewareTagsSavepointWithParentId() {
        val v = StdLibVault()
        val log = mutableListOf<String>()
        v.middlewares(LoggingMiddleware("V", log::add))
        v action {
            v action { n mutate 1 }
        }
        // 4 lines: outer-started, inner-started (with parent suffix), inner-completed, outer-completed
        assertEquals(4, log.size, "outer + inner = 4 lines, log=$log")
        assertTrue(log[1].contains("savepoint of"), "inner's started line names the parent: ${log[1]}")
    }
}

class TimingMiddlewareTest {

    @Test
    fun timingMiddlewareReportsElapsedMsForSuccessfulTransaction() {
        val v = StdLibVault()
        val results = mutableListOf<Triple<String, TransactionStatus, Long>>()
        v.middlewares(TimingMiddleware { id, status, ms -> results.add(Triple(id, status, ms)) })
        v action { n mutate 1 }
        assertEquals(1, results.size)
        val (_, status, ms) = results[0]
        assertEquals(TransactionStatus.Committed, status)
        assertTrue(ms >= 0, "elapsedMs is non-negative; got $ms")
    }

    @Test
    fun timingMiddlewareReportsRolledBackStatusOnError() {
        val v = StdLibVault()
        val results = mutableListOf<Triple<String, TransactionStatus, Long>>()
        v.middlewares(TimingMiddleware { id, status, ms -> results.add(Triple(id, status, ms)) })
        v action {
            n mutate 1
            error("boom")
        }
        assertEquals(1, results.size)
        assertEquals(TransactionStatus.RolledBack, results[0].second)
    }
}

class ValidationMiddlewareTest {

    @Test
    fun validationMiddlewareRollsBackWhenCheckThrows() {
        val v = StdLibVault()
        v.middlewares(
            ValidationMiddleware<StdLibVault> {
                require(balance.value >= 0) { "balance cannot be negative" }
            },
        )
        val r = v action { balance mutate -100 }
        assertIs<TransactionResult.Error>(r)
        assertEquals(TransactionStatus.RolledBack, r.transaction.status)
        assertEquals(0L, v.balance.value, "rolled back to initial")
    }

    @Test
    fun validationMiddlewarePassesThroughWhenCheckSucceeds() {
        val v = StdLibVault()
        v.middlewares(
            ValidationMiddleware<StdLibVault> {
                require(balance.value >= 0)
            },
        )
        val r = v action { balance mutate 100 }
        assertIs<TransactionResult.Success<*>>(r)
        assertEquals(100L, v.balance.value)
    }
}

class CodecTest {

    @Test fun stringCodecRoundTripsLosslessly() {
        val s = "hello, world!"
        assertEquals(s, StringCodec.decode(StringCodec.encode(s)))
    }

    @Test fun longCodecRoundTrips() {
        val n = 12_345_678L
        assertEquals(n, LongCodec.decode(LongCodec.encode(n)))
    }

    @Test fun intCodecRoundTrips() {
        val n = -42
        assertEquals(n, IntCodec.decode(IntCodec.encode(n)))
    }

    @Test fun booleanCodecRoundTrips() {
        assertEquals(true, BooleanCodec.decode(BooleanCodec.encode(true)))
        assertEquals(false, BooleanCodec.decode(BooleanCodec.encode(false)))
    }
}

class InMemoryKvStoreTest {

    @Test fun putThenGetReturnsTheValue() {
        val kv = InMemoryKvStore()
        kv.put("k", "v")
        assertEquals("v", kv.get("k"))
    }

    @Test fun getOnMissingKeyReturnsNull() {
        val kv = InMemoryKvStore()
        assertNull(kv.get("missing"))
    }

    @Test fun removeDropsTheKey() {
        val kv = InMemoryKvStore()
        kv.put("k", "v")
        kv.remove("k")
        assertNull(kv.get("k"))
    }

    @Test fun snapshotReturnsAnImmutableCopy() {
        val kv = InMemoryKvStore()
        kv.put("a", "1")
        kv.put("b", "2")
        val s = kv.snapshot()
        kv.put("c", "3")
        assertEquals(2, s.size, "snapshot is detached from subsequent puts")
    }
}

class KvBridgeTest {

    @Test
    fun kvBridgePersistsValueOnCommit() {
        val v = StdLibVault()
        val kv = InMemoryKvStore()
        v { balance bridge KvBridge(kv, "k", LongCodec) }
        v action { balance mutate 1234 }
        assertEquals("1234", kv.get("k"))
    }

    @Test
    fun kvBridgeHydratesStateFromStoreOnAttach() {
        val kv = InMemoryKvStore()
        kv.put("k", "9999")
        val v = StdLibVault()
        v { balance bridge KvBridge(kv, "k", LongCodec) }
        assertEquals(9999L, v.balance.value, "attach replayed persisted value")
    }

    @Test
    fun kvBridgeRoundTripsAcrossSimulatedRestart() {
        val kv = InMemoryKvStore()
        // Session 1: write
        run {
            val v = StdLibVault()
            v { balance bridge KvBridge(kv, "balance", LongCodec) }
            v action { balance mutate 42 }
        }
        // Session 2: read (fresh vault, same store)
        val v2 = StdLibVault()
        v2 { balance bridge KvBridge(kv, "balance", LongCodec) }
        assertEquals(42L, v2.balance.value)
    }

    @Test
    fun kvBridgeRollbackDoesNotPersist() {
        val v = StdLibVault()
        val kv = InMemoryKvStore()
        v { balance bridge KvBridge(kv, "k", LongCodec) }
        val r = v action {
            balance mutate 999
            error("rollback")
        }
        assertIs<TransactionResult.Error>(r)
        assertNull(kv.get("k"), "no commit → no publish")
    }

    @Test
    fun kvBridgeStringCodec() {
        val v = StdLibVault()
        val kv = InMemoryKvStore()
        v { s bridge KvBridge(kv, "s", StringCodec) }
        v action { s mutate "persisted" }
        assertEquals("persisted", kv.get("s"))

        // Round trip via fresh vault.
        val v2 = StdLibVault()
        v2 { s bridge KvBridge(kv, "s", StringCodec) }
        assertEquals("persisted", v2.s.value)
    }

    @Test
    fun kvBridgeWithMissingKeyHydratesToInitialValue() {
        val v = StdLibVault()
        val kv = InMemoryKvStore()
        // No prior put; attach attempts replay but kv.get returns null → state stays at initial.
        v { balance bridge KvBridge(kv, "k", LongCodec) }
        assertEquals(0L, v.balance.value)
    }

    @Test
    fun kvBridgeTransactionMetadataMakesValueRecoverableAfterCorruption() {
        // Sanity: a malformed encoded value falls through (kv.get returns string,
        // codec.decode throws → caught by runCatching in observe → state stays initial).
        val kv = InMemoryKvStore()
        kv.put("k", "not-a-long")
        val v = StdLibVault()
        v { balance bridge KvBridge(kv, "k", LongCodec) }
        assertEquals(0L, v.balance.value, "corrupt persisted value must not crash; state stays at initial")
    }
}

class StandardLibIntegrationTest {

    @Test
    fun loggingPlusTimingPlusValidationCoexistOnSameVault() {
        val v = StdLibVault()
        val log = mutableListOf<String>()
        val timings = mutableListOf<Long>()
        // Order matters: LAST is outermost. Logging is placed last so it brackets
        // the whole chain and its onError sees validation failures.
        v.middlewares(
            ValidationMiddleware<StdLibVault> { require(n.value >= 0) },
            TimingMiddleware { _, _, ms -> timings.add(ms) },
            LoggingMiddleware("V", log::add),
        )
        // Successful action → all three middlewares fire.
        val ok = v action { n mutate 5 }
        assertIs<TransactionResult.Success<*>>(ok)
        assertEquals(2, log.size)
        assertEquals(1, timings.size)
        assertNotNull(timings[0])

        // Validation failure → outermost (Logging) sees the error.
        val r = v action { n mutate -1 }
        assertIs<TransactionResult.Error>(r)
        assertTrue(log.any { it.contains("✗") }, "logging middleware sees the error: $log")
        assertEquals(2, timings.size, "timing middleware records the rolled-back transaction too")
    }
}
