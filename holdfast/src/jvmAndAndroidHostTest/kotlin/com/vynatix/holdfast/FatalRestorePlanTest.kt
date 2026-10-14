@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast

import com.vynatix.holdfast.bridge.IntCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// Issue #61: a fatal error thrown while a restore PLANS — a target's
// initializer, the schema version, `migrate`, a value or key codec — is thrown
// from `restore` before its action opens: never carried into the action and
// returned as an Error, never an issue BestEffort tolerates, never reported.

/** An Int codec that throws [decodeThrows] while set. */
private class FrpIntCodec : StateCodec<Int> {
    @Volatile var decodeThrows: Throwable? = null

    override fun encode(value: Int): String = value.toString()

    override fun decode(string: String): Int {
        decodeThrows?.let { throw it }
        return string.toInt()
    }
}

/** A String key codec that throws [decodeThrows] while set. */
private class FrpKeyCodec : StateCodec<String> {
    @Volatile var decodeThrows: Throwable? = null

    override fun encode(value: String): String = value

    override fun decode(string: String): String {
        decodeThrows?.let { throw it }
        return string
    }
}

/** [lazy]'s initializer throws [initThrows] when given; nothing reads it before a restore. */
private class FrpStore(
    private val initThrows: Throwable? = null,
) : Store<FrpStore>() {
    val codec = FrpIntCodec()
    val keyCodec = FrpKeyCodec()
    val valueCodec = FrpIntCodec()

    val touched by state(codec = IntCodec) { 0 }
    val n by state(codec = codec) { 0 }
    val lazy by state(codec = IntCodec) {
        initThrows?.let { throw it }
        7
    }
    val docs by keyedState<String, Int>(codec = valueCodec, keyCodec = keyCodec) { -1 }
}

/** Schema version 1, unless [versionThrows] is set: then reading it throws. */
private class FrpVersionedStore :
    Store<FrpVersionedStore>(),
    SchemaVersioned {
    @Volatile var versionThrows: Throwable? = null

    val n by state(codec = IntCodec) { 0 }

    override val schemaVersion: Int
        get() {
            versionThrows?.let { throw it }
            return 1
        }

    override fun migrate(
        from: Int,
        view: EncodedSnapshotView,
    ) {}
}

/** Schema version 2; [migrate] keeps the view it was given, then throws [migrateThrows] when set. */
private class FrpMigratingStore :
    Store<FrpMigratingStore>(),
    SchemaVersioned {
    @Volatile var migrateThrows: Throwable? = null
    var stashed: EncodedSnapshotView? = null

    val n by state(codec = IntCodec) { 0 }

    override val schemaVersion: Int get() = 2

    override fun migrate(
        from: Int,
        view: EncodedSnapshotView,
    ) {
        stashed = view
        migrateThrows?.let { throw it }
    }
}

/** Records the id of every transaction its store starts. */
private class FrpStarted<S : Store<S>> : Middleware<S>() {
    val ids = mutableListOf<String>()

    override fun onTransactionStarted(context: MiddlewareContext<S>) {
        ids += context.transaction.id
    }
}

private fun Store<*>.recordUncaught(): List<Throwable> {
    val seen = mutableListOf<Throwable>()
    uncaughtObserverHandler = { seen += it }
    return seen
}

private fun assertThreadClean() {
    assertNull(SettleScopes.current(), "no settle scope left open on this thread")
    assertNull(FrameMarkers.current(), "no frame marker left on this thread")
}

private fun FrpStore.assertNotWedged() {
    assertNull(activeTransaction)
    assertThreadClean()
    val before = touched.value
    action { touched mutate before + 1 }.getOrThrow()
    assertEquals(before + 1, touched.value)
}

/** A store of schema 1 holding n = 5, written by hand: what an earlier release encoded. */
private const val V1_TEXT = """{"format":"holdfast.store","v":1,"schema":1,"states":{"n":"5"},"skipped":[]}"""

class FatalRestorePlanTest {
    @Test
    fun aTargetInitializersFatalErrorIsThrownBeforeTheRestoreOpens() {
        val source = FrpStore()
        source action {
            touched mutate 1
            lazy mutate 8
        }
        val snapshot = source.snapshot()
        val fatal = StackOverflowError()
        val target = FrpStore(initThrows = fatal)
        val seen = target.recordUncaught()
        val started = FrpStarted<FrpStore>()
        target.middlewares(started)
        target action { touched mutate 2 }
        started.ids.clear()

        assertSame(fatal, assertFailsWith<StackOverflowError> { target.restore(snapshot) })
        assertSame(fatal, assertFailsWith<StackOverflowError> { target.restore(snapshot, RestorePolicy.BestEffort) })

        assertEquals(emptyList(), started.ids, "no Restore transaction started")
        assertEquals(2, target.touched.value)
        assertEquals(emptyList(), seen)
        target.assertNotWedged()
    }

    @Test
    fun aSchemaVersionGettersFatalErrorIsThrownBeforeTheRestoreOpens() {
        val source = FrpVersionedStore()
        source action { n mutate 5 }
        val snapshot = source.snapshot()
        val target = FrpVersionedStore()
        val seen = target.recordUncaught()
        val started = FrpStarted<FrpVersionedStore>()
        target.middlewares(started)
        val fatal = OutOfMemoryError()
        target.versionThrows = fatal

        assertSame(fatal, assertFailsWith<OutOfMemoryError> { target.restore(snapshot) })

        assertEquals(emptyList(), started.ids)
        assertEquals(0, target.n.value)
        assertEquals(emptyList(), seen)
        target.versionThrows = null
        assertNull(target.activeTransaction)
        assertThreadClean()
        target.restore(snapshot).getOrThrow()
        assertEquals(5, target.n.value)
    }

    @Test
    fun aValueCodecsFatalErrorIsNotAnIssueBestEffortTolerates() {
        val source = FrpStore()
        source action { n mutate 5 }
        val decoded = StoreSnapshot.decode(source.snapshot().encode())
        val target = FrpStore()
        val seen = target.recordUncaught()
        val started = FrpStarted<FrpStore>()
        target.middlewares(started)
        target action { n mutate 1 }
        started.ids.clear()
        val fatal = StackOverflowError()
        target.codec.decodeThrows = fatal

        assertSame(fatal, assertFailsWith<StackOverflowError> { target.restore(decoded, RestorePolicy.BestEffort) })

        assertEquals(emptyList(), started.ids, "no Restore transaction started")
        assertEquals(1, target.n.value)
        assertEquals(emptyList(), seen)
        target.assertNotWedged()

        // Control: a non-fatal decode failure is still an Undecodable issue BestEffort tolerates.
        target.codec.decodeThrows = IllegalArgumentException("unreadable")
        val report = target.restore(decoded, RestorePolicy.BestEffort).getOrThrow()
        assertTrue(report.issues.any { it is RestoreIssue.Undecodable && it.stateName == "n" }, "${report.issues}")
        assertEquals(1, target.n.value)
        assertEquals(emptyList(), seen)
    }

    @Test
    fun aKeyCodecsFatalErrorIsThrownAndNoEntryIsCreated() {
        val source = FrpStore()
        source action { docs["a"] mutate 1 }
        val decoded = StoreSnapshot.decode(source.snapshot().encode())
        val target = FrpStore()
        val seen = target.recordUncaught()
        val started = FrpStarted<FrpStore>()
        target.middlewares(started)
        val fatal = StackOverflowError()
        target.keyCodec.decodeThrows = fatal

        assertSame(fatal, assertFailsWith<StackOverflowError> { target.restore(decoded, RestorePolicy.BestEffort) })

        assertEquals(emptyMap(), target.docs.entries, "no entry created")
        assertEquals(emptyList(), started.ids)
        assertEquals(emptyList(), seen)
        target.assertNotWedged()
        target.keyCodec.decodeThrows = null
        target.restore(decoded, RestorePolicy.BestEffort).getOrThrow()
        assertEquals(1, target.docs["a"].value)
    }

    @Test
    fun anEntryValueCodecsFatalErrorIsThrownWithTheCreatedEntryAtItsInitialValue() {
        val source = FrpStore()
        source action { docs["a"] mutate 1 }
        val decoded = StoreSnapshot.decode(source.snapshot().encode())
        val target = FrpStore()
        val seen = target.recordUncaught()
        val started = FrpStarted<FrpStore>()
        target.middlewares(started)
        val fatal = StackOverflowError()
        target.valueCodec.decodeThrows = fatal

        assertSame(fatal, assertFailsWith<StackOverflowError> { target.restore(decoded, RestorePolicy.BestEffort) })

        assertEquals(setOf("a"), target.docs.entries.keys, "the entry the plan created stays live")
        assertEquals(-1, target.docs["a"].value, "at its initial value")
        assertEquals(emptyList(), started.ids)
        assertEquals(emptyList(), seen)
        target.assertNotWedged()
    }

    @Test
    fun migratesFatalErrorIsThrownAsItIsWithTheViewClosed() {
        val target = FrpMigratingStore()
        val seen = target.recordUncaught()
        val started = FrpStarted<FrpMigratingStore>()
        target.middlewares(started)
        val fatal = StackOverflowError()
        target.migrateThrows = fatal
        val decoded = StoreSnapshot.decode(V1_TEXT)

        assertSame(fatal, assertFailsWith<StackOverflowError> { target.restore(decoded, RestorePolicy.BestEffort) })
        val leaked = checkNotNull(target.stashed)
        target.stashed = null
        assertSame(fatal, assertFailsWith<StackOverflowError> { target.restore(decoded) })

        val closed = assertFailsWith<IllegalStateException> { leaked.put("n", "6") }
        assertTrue("closed" in closed.message.orEmpty(), closed.message)
        assertEquals(0, target.n.value)
        assertEquals(emptyList(), started.ids)
        assertEquals(emptyList(), seen)
        assertNull(target.activeTransaction)
        assertThreadClean()

        // Control: a non-fatal migrate failure is still a SnapshotMigrationException, returned as an Error.
        target.migrateThrows = IllegalArgumentException("cannot migrate")
        val error = assertIs<TransactionResult.Error>(target.restore(decoded))
        assertIs<SnapshotMigrationException>(error.exception)
        target.migrateThrows = null
        target.restore(decoded).getOrThrow()
        assertEquals(5, target.n.value)
    }
}
