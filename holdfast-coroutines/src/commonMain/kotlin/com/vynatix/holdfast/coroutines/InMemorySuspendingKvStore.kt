package com.vynatix.holdfast.coroutines

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Thread-safe in-memory [SuspendingKvStore]. Suitable for tests, dev, or as the
 * "session" tier in a multi-tier persistence stack.
 *
 * Each operation yields via `delay(0)` before completing — a deliberate choice that
 * makes the implementation honestly suspending. Consumer code that synchronously
 * expects results (off the suspend path) will surface the bug here rather than in
 * production with a real async backend.
 *
 * Concurrent operations are serialized by a [Mutex]; no writes are lost.
 */
class InMemorySuspendingKvStore(
    initial: Map<String, String> = emptyMap(),
) : SuspendingKvStore {
    private val mutex = Mutex()
    private val map = initial.toMutableMap()

    override suspend fun get(key: String): String? =
        mutex.withLock {
            delay(0)
            map[key]
        }

    override suspend fun put(
        key: String,
        value: String,
    ): Unit =
        mutex.withLock {
            delay(0)
            map[key] = value
        }

    override suspend fun remove(key: String): Unit =
        mutex.withLock {
            delay(0)
            map.remove(key)
        }

    override suspend fun snapshot(): Map<String, String> =
        mutex.withLock {
            delay(0)
            map.toMap()
        }
}
