@file:OptIn(ExperimentalStoreApi::class, StoreInternalApi::class)

package com.vynatix.holdfast

import com.vynatix.holdfast.bridge.IntCodec
import com.vynatix.holdfast.bridge.StringCodec
import com.vynatix.holdfast.crypto.EncryptingTransformer
import com.vynatix.holdfast.crypto.XorCipher
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val CUT_SEED = "consistent-cut-seed".encodeToByteArray()

private class CutProfile : Store<CutProfile>() {
    val name by state(codec = StringCodec) { "guest" }
    val visits by state(codec = IntCodec) { 0 }
}

private class CutVault : Store<CutVault>() {
    val pin by state(transformer = EncryptingTransformer(XorCipher(CUT_SEED)), codec = StringCodec) { "" }
}

private class CutCart : Store<CutCart>() {
    val items by state(codec = IntCodec) { 0 }
    val notes by keyedState<String, String>(codec = StringCodec, keyCodec = StringCodec) { "" }
}

/** Declares [CutCart.items] as text: its encoded snapshot is a corrupt leaf for a CutCart. */
private class CutCartAsText : Store<CutCartAsText>() {
    val items by state(codec = StringCodec) { "three" }
}

/** Declares [CutProfile.visits] as text: its encoded snapshot is a corrupt leaf for a CutProfile. */
private class CutProfileAsText : Store<CutProfileAsText>() {
    val visits by state(codec = StringCodec) { "seven" }
}

/** A Secret state next to a plain one, both with codecs. */
private class CutKeys : Store<CutKeys>() {
    val token by state(codec = StringCodec, tags = setOf(StateTag.Secret)) { "" }
    val label by state(codec = StringCodec) { "" }
}

/** A state whose initializer runs [go] — a restore, say — at its first read. */
private class CutRestoringInit(
    private val go: () -> Unit,
) : Store<CutRestoringInit>() {
    val x by state {
        go()
        0
    }
}

/** A state whose initializer throws while [failing] says so. */
private class CutFlaky(
    private val failing: () -> Boolean,
) : Store<CutFlaky>() {
    val late by state(codec = IntCodec) { if (failing()) error("initializer boom") else 0 }
}

/** Records every transaction a store's middleware sees: its frame id, and how it ended. */
private class CutFrameLog<V : Store<V>> : Middleware<V>() {
    val completed = mutableListOf<String?>()
    val failed = mutableListOf<String?>()

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

/**
 * The multi-store cut, both ways (issue #20 plan PR 15, D21; issue #21's
 * `Root.snapshot`/`Root.restore`): `captureConsistent(stores)` reads one cut
 * across several stores, and `restoreInOneFrame(snapshots)` puts one back in
 * ONE outermost `atomic` frame — all of it or none of it, staged raw.
 */
class ConsistentCutTest {
    private class Stores {
        val profile = CutProfile()
        val vault = CutVault()
        val cart = CutCart()
        val all: List<Store<*>> = listOf(profile, vault, cart)
    }

    private fun filled(): Stores =
        Stores().apply {
            profile action {
                name mutate "ada"
                visits mutate 7
            }
            vault action { pin mutate "4921" }
            cart action {
                items mutate 3
                notes["milk"] mutate "oat"
                notes["bread"] mutate "rye"
            }
        }

    @Test fun threeStoresRoundTripCaptureEncodeDecodeRestoreInOneFrame() {
        val source = filled()
        val cut = captureConsistent(source.all)
        val texts = cut.map { it.encode() }
        assertFalse(texts.any { "4921" in it }, "the pin's plaintext never reaches the text")

        val target = Stores()
        val profileLog = CutFrameLog<CutProfile>().also { target.profile.middlewares(it) }
        val cartLog = CutFrameLog<CutCart>().also { target.cart.middlewares(it) }
        val decoded = texts.map { StoreSnapshot.decode(it) }
        val reports =
            restoreInOneFrame(target.all.zip(decoded).toMap(), RestorePolicy.Strict).getOrThrow()

        assertEquals("ada", target.profile.name.value)
        assertEquals(7, target.profile.visits.value)
        assertEquals("4921", target.vault.pin.value, "restored raw: decrypting once yields the plaintext")
        assertEquals(3, target.cart.items.value)
        val notes = target.cart.notes.entries
        assertEquals(mapOf("milk" to "oat", "bread" to "rye"), notes.mapValues { it.value.value })
        assertEquals(cut, captureConsistent(target.all), "the restored stores hold the captured cut, raw value for value")
        assertEquals(
            cut[1].rawValues["pin"],
            target.vault.snapshot().rawValues["pin"],
            "the ciphertext went back as it was captured, not encrypted a second time",
        )
        assertEquals(target.all, reports.keys.toList(), "a report per store, in the map's order")
        assertEquals(setOf("name", "visits"), reports.getValue(target.profile).restored)
        val frame = profileLog.completed.single()
        assertTrue(frame != null && frame.startsWith("atomic-"), "the profile's one transaction is a frame root")
        assertEquals<List<String?>>(listOf(frame), cartLog.completed, "one frame, one transaction per store")
    }

    @Test fun aCapturedCutRestoresInPlaceAsUndo() {
        val stores = filled()
        val cut = captureConsistent(stores.all)
        stores.profile action { visits mutate 99 }
        stores.vault action { pin mutate "0000" }
        stores.cart action {
            items mutate 0
            notes["milk"] mutate "cow"
        }

        restoreInOneFrame(stores.all.zip(cut).toMap()).getOrThrow()

        assertEquals(7, stores.profile.visits.value)
        assertEquals("4921", stores.vault.pin.value)
        assertEquals(3, stores.cart.items.value)
        assertEquals("oat", stores.cart.notes["milk"].value)
        assertEquals(cut, captureConsistent(stores.all))
    }

    @Test fun oneCorruptLeafRollsEveryStoreBack() {
        val source = filled()
        val good = captureConsistent(source.all).map { StoreSnapshot.decode(it.encode()) }
        // The cart's leaf holds text its IntCodec cannot decode.
        val corrupt = StoreSnapshot.decode(CutCartAsText().snapshot().encode())

        val target = Stores()
        target.profile action { visits mutate 1 }
        val before = captureConsistent(target.all)
        val fired = mutableListOf<String>()
        val watches =
            listOf(
                target.profile.visits effect { fired += "visits=$this" },
                target.vault.pin effect { fired += "pin" },
                target.cart.items effect { fired += "items=$this" },
            )
        fired.clear()
        val logs = listOf(CutFrameLog<CutProfile>().also { target.profile.middlewares(it) })
        // The corrupt leaf sits between two good ones, in map order and in lock order.
        val snapshots = mapOf(target.profile to good[0], target.cart to corrupt, target.vault to good[1])

        val result = restoreInOneFrame(snapshots)

        assertIs<TransactionResult.Error>(result)
        val rejected = assertIs<RestoreRejectedException>(result.exception)
        assertEquals(listOf("items"), rejected.issues.map { it.stateName }, "it names the corrupt state")
        assertEquals(before, captureConsistent(target.all), "every store rolled back: nothing changed")
        assertEquals(1, target.profile.visits.value)
        assertEquals(emptyList(), fired, "no observer fired on any store")
        assertEquals(1, logs[0].failed.size, "the good leaves' transactions rolled back with the frame")
        assertEquals(emptyList(), logs[0].completed, "and never completed")
        watches.forEach { it.dispose() }
    }

    @Test fun aFailingInitializerOfOneLeafRollsEveryStoreBack() {
        var fail = true
        val flaky = CutFlaky { fail }
        val donor = CutFlaky { false }
        donor action { late mutate 5 }
        val late = StoreSnapshot.decode(donor.snapshot().encode())
        val source = filled()
        val target = Stores()

        // The plan materializes the never-read target, whose initializer throws.
        val result = restoreInOneFrame(mapOf(target.profile to source.profile.snapshot(), flaky to late))

        assertIs<TransactionResult.Error>(result)
        assertEquals("initializer boom", result.exception.message)
        assertEquals(0, target.profile.visits.value, "the other leaf rolled back")
        fail = false
        restoreInOneFrame(mapOf(target.profile to source.profile.snapshot(), flaky to late)).getOrThrow()
        assertEquals(5, flaky.late.value, "a throwing initializer stays retryable")
        assertEquals(7, target.profile.visits.value)
    }

    @Test fun derivedStatesOverTheRestoredStoresSettleOnceAfterTheFrame() {
        val source = filled()
        val cut = captureConsistent(source.all)
        val target = Stores()
        var computes = 0
        val total =
            target.profile.derivedState(target.profile.visits, target.cart.items) {
                computes++
                target.profile.visits.value + target.cart.items.value
            }

        restoreInOneFrame(target.all.zip(cut).toMap()).getOrThrow()

        assertEquals(10, total.value)
        assertEquals(2, computes, "the initial compute, then one recompute for the whole frame")
        total.dispose()
    }

    /** A node over [target]'s cart as a whole, hosted on its profile: its computes, and every value it showed. */
    private class CartNode(
        target: Stores,
    ) {
        var computes = 0
        val seen = mutableListOf<Int>()
        val node =
            target.profile.derivedStateOverStores("n", listOf(target.cart)) {
                computes++
                target.cart.notes.entries.size * 100 + target.cart.items.value
            }
        private val watch = node effect { seen += this }

        fun dispose() {
            watch.dispose()
            node.dispose()
        }
    }

    @Test fun aNodeFollowingARestoredStoreAsAWholeSettlesOnceAfterTheFrame() {
        val cut = captureConsistent(filled().all)
        val target = Stores()
        val probe = CartNode(target)

        restoreInOneFrame(target.all.zip(cut).toMap()).getOrThrow()

        assertEquals(203, probe.node.value)
        assertEquals(2, probe.computes, "the initial compute, then one after the frame — none as the plan creates entries")
        assertEquals(listOf(0, 203), probe.seen, "never the plan's new entries without the frame's writes")
        probe.dispose()
    }

    @Test fun aNodeFollowingAStoreAsAWholeSettlesOnceAfterItsRestore() {
        val cut = captureConsistent(filled().all)
        val target = Stores()
        val probe = CartNode(target)

        target.cart.restore(cut[2]).getOrThrow()

        assertEquals(203, probe.node.value)
        assertEquals(2, probe.computes, "the plan and the action are one entry")
        assertEquals(listOf(0, 203), probe.seen)
        probe.dispose()
    }

    @Test fun aRolledBackFrameStillSettlesTheEntriesItsPlanCreatedOnce() {
        val cut = captureConsistent(filled().all)
        val corrupt = StoreSnapshot.decode(CutProfileAsText().snapshot().encode())
        val target = Stores()
        val probe = CartNode(target)

        val result = restoreInOneFrame(mapOf(target.profile to corrupt, target.cart to cut[2]))

        assertIs<RestoreRejectedException>(assertIs<TransactionResult.Error>(result).exception)
        assertEquals(0, target.cart.items.value, "the frame rolled back")
        assertEquals(setOf("milk", "bread"), target.cart.notes.entries.keys, "the entries the plan created stay live")
        assertEquals(200, probe.node.value, "the value covers them")
        assertEquals(2, probe.computes, "one recompute, after the failed frame")
        assertEquals(listOf(0, 200), probe.seen)
        probe.dispose()
    }

    @Test fun anEncodedCutRestoresItsEncodableProjection() {
        val source = CutKeys()
        source action {
            token mutate "s3cret"
            label mutate "home"
        }
        val captured = captureConsistent(listOf(source)).single()
        val text = captured.encode()
        assertFalse("s3cret" in text, "a Secret value is withheld")
        val target = CutKeys()
        target action {
            token mutate "mine"
            label mutate "old"
        }

        restoreInOneFrame(mapOf(target to StoreSnapshot.decode(text))).getOrThrow()

        assertEquals("home", target.label.value)
        assertEquals("mine", target.token.value, "a Secret state, encoded as null, keeps the value it holds")
        restoreInOneFrame(mapOf(target to captured)).getOrThrow()
        assertEquals("s3cret", target.token.value, "a captured cut restores as the cut it was")
    }

    @Test fun refusesToRunInsideAnAction() {
        val stores = filled()
        val cut = captureConsistent(stores.all)
        stores.vault action { pin mutate "0000" }

        val outer =
            stores.profile action {
                val refused =
                    assertFailsWith<IllegalStateException> { restoreInOneFrame(mapOf(stores.vault to cut[1])) }
                assertContains(refused.message!!, "in one frame")
                assertContains(refused.message!!, "nested frame is not all-or-nothing")
            }
        outer.getOrThrow()

        assertEquals("0000", stores.vault.pin.value, "nothing was restored")
    }

    @Test fun refusesToRunInsideAFrameOrAnObserver() {
        val stores = filled()
        val cut = captureConsistent(stores.all)
        stores.cart action { items mutate 0 }

        atomic(stores.profile, stores.cart) {
            assertFailsWith<IllegalStateException> { restoreInOneFrame(mapOf(stores.cart to cut[2])) }
        }.getOrThrow()
        var fromObserver: Throwable? = null
        val watch =
            stores.profile.visits effect {
                if (this == 8) fromObserver = runCatching { restoreInOneFrame(mapOf(stores.cart to cut[2])) }.exceptionOrNull()
            }
        stores.profile action { visits mutate 8 }
        watch.dispose()

        assertIs<IllegalStateException>(fromObserver, "a commit's fanout is inside its entry")
        assertEquals(0, stores.cart.items.value, "nothing was restored")
        restoreInOneFrame(mapOf(stores.cart to cut[2])).getOrThrow()
        assertEquals(3, stores.cart.items.value, "at top level it runs")
    }

    @Test fun refusesToRunInsideADerivedStatesCompute() {
        val stores = filled()
        val cut = captureConsistent(stores.all)
        var refused: Throwable? = null
        val probe =
            stores.profile.derivedState(stores.profile.visits) {
                if (visits.value == 9) refused = runCatching { restoreInOneFrame(mapOf(stores.cart to cut[2])) }.exceptionOrNull()
                visits.value
            }

        stores.profile action { visits mutate 9 }

        // Only the NoWriteRegion refusal says this: atomic's own refusal has
        // no "in one frame", and the enclosing-entry check no ": the compute of".
        assertContains(assertIs<IllegalStateException>(refused).message!!, "in one frame: the compute of")
        probe.dispose()
    }

    @Test fun refusesToRunInsideAStateInitializerBeforeItsPlanRuns() {
        val cut = captureConsistent(listOf(filled().cart))
        val target = CutCart()
        var refused: Throwable? = null
        val probe = CutRestoringInit { refused = runCatching { restoreInOneFrame(mapOf(target to cut[0])) }.exceptionOrNull() }

        // A first read at top level, outside any entry.
        probe.x.value

        assertContains(assertIs<IllegalStateException>(refused).message!!, "in one frame: the initializer of")
        assertFalse("milk" in target.notes, "the plan never ran: it created no entry")
        assertFalse("bread" in target.notes)
        assertEquals(0, target.items.value)
    }

    @Test fun refusesAnEmptyMapAndADisposedStore() {
        assertFailsWith<IllegalArgumentException> { restoreInOneFrame(emptyMap()) }
        val stores = filled()
        val cut = captureConsistent(stores.all)
        stores.vault.dispose()
        val refused =
            assertFailsWith<IllegalStateException> {
                restoreInOneFrame(mapOf(stores.profile to cut[0], stores.vault to cut[1]))
            }
        assertEquals("store disposed", refused.message)
        assertNull(stores.profile.activeTransaction, "no frame was opened")
    }
}
