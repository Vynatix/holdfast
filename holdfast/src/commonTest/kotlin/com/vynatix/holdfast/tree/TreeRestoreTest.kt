@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast.tree

import com.vynatix.holdfast.EncodedSnapshotView
import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.Middleware
import com.vynatix.holdfast.Redacted
import com.vynatix.holdfast.RestorePolicy
import com.vynatix.holdfast.RestoreRejectedException
import com.vynatix.holdfast.SchemaVersioned
import com.vynatix.holdfast.SnapshotScope
import com.vynatix.holdfast.StateTag
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreInternalApi
import com.vynatix.holdfast.TransactionResult
import com.vynatix.holdfast.TransactionStatus
import com.vynatix.holdfast.atomic
import com.vynatix.holdfast.bridge.IntCodec
import com.vynatix.holdfast.bridge.StringCodec
import com.vynatix.holdfast.crypto.EncryptingTransformer
import com.vynatix.holdfast.crypto.XorCipher
import com.vynatix.holdfast.effect
import com.vynatix.holdfast.snapshot
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private val restoreCipher = XorCipher("tree-restore-seed".encodeToByteArray())

private class RsProfileStore : Store<RsProfileStore>() {
    val name by state(codec = StringCodec) { "guest" }
    val visits by state(codec = IntCodec) { 0 }
    val secret by state(codec = StringCodec, tags = setOf(StateTag.Secret)) { "s0" }
}

private class RsVaultStore : Store<RsVaultStore>() {
    val pin by state(transformer = EncryptingTransformer(restoreCipher), codec = StringCodec) { "" }
}

private class RsThreadStore(
    id: String,
) : Store<RsThreadStore>() {
    val title by state(codec = StringCodec) { "thread $id" }
}

/** The receiver: named `Rs` by its class (minus `Store`), with no states of its own. */
private class RsStore : Store<RsStore>() {
    val profile = RsProfileStore()
    val vault = RsVaultStore()
    val main by stores { listOf(profile, vault) }
    val threads by stores<String, RsThreadStore> { RsThreadStore(it) }
}

/** Declares [RsProfileStore.visits] as text: its encoded leaf is corrupt for an RsProfileStore. */
private class RsProfileAsText : Store<RsProfileAsText>() {
    val name by state(codec = StringCodec) { "guest" }
    val visits by state(codec = StringCodec) { "many" }
}

private class RsProfileAsTextApp : Store<RsProfileAsTextApp>() {
    val profile = RsProfileAsText()
    val vault = RsVaultStore()
    val main by stores(names = mapOf(RsProfileAsText::class to "RsProfile")) { listOf(profile, vault) }
    val threads by stores<String, RsThreadStore2> { RsThreadStore2(it) }
}

private class RsThreadStore2(
    id: String,
) : Store<RsThreadStore2>() {
    val title by state(codec = StringCodec) { "thread $id" }
}

private class RsFrameLog<V : Store<V>> : Middleware<V>() {
    val started = mutableListOf<Pair<String, String?>>()
    val completed = mutableListOf<String?>()
    val failed = mutableListOf<String?>()

    override fun onTransactionStarted(context: MiddlewareContext<V>) {
        started += context.transaction.id to context.transaction.frameId
    }

    override fun onTransactionCompleted(context: MiddlewareContext<V>) {
        completed += context.transaction.frameId
    }

    override fun onTransactionError(
        context: MiddlewareContext<V>,
        error: Throwable,
    ) {
        failed += context.transaction.frameId
    }
}

/** Schema 2 of a versioned leaf: `count` was `n` in schema 1. */
private class RsVersionedStore :
    Store<RsVersionedStore>(),
    SchemaVersioned {
    val count by state(codec = IntCodec) { 0 }
    val migrated = mutableListOf<Int>()
    override val schemaVersion: Int get() = 2

    override fun migrate(
        from: Int,
        view: EncodedSnapshotView,
    ) {
        migrated += from
        if (from < 2) view.rename("n", "count")
    }
}

private class RsVersionedV1Store : Store<RsVersionedV1Store>() {
    val n by state(codec = IntCodec) { 0 }
}

/** Two stores of one class: one pinned, the other as a `store { }` child, so neither name comes from the class. */
private class RsVersionedApp : Store<RsVersionedApp>() {
    val a by store { RsVersionedStore() }
    val b by store { RsVersionedStore() }
}

private class RsVersionedV1App : Store<RsVersionedV1App>() {
    val a by store { RsVersionedV1Store() }
    val b by store { RsVersionedV1Store() }
}

/** A leaf whose `onTransactionStarted` disposes [victim], a participant whose frame root is already open. */
private class RsDisposingMiddleware<V : Store<V>>(
    private val victim: Store<*>,
) : Middleware<V>() {
    override fun onTransactionStarted(context: MiddlewareContext<V>) {
        victim.dispose()
    }
}

/** T4 restore, T5: `tree.restore` puts a tree back as one frame, addressed by node. */
class TreeRestoreTest {
    private fun filled(): RsStore {
        val root = RsStore()
        root.profile action {
            name mutate "ada"
            visits mutate 7
            secret mutate "s1"
        }
        root.vault action { pin mutate "4921" }
        root.threads.create("t1") action { title mutate "first" }
        return root
    }

    @Test
    fun aCapturedTreeRestoresInPlaceAsUndoInOneFrameWithOneTransactionPerLeaf() {
        val root = filled()
        val before = root.tree.snapshot()
        root.profile action { visits mutate 99 }
        root.vault action { pin mutate "0000" }
        root.threads["t1"]!! action { title mutate "changed" }
        val profileLog = RsFrameLog<RsProfileStore>().also { root.profile.middlewares(it) }
        val vaultLog = RsFrameLog<RsVaultStore>().also { root.vault.middlewares(it) }

        val result = root.tree.restore(before)
        val report = assertIs<TransactionResult.Success<TreeRestoreReport>>(result).value

        assertEquals(7, root.profile.visits.value)
        assertEquals("4921", root.vault.pin.value)
        assertEquals("first", root.threads["t1"]!!.title.value)
        assertEquals(before, root.tree.snapshot(), "the tree holds the captured cut again, Secret included")
        val frame = profileLog.completed.single()
        assertNotNull(frame)
        assertTrue(frame.startsWith("atomic-"), "each leaf's transaction is a root of the frame")
        assertEquals(listOf<String?>(frame), vaultLog.completed, "one frame id across the leaves")
        assertEquals(profileLog.started.single().first, vaultLog.started.single().first, "the frame's transaction id, shared")
        assertEquals(4, report.perNode.size, "the receiver, both group leaves and the keyed leaf")
        assertEquals(setOf("name", "visits", "secret"), report.perNode.getValue(root.tree.nodeOf(root.profile)!!).restored)
        assertEquals(emptyList<StoreNode>(), report.skipped)
        assertEquals(emptyList<StoreNode>(), report.rebound)
        assertEquals(frame, result.transaction.frameId)
    }

    @Test
    fun anEncryptedLeafRoundTripsWithoutDoubleEncryption() {
        val root = filled()
        val cipherText = root.vault.snapshot().rawValues["pin"]
        val captured = root.tree.snapshot()
        root.vault action { pin mutate "0000" }
        root.tree.restore(captured).getOrThrow()
        assertEquals("4921", root.vault.pin.value, "decrypting once yields the plaintext")
        assertEquals(cipherText, root.vault.snapshot().rawValues["pin"], "the ciphertext went back as captured")

        val decoded = root.tree.decode(captured.encode())
        root.vault action { pin mutate "1111" }
        root.tree.restore(decoded).getOrThrow()
        assertEquals("4921", root.vault.pin.value)
        assertEquals(cipherText, root.vault.snapshot().rawValues["pin"])
    }

    @Test
    fun oneCorruptLeafRollsEveryLeafBack() {
        val source = RsProfileAsTextApp()
        source.profile action { name mutate "ada" }
        source.vault action { pin mutate "4921" }
        source.threads.create("t1")
        val text = source.tree.snapshot().encode()

        val target = filled()
        val decoded = target.tree.decode(text)
        assertEquals(emptyList<List<String>>(), decoded.unresolvedPaths, "the same names: every path resolves")
        val vaultLog = RsFrameLog<RsVaultStore>().also { target.vault.middlewares(it) }
        val result = target.tree.restore(decoded)

        assertIs<TransactionResult.Error>(result)
        assertEquals(TransactionStatus.RolledBack, result.transaction.status)
        assertEquals("ada", target.profile.name.value, "unchanged")
        assertEquals(7, target.profile.visits.value, "the corrupt leaf did not apply")
        assertEquals("4921", target.vault.pin.value)
        assertEquals(emptyList<String?>(), vaultLog.completed, "no leaf committed")
        assertEquals(1, vaultLog.failed.size, "every leaf's transaction failed with the frame")
    }

    @Test
    fun nestingInsideAnActionOrAFrameIsRefusedWithATeachingMessage() {
        val root = filled()
        val tree = root.tree.snapshot()
        var fromAction: Throwable? = null
        root.profile.action { fromAction = runCatching { root.tree.restore(tree) }.exceptionOrNull() }.getOrThrow()
        val actionMessage = assertIs<IllegalStateException>(fromAction).message!!
        assertContains(actionMessage, "one outermost frame")
        assertContains(actionMessage, "restore the tree under 'Rs'")
        var fromFrame: Throwable? = null
        atomic(root.vault) { fromFrame = runCatching { root.tree.restore(tree) }.exceptionOrNull() }.getOrThrow()
        assertContains(assertIs<IllegalStateException>(fromFrame).message!!, "Call it from outside every entry")
        var fromObserver: Throwable? = null
        val watch = root.profile.visits effect { if (this == 1) fromObserver = runCatching { root.tree.restore(tree) }.exceptionOrNull() }
        root.profile action { visits mutate 1 }
        watch.dispose()
        assertIs<IllegalStateException>(fromObserver)
        assertEquals(1, root.profile.visits.value, "nothing restored")
    }

    @Test
    fun aTreeFromAnotherParentFailsFast() {
        val root = filled()
        val other = filled()
        val failure = assertFailsWith<IllegalArgumentException> { root.tree.restore(other.tree.snapshot()) }
        assertContains(failure.message!!, "captured under 'Rs'")
        assertContains(failure.message!!, "restore it through the store it was captured from")
        val t1 = root.threads["t1"]!!
        val fromChild = assertFailsWith<IllegalArgumentException> { t1.tree.restore(root.tree.snapshot()) }
        assertContains(fromChild.message!!, "is not under this store ('t1')", message = "a parent's capture is not under its child")
    }

    @Test
    fun aDisposedKeyedLeafIsSkippedUnderIgnoreUnknownAndFailsUnderStrict() {
        val root = filled()
        val tree = root.tree.snapshot()
        val t1 = root.threads["t1"]!!
        val t1Leaf = root.tree.nodeOf(t1)!!
        t1.dispose()
        root.profile action { visits mutate 1 }

        val report = root.tree.restore(tree).getOrThrow()
        assertEquals(listOf(t1Leaf), report.skipped)
        assertEquals(7, root.profile.visits.value, "the live leaves were restored")
        assertEquals(3, report.perNode.size, "the receiver and both group leaves")

        root.profile action { visits mutate 2 }
        val strict = root.tree.restore(tree, RestorePolicy.Strict)
        assertIs<TransactionResult.Error>(strict)
        val rejected = assertIs<RestoreRejectedException>(strict.exception)
        assertEquals("Rs", rejected.message!!.substringAfter("Cannot restore ").substringBefore(" under"))
        assertEquals(
            listOf("threads/t1"),
            rejected.issues.map { it.stateName },
            "receiver-relative and as captured, like unresolvedPaths",
        )
        assertEquals(2, root.profile.visits.value, "nothing was touched")
        assertEquals(TransactionStatus.RolledBack, strict.transaction.status)
    }

    @Test
    fun strictFailsOnUnresolvedPaths() {
        val root = filled()
        val text =
            root.tree
                .snapshot()
                .encode()
                .replace(""""threads":""", """"other":{"kind":"branch","children":{}},"threads":""")
        val decoded = root.tree.decode(text)
        assertEquals(listOf(listOf("other")), decoded.unresolvedPaths)
        root.profile action { visits mutate 3 }
        val strict = root.tree.restore(decoded, RestorePolicy.Strict)
        assertIs<TransactionResult.Error>(strict)
        assertEquals(listOf("other"), assertIs<RestoreRejectedException>(strict.exception).issues.map { it.stateName })
        assertEquals(3, root.profile.visits.value)
        root.tree.restore(decoded).getOrThrow()
        assertEquals(7, root.profile.visits.value, "IgnoreUnknown tolerates the path")
    }

    @Test
    fun aCapturedTreeReachesARecreatedKeyedStoreAndReportsRebound() {
        val root = filled()
        val tree = root.tree.snapshot()
        root.threads["t1"]!!.dispose()
        val recreated = root.threads.create("t1")
        assertEquals("thread t1", recreated.title.value)
        val report = root.tree.restore(tree).getOrThrow()
        assertEquals("first", recreated.title.value)
        assertEquals(listOf(root.tree.nodeOf(recreated)!!), report.rebound)
        assertEquals(emptyList<StoreNode>(), report.skipped)
        assertTrue(root.tree.nodeOf(recreated)!! in report.perNode.keys)
    }

    @Test
    fun aKeyedStoresCaptureTakenThroughItsParentRestoresThroughItsOwnTree() {
        val root = RsStore()
        val thread = root.threads.create("1")
        thread action { title mutate "a" }
        val captured = root.tree.snapshot(root.tree.nodeOf(thread)!!)
        thread action { title mutate "b" }

        val report = thread.tree.restore(captured, RestorePolicy.Strict).getOrThrow()

        assertEquals("a", thread.title.value, "the receiver's own capture restores")
        assertEquals(listOf<StoreNode>(thread.tree.node), report.perNode.keys.toList())
        assertEquals(emptyList<StoreNode>(), report.skipped)
    }

    @Test
    fun theProcessDeathIdiomRecreatesPendingKeysThenRestores() {
        val source = filled()
        source.threads.create("t2") action { title mutate "second" }
        val text = source.tree.snapshot().encode()

        val app = RsStore()
        val tree = app.tree.decode(text)
        assertEquals(setOf("t1", "t2"), tree.pendingKeys(app.threads))
        tree.pendingKeys(app.threads).forEach { app.threads.create(it) }
        val report = app.tree.restore(tree, RestorePolicy.Strict).getOrThrow()
        assertEquals("first", app.threads["t1"]!!.title.value)
        assertEquals("second", app.threads["t2"]!!.title.value)
        assertEquals("ada", app.profile.name.value)
        assertEquals(emptyList<StoreNode>(), report.skipped)
        assertEquals(emptyList<StoreNode>(), report.rebound, "a pending key's store was created before the restore, never rebound")
        assertEquals(5, report.perNode.size, "the receiver, both group leaves and both keyed leaves")
    }

    @Test
    fun migrateRunsPerLeafOnADecodedTree() {
        val v1 = RsVersionedV1App()
        v1.a action { n mutate 5 }
        v1.b action { n mutate 6 }
        val text = v1.tree.snapshot().encode()
        val v2 = RsVersionedApp()
        v2.tree.restore(v2.tree.decode(text), RestorePolicy.Strict).getOrThrow()
        assertEquals(listOf(1), v2.a.migrated)
        assertEquals(listOf(1), v2.b.migrated)
        assertEquals(5, v2.a.count.value)
        assertEquals(6, v2.b.count.value)
    }

    @Test
    fun aDecodedTreeLeavesSecretsUntouchedWhileACapturedAllTreeRestoresThemLosslessly() {
        val root = filled()
        val captured = root.tree.snapshot()
        val decoded = root.tree.decode(captured.encode())
        root.profile action { secret mutate "s2" }

        root.tree.restore(decoded).getOrThrow()
        assertEquals("s2", root.profile.secret.value, "encoded as null: kept")
        assertTrue(
            "secret" in
                root.tree
                    .restore(decoded)
                    .getOrThrow()
                    .perNode
                    .getValue(root.tree.nodeOf(root.profile)!!)
                    .kept,
        )

        root.tree.restore(captured).getOrThrow()
        assertEquals("s1", root.profile.secret.value, "a capture holds the raw value")
        assertSame(Redacted, captured.entry(root.profile.secret), "and still never shows it")
        assertEquals(captured, root.tree.snapshot())
    }

    @Test
    fun anEmptySubtreeReturnsSuccessCarryingASyntheticCommittedTransaction() {
        val root = RsStore()
        val tree = root.tree.snapshot(root.threads)
        assertEquals(emptyList<TreeSnapshot>(), tree.children)
        val result = root.tree.restore(tree)
        val success = assertIs<TransactionResult.Success<TreeRestoreReport>>(result)
        assertEquals(TransactionStatus.Committed, success.transaction.status)
        assertEquals("tree-restore", success.transaction.id)
        assertNotNull(success.transaction.endTime)
        assertTrue(success.value.perNode.isEmpty())
    }

    @Test
    fun aLeafDisposedAfterLockAcquisitionIsSkippedAndReported() {
        val root = filled()
        val tree = root.tree.snapshot()
        root.profile action { visits mutate 1 }
        root.vault action { pin mutate "0000" }
        // The profile's root opens first (lower lock-order key); the vault's
        // started hook then disposes it, after its lock and root are held.
        assertTrue(root.profile.lockOrderKey < root.vault.lockOrderKey)
        root.vault.middlewares(RsDisposingMiddleware(root.profile))
        val profileLeaf = root.tree.nodeOf(root.profile)!!

        val report = root.tree.restore(tree).getOrThrow()

        assertTrue(root.profile.isDisposed)
        assertEquals(listOf(profileLeaf), report.skipped)
        assertFalse(profileLeaf in report.perNode.keys)
        assertEquals("4921", root.vault.pin.value, "the rest of the frame committed")
        assertNull(root.tree.nodeOf(root.profile), "the disposed leaf left the tree")
    }

    @Test
    fun aSterileRestoreResetsRemoteStatesInEveryLeaf() {
        val root = RsSterileApp()
        root.a action {
            local mutate "x"
            remote mutate "r1"
        }
        val tree = root.tree.snapshot()
        root.a action {
            local mutate "y"
            remote mutate "r2"
        }
        val report = root.tree.restore(tree, sterile = true).getOrThrow()
        assertEquals("x", root.a.local.value)
        assertEquals("r0", root.a.remote.value, "Remote goes back to its initial value")
        assertEquals(setOf("remote"), report.perNode.getValue(root.tree.nodeOf(root.a)!!).sterilized)
    }

    @Test
    fun restoreUnderUserAuthoredScopeTouchesOnlyTaggedStates() {
        val root = RsSterileApp()
        root.a action {
            local mutate "x"
            authored mutate "typed"
        }
        val tree = root.tree.snapshot(scope = SnapshotScope.UserAuthored)
        root.a action {
            local mutate "y"
            authored mutate "retyped"
        }
        root.tree.restore(tree).getOrThrow()
        assertEquals("y", root.a.local.value, "not captured, so kept")
        assertEquals("typed", root.a.authored.value)
    }

    @Test
    fun aParentsOwnStatesAreCapturedAndRestoredWithItsChildren() {
        val parent = RsParentStore()
        parent action { open mutate "thread-1" }
        parent.child action { authored mutate "typed" }
        val tree = parent.tree.snapshot(scope = SnapshotScope.UserAuthored)
        assertEquals("thread-1", tree[parent.open], "the receiver's own state is in its capture")
        assertEquals("typed", tree[parent.child.authored])

        parent action { open mutate "thread-2" }
        parent.child action { authored mutate "retyped" }
        val decoded = parent.tree.decode(tree.encode())
        val report = parent.tree.restore(decoded, RestorePolicy.Strict).getOrThrow()
        assertEquals("thread-1", parent.open.value)
        assertEquals("typed", parent.child.authored.value)
        assertEquals(listOf<StoreNode>(parent.tree.node, parent.tree.nodeOf(parent.child)!!), report.perNode.keys.toList())

        parent.tree.reset().getOrThrow()
        assertEquals("none", parent.open.value, "a reset of the receiver's subtree resets its own states too")
        assertEquals("a0", parent.child.authored.value)
    }
}

private class RsSterileStore : Store<RsSterileStore>() {
    val local by state(codec = StringCodec) { "l0" }
    val remote by state(codec = StringCodec, tags = setOf(StateTag.Remote)) { "r0" }
    val authored by state(codec = StringCodec, tags = setOf(StateTag.UserAuthored)) { "a0" }
}

private class RsSterileApp : Store<RsSterileApp>() {
    val a = RsSterileStore()
    val leaf by stores(names = mapOf(RsSterileStore::class to "a")) { listOf(a) }
}

/** A parent with states of its own: they are captured and restored with its children's. */
private class RsParentStore : Store<RsParentStore>() {
    val open by state(codec = StringCodec, tags = setOf(StateTag.UserAuthored)) { "none" }
    val child by store { RsSterileStore() }
}
