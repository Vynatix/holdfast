@file:OptIn(ExperimentalStoreApi::class)

package com.vynatix.holdfast

import com.vynatix.holdfast.bridge.IntCodec
import com.vynatix.holdfast.bridge.StringCodec
import com.vynatix.holdfast.crypto.EncryptingTransformer
import com.vynatix.holdfast.crypto.XorCipher
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private val VAULT_SEED = "snapshot-scope-seed".encodeToByteArray()

private const val TOKEN = "tok-5ECRET-1"
private const val PIN = "pin-5ECRET-2"

/** A codec that records every value it is asked to encode. */
private class WatchingCodec : StateCodec<String> {
    val seen = mutableListOf<String>()

    override fun encode(value: String): String {
        seen += value
        return value
    }

    override fun decode(string: String): String = string
}

private class AccountStore : Store<AccountStore>() {
    val tokenCodec = WatchingCodec()
    var initializersRun = 0

    val name by state(codec = StringCodec) {
        initializersRun++
        "guest"
    }
    val token by state(codec = tokenCodec, tags = setOf(StateTag.Secret)) {
        initializersRun++
        ""
    }
    val pin by state(
        transformer = EncryptingTransformer(XorCipher(VAULT_SEED)),
        codec = StringCodec,
        tags = setOf(StateTag.Secret),
    ) {
        initializersRun++
        ""
    }
    val pins by state(codec = StringCodec, tags = setOf(StateTag.UserAuthored)) {
        initializersRun++
        "a"
    }
    val draft by state(tags = setOf(StateTag.UserAuthored)) {
        initializersRun++
        ""
    }
    val feed by state(codec = IntCodec, tags = setOf(StateTag.Remote)) {
        initializersRun++
        0
    }
    val scratch by state {
        initializersRun++
        0
    }

    fun signIn() =
        action {
            name mutate "ada"
            token mutate TOKEN
            pin mutate PIN
            pins mutate "a,b"
            draft mutate "unsent"
            feed mutate 7
        }
}

/** Tagged states without a codec: what `encode()` writes of them, apart from [AccountStore]'s assertions. */
private class CodecLessTagsStore : Store<CodecLessTagsStore>() {
    val name by state(codec = StringCodec) { "n" }
    val feed by state(tags = setOf(StateTag.Remote)) { 1 }
    val key by state(tags = setOf(StateTag.Secret)) { "k3y-5ECRET" }
}

/**
 * Snapshot scopes and Secret redaction in snapshots (issue #20, R3):
 * acceptance 2 (`snapshot(UserAuthored)` holds exactly the tagged states) and
 * the encoded part of acceptance 1 (a Secret's raw value appears in no encoded
 * snapshot), plus the frozen undo idiom and the encodable round trip.
 */
class SnapshotScopeTest {
    /** The plaintext and ciphertext of both secrets, which no written text may contain. */
    private fun AccountStore.secretTexts(): List<String> {
        val cipherPin = checkNotNull(snapshot(SnapshotScope.Raw).content as? CapturedContent).rawValues.getValue("pin")
        assertFalse(cipherPin == PIN, "the pin is stored encrypted")
        return listOf(TOKEN, PIN, cipherPin as String)
    }

    @Test fun userAuthoredCapturesExactlyTheTaggedStates() {
        val store = AccountStore()
        val snapshot = store.snapshot(SnapshotScope.UserAuthored)
        assertEquals(setOf("pins", "draft"), snapshot.stateNames)
        assertEquals(2, store.initializersRun, "only the captured states' initializers ran")
        assertEquals("a", snapshot[store.pins])
        assertSame(SnapshotEntry.Absent, snapshot.entry(store.token))
        assertSame(SnapshotEntry.Absent, snapshot.entry(store.feed))
    }

    @Test fun userAuthoredHoldsNoDerivedAndRestoresOnlyItsStates() {
        val store = AccountStore()
        val (length, d) = store.derived(store.pins) { pins.value.length }
        try {
            store.signIn()
            val overlay = store.snapshot(SnapshotScope.UserAuthored)
            assertTrue(checkNotNull(overlay.content as? CapturedContent).derivedBackingValues.isEmpty())
            store action {
                pins mutate "z"
                draft mutate ""
                feed mutate 99
            }
            store.restore(overlay).getOrThrow()
            assertEquals("a,b", store.pins.value)
            assertEquals("unsent", store.draft.value)
            assertEquals(99, store.feed.value, "a state outside the scope keeps its value")
            assertEquals(3, length.value, "the derived recomputed from the restored source")
        } finally {
            d.dispose()
        }
    }

    @Test fun allWithholdsSecretsFromTypedReadsAndRawReadsThemInPlaintext() {
        val store = AccountStore()
        store.signIn()
        val all = store.snapshot()
        assertSame(Redacted, all.entry(store.token))
        assertSame(Redacted, all.entry(store.pin))
        assertNull(all[store.token])
        assertEquals("ada", all[store.name])
        assertSame(Redacted, store.snapshot(SnapshotScope.All).entry(store.token))

        val raw = store.snapshot(SnapshotScope.Raw)
        assertEquals(TOKEN, raw[store.token])
        assertEquals(PIN, raw[store.pin], "an encrypted secret reads as its plaintext")
        assertEquals(SnapshotEntry.Present(TOKEN), raw.entry(store.token))
    }

    @Test fun aDerivedOfASecretIsWithheldToo() {
        val store = AccountStore()
        val (tokenLength, d) = store.derived(store.token) { token.value.length }
        try {
            store.signIn()
            assertSame(Redacted, store.snapshot().entry(tokenLength))
            assertEquals(TOKEN.length, store.snapshot(SnapshotScope.Raw)[tokenLength])
        } finally {
            d.dispose()
        }
    }

    @Test fun noEncodedSnapshotHoldsASecretInAnyScope() {
        val store = AccountStore()
        store.signIn()
        val secrets = store.secretTexts()
        for (scope in listOf(SnapshotScope.All, SnapshotScope.Raw, SnapshotScope.UserAuthored)) {
            val snapshot = store.snapshot(scope)
            for (text in listOf(snapshot.encode(), snapshot.encode(includeRemote = true))) {
                secrets.forEach { secret -> assertFalse(secret in text, "$scope leaks a secret: $text") }
            }
        }
        assertTrue(store.tokenCodec.seen.isEmpty(), "a Secret value never reaches its codec: ${store.tokenCodec.seen}")
        val text = store.snapshot(SnapshotScope.Raw).encode()
        assertContains(text, "\"token\":null")
        assertContains(text, "\"pin\":null")
    }

    @Test fun renderAndToStringWithholdSecretsInEveryScope() {
        val store = AccountStore()
        store.signIn()
        val secrets = store.secretTexts()
        for (scope in listOf(SnapshotScope.All, SnapshotScope.Raw)) {
            val snapshot = store.snapshot(scope)
            val shown = listOf(snapshot.render(), snapshot.toString(), StoreSnapshot.decode(snapshot.encode()).render())
            shown.forEach { text -> secrets.forEach { secret -> assertFalse(secret in text, "$scope shows a secret: $text") } }
            assertContains(snapshot.render(), "token = <redacted>")
            assertContains(snapshot.render(), "name = ada")
        }
        assertContains(store.snapshot(SnapshotScope.Raw).render(), "scope Raw")
    }

    @Test fun remoteStatesAreEncodedOnlyWhenAskedFor() {
        val store = AccountStore()
        store.signIn()
        val snapshot = store.snapshot()
        assertFalse("feed" in StoreSnapshot.decode(snapshot.encode()).stateNames, "left out by default")
        assertFalse("\"feed\"" in snapshot.encode())
        assertEquals(7, StoreSnapshot.decode(snapshot.encode(includeRemote = true))[store.feed])
        assertEquals(setOf("draft", "scratch"), snapshot.unencodableStateNames, "a codec-less state is still listed")
    }

    @Test fun theFrozenUndoIdiomIsLosslessWithSecrets() {
        val store = AccountStore()
        store.signIn()
        val undo = store.snapshot()
        store action {
            token mutate "rotated"
            pin mutate "0000"
            name mutate "eve"
        }
        store.restore(undo).getOrThrow()
        assertEquals(TOKEN, store.token.value)
        assertEquals(PIN, store.pin.value, "the ciphertext went back without a second encryption")
        assertEquals("ada", store.name.value)
    }

    @Test fun aRoundTripIsEqualOnTheEncodableProjection() {
        val store = AccountStore()
        store.signIn()
        for (scope in listOf(SnapshotScope.All, SnapshotScope.Raw)) {
            val snapshot = store.snapshot(scope)
            val decoded = StoreSnapshot.decode(snapshot.encode())
            assertTrue(decoded.equalsEncodable(snapshot), "$scope: ${snapshot.encode()}")
            assertEquals(snapshot.encode(), decoded.encode())
        }
        assertEquals(store.snapshot(), store.snapshot(SnapshotScope.Raw), "the scope plays no part in equality")
    }

    @Test fun aDecodedSnapshotNeverDecodesASecretStatesText() {
        // Text written before `token` was Secret, or by hand, still holds its value.
        val text = """{"format":"holdfast.store","v":1,"schema":1,"states":{"name":"x","token":"$TOKEN"},"skipped":[]}"""
        val store = AccountStore()
        val decoded = StoreSnapshot.decode(text)
        assertSame(Redacted, decoded.entry(store.token), "a decoded snapshot is no Raw capture")
        assertEquals("x", decoded[store.name])
        store.restore(decoded, RestorePolicy.Strict).getOrThrow()
        assertEquals(TOKEN, store.token.value, "a restore still writes it: redaction is read-time only")
    }

    @Test fun anEncodedSecretRestoresNothing() {
        val source = AccountStore()
        source.signIn()
        val target = AccountStore()
        target action { token mutate "target-own" }
        val report = target.restore(StoreSnapshot.decode(source.snapshot().encode()), RestorePolicy.Strict).getOrThrow()
        assertEquals("target-own", target.token.value, "a withheld value leaves the state as it is")
        assertTrue("token" in report.kept && "pin" in report.kept, "$report")
    }

    @Test fun codecLessRemoteIsLeftOutAndCodecLessSecretIsSkipped() {
        val snapshot = CodecLessTagsStore().snapshot()
        assertEquals(setOf("feed", "key"), snapshot.unencodableStateNames, "the capture lists both")

        val text = snapshot.encode()
        assertTrue(text.endsWith(""""states":{"name":"n"},"skipped":["key"]}"""), text)
        assertFalse("\"feed\"" in text, "a codec-less Remote state is neither written nor skipped: $text")
        assertFalse("k3y-5ECRET" in text, text)
        assertEquals(setOf("key"), StoreSnapshot.decode(text).unencodableStateNames, "unlike the capture's")
        assertTrue(StoreSnapshot.decode(text).equalsEncodable(snapshot))

        val withRemote = snapshot.encode(includeRemote = true)
        assertTrue(withRemote.endsWith(""""skipped":["feed","key"]}"""), withRemote)
        assertEquals(setOf("feed", "key"), StoreSnapshot.decode(withRemote).unencodableStateNames)
    }

    @Test fun aDecodedSnapshotRendersTheTextItHoldsEvenForASecretState() {
        // Text written before `token` was tagged Secret, or by another writer:
        // a decoded snapshot knows no tags, so it renders the text as it is,
        // while a typed read still withholds the value.
        val store = AccountStore()
        val text = """{"format":"holdfast.store","v":1,"schema":1,"states":{"token":"old-plain"},"skipped":[]}"""
        val decoded = StoreSnapshot.decode(text)
        assertSame(Redacted, decoded.entry(store.token))
        assertContains(decoded.render(), "old-plain")
        assertEquals(text, decoded.encode(), "and writes it back")
        store action { token mutate "old-plain" }
        assertFalse("old-plain" in store.snapshot().render(), "a captured snapshot never shows it")
    }

    @Test fun scopedSnapshotsAreGatedOnDispose() {
        val store = AccountStore()
        store.dispose()
        val failure = runCatching { store.snapshot(SnapshotScope.UserAuthored) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException && "disposed" in failure.message.orEmpty(), "$failure")
    }
}
