package com.vynatix.holdfast.stress

import com.vynatix.holdfast.bridge.FileSystemKvStore
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `FileSystemKvStore` (JVM actual: tempfile + `ATOMIC_MOVE` rename per `put`) under real
 * thread contention against a scratch temp directory.
 *
 * Pinned claims:
 *  - per-file write atomicity: no reader — `get` or `snapshot` — ever observes a torn or
 *    cross-key value; every written value carries its own checksum and key tag, so any
 *    partial or mixed read is detected exactly;
 *  - the happy path leaks no `.tmp-` work files once writers quiesce;
 *  - values persist byte-for-byte across store instances sharing one root directory.
 *
 * Characterized defects (see the BUG-tagged tests below):
 *  - `snapshot()` reads each listed file with no guard (unlike `get`, whose read is wrapped
 *    in `runCatching`), so it can throw when racing `remove`;
 *  - key encoding collides for characters above 0xFF, silently clobbering across keys.
 */
class FileSystemKvStoreStressTest {
    /**
     * Run [body] on a daemon worker and fail — rather than hang — if it does not finish
     * within [seconds]. A wedged filesystem loop would otherwise burn the 10-minute
     * test-task cap before reporting anything.
     */
    private fun completesWithin(
        seconds: Long,
        what: String,
        body: () -> Unit,
    ) {
        val done = CountDownLatch(1)
        val thrown = AtomicReference<Throwable?>(null)
        val worker =
            Thread {
                try {
                    body()
                } catch (e: Throwable) {
                    thrown.set(e)
                } finally {
                    done.countDown()
                }
            }
        worker.isDaemon = true
        worker.name = "fs-kv-stress-probe"
        worker.start()
        if (!done.await(seconds, TimeUnit.SECONDS)) {
            fail("$what did not complete within ${seconds}s — a filesystem op is stuck")
        }
        thrown.get()?.let { throw it }
    }

    /** Start [threadCount] named daemon workers, release them together, and join them all. */
    private fun runWorkers(
        threadCount: Int,
        namePrefix: String,
        body: (Int) -> Unit,
    ) {
        val start = CountDownLatch(1)
        val firstFailure = AtomicReference<Throwable?>(null)
        val workers =
            (0 until threadCount).map { t ->
                Thread {
                    try {
                        start.await(10, TimeUnit.SECONDS)
                        body(t)
                    } catch (e: Throwable) {
                        firstFailure.compareAndSet(null, e)
                    }
                }.apply {
                    isDaemon = true
                    name = "$namePrefix-$t"
                }
            }
        workers.forEach { it.start() }
        start.countDown()
        workers.forEach { it.join() }
        firstFailure.get()?.let { throw it }
    }

    private fun newRoot(): File = Files.createTempDirectory("holdfast-fs-stress").toFile()

    /** A value that proves its own integrity: `<key>:<seq>#<hashCode of "<key>:<seq>">`. */
    private fun sealedValue(
        key: String,
        seq: Int,
    ): String {
        val payload = "$key:$seq"
        return "$payload#${payload.hashCode()}"
    }

    /** True iff [value] is a complete, untorn write that some `put` targeted at [key]. */
    private fun isSealedFor(
        key: String,
        value: String,
    ): Boolean {
        val idx = value.lastIndexOf('#')
        if (idx <= 0) return false
        val payload = value.substring(0, idx)
        return value.substring(idx + 1) == payload.hashCode().toString() && payload.startsWith("$key:")
    }

    /**
     * 6 threads x 300 seeded ops (put/get/remove/snapshot) over 20 shared keys. The
     * tempfile + `ATOMIC_MOVE` claim means every non-null read must be a complete value
     * written by some `put` for that exact key — checksum plus key tag verify both halves.
     *
     * `snapshot()` calls made while removers run may throw through the known unguarded
     * `readText` window (see the BUG test below); those are counted and tolerated here so
     * this pin stays deterministic. Integrity of every snapshot that DOES return is
     * asserted exactly.
     */
    @Test
    fun `hurricane of put get remove snapshot never yields a torn or cross-key value`() {
        val root = newRoot()
        try {
            val kv = FileSystemKvStore(root.path)
            val keys = List(20) { "key-" + it.toString().padStart(2, '0') }
            val integrityFailures = AtomicInteger(0)
            val firstFailure = AtomicReference<String?>(null)
            val snapshotCrashes = AtomicInteger(0)

            fun recordIfTorn(
                key: String,
                value: String,
                where: String,
            ) {
                if (!isSealedFor(key, value)) {
                    integrityFailures.incrementAndGet()
                    firstFailure.compareAndSet(null, "$where($key) -> $value")
                }
            }

            completesWithin(60, "6x300 put/get/remove/snapshot hurricane") {
                runWorkers(6, "fs-hurricane") { t ->
                    val rnd = Random(1_000 + t)
                    for (i in 1..300) {
                        val key = keys[rnd.nextInt(keys.size)]
                        when (rnd.nextInt(10)) {
                            in 0..4 -> kv.put(key, sealedValue(key, t * 1_000 + i))
                            in 5..6 -> {
                                val read = kv.get(key)
                                if (read != null) recordIfTorn(key, read, "get")
                            }
                            in 7..8 -> kv.remove(key)
                            else -> {
                                runCatching { kv.snapshot() }
                                    .onFailure { snapshotCrashes.incrementAndGet() }
                                    .onSuccess { snap ->
                                        for ((k, v) in snap) recordIfTorn(k, v, "snapshot")
                                    }
                            }
                        }
                    }
                }
            }

            assertEquals(
                0,
                integrityFailures.get(),
                "atomic-move immunity violated — first torn/cross-key read: ${firstFailure.get()}",
            )

            // Quiesced: no concurrent removes, so snapshot cannot race and must be fully valid.
            for ((k, v) in kv.snapshot()) {
                assertTrue(isSealedFor(k, v), "settled snapshot[$k] holds a torn value: $v")
            }
            for (key in keys) {
                val read = kv.get(key)
                if (read != null) {
                    assertTrue(isSealedFor(key, read), "settled get($key) holds a torn value: $read")
                }
            }

            // Every put succeeded (writable root), so every work file must have been renamed away.
            val leaked = root.listFiles().orEmpty().filter { it.name.startsWith(".tmp-") }
            assertTrue(leaked.isEmpty(), "happy-path puts must not leak temp files: $leaked")

            // Liveness sentinel: the store still round-trips after the storm.
            kv.put("sentinel", sealedValue("sentinel", -1))
            assertEquals(sealedValue("sentinel", -1), kv.get("sentinel"))
        } finally {
            root.deleteRecursively()
        }
    }

    /**
     * BUG (jvmAndAndroidMain `FileSystemKvStore.snapshot`, lines 34-39): the per-file read
     * is an unguarded `f.readText(...)` after `listFiles`, unlike `get` which wraps its
     * read in `runCatching`. A file deleted by a concurrent `remove` between the listing
     * and the read makes `snapshot()` throw `FileNotFoundException` instead of skipping
     * the vanished entry.
     *
     * The crash is a race and cannot be asserted deterministically, so this test TOLERATES
     * it (counted, not asserted) while actively hammering the window: a writer cycles
     * put+remove on one key 400 times while a snapshotter loops 400 `snapshot()` calls.
     * What IS pinned: every snapshot that returns carries only complete checksummed
     * values, and never-removed keys survive untouched. When `snapshot()` guards its
     * per-file read, add `assertEquals(0, crashes.get())` to close the window for good.
     */
    @Test
    fun `snapshot racing remove keeps integrity for every snapshot that returns`() {
        val root = newRoot()
        try {
            val kv = FileSystemKvStore(root.path)
            // Stable keys keep every snapshot non-trivial; they are never removed.
            val stableKeys = List(5) { "stable-$it" }
            for ((i, k) in stableKeys.withIndex()) kv.put(k, sealedValue(k, i))
            val racedKey = "raced"

            val crashes = AtomicInteger(0)
            val integrityFailures = AtomicInteger(0)
            val firstFailure = AtomicReference<String?>(null)

            completesWithin(60, "snapshot-vs-remove race loop") {
                runWorkers(2, "fs-snap-race") { t ->
                    if (t == 0) {
                        for (i in 1..400) {
                            kv.put(racedKey, sealedValue(racedKey, i))
                            kv.remove(racedKey)
                        }
                    } else {
                        for (i in 1..400) {
                            runCatching { kv.snapshot() }
                                .onFailure { crashes.incrementAndGet() }
                                .onSuccess { snap ->
                                    for ((k, v) in snap) {
                                        if (!isSealedFor(k, v)) {
                                            integrityFailures.incrementAndGet()
                                            firstFailure.compareAndSet(null, "snapshot[$k] -> $v")
                                        }
                                    }
                                }
                        }
                    }
                }
            }

            assertEquals(
                0,
                integrityFailures.get(),
                "a successful snapshot returned a torn value: ${firstFailure.get()}",
            )
            for ((i, k) in stableKeys.withIndex()) {
                assertEquals(sealedValue(k, i), kv.get(k), "stable key $k must be untouched by the race")
            }
        } finally {
            root.deleteRecursively()
        }
    }

    /**
     * BUG (jvmAndAndroidMain `FileSystemKvStore.encodeKey`/`decodeKey`): unsafe characters
     * encode as `'%' + code.toString(16).padStart(2, '0')`, but `padStart` is a MINIMUM —
     * code points above 0xFF emit 3-5 hex digits while `decodeKey` consumes exactly two.
     * The euro sign U+20AC encodes to `%20ac`; the three-character key `" ac"` (space,
     * 'a', 'c') encodes to `%20` + `a` + `c` = `%20ac` as well. Two distinct keys share
     * one file, so the later put clobbers the earlier, `get` on either key returns the
     * survivor, `remove` of one key deletes the other's data, and `snapshot()` always
     * decodes the shared filename as `" ac"` — the euro key can never be listed.
     *
     * Expected once encoding is fixed (unambiguous multi-digit escapes): each key keeps
     * its own value, `snapshot()` lists both, and `remove` only affects its own key —
     * flip the clobber/list/remove assertions then.
     */
    @Test
    fun `distinct keys above 0xFF collide onto one file and clobber each other`() {
        val root = newRoot()
        try {
            val kv = FileSystemKvStore(root.path)
            val euro = "€"
            val spaceAc = " ac"

            kv.put(euro, "euro-value")
            assertEquals("euro-value", kv.get(euro), "sanity: the euro key round-trips on its own")

            // BUG: " ac" resolves to the same "%20ac" file, overwriting the euro key's value.
            kv.put(spaceAc, "space-ac-value")
            assertEquals("space-ac-value", kv.get(spaceAc))
            assertEquals(
                "space-ac-value",
                kv.get(euro),
                "characterized collision: get(euro) returns the \" ac\" value; expect \"euro-value\" once fixed",
            )

            // decodeKey consumes exactly two hex digits, so the shared file always lists as " ac".
            assertEquals(mapOf(spaceAc to "space-ac-value"), kv.snapshot())

            // Cross-key data loss: removing the euro key deletes " ac"'s data too.
            kv.remove(euro)
            assertNull(kv.get(spaceAc), "characterized collision: remove(euro) must currently take \" ac\" with it")
        } finally {
            root.deleteRecursively()
        }
    }

    /**
     * Persistence pin: a second instance over the same root sees every value byte-for-byte
     * — nothing is cached per-instance, and the settled directory decodes cleanly.
     */
    @Test
    fun `fifty checksummed keys survive a store re-instantiation byte-for-byte`() {
        val root = newRoot()
        try {
            val writer = FileSystemKvStore(root.path)
            val expected =
                (0 until 50).associate { i ->
                    val key = "persist-" + i.toString().padStart(2, '0')
                    key to sealedValue(key, i)
                }
            for ((k, v) in expected) writer.put(k, v)

            val reborn = FileSystemKvStore(root.path)
            for ((k, v) in expected) {
                assertEquals(v, reborn.get(k), "key $k must round-trip through a fresh instance")
            }
            assertEquals(expected, reborn.snapshot(), "a fresh instance's snapshot must be exactly the written map")
        } finally {
            root.deleteRecursively()
        }
    }
}
