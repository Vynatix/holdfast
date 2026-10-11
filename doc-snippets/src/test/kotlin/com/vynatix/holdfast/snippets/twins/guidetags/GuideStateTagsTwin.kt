// Twin of GUIDE §16.4 (state tags, snapshot scopes and redaction). The block
// is embedded at top level; the test drives it with the values its comment
// assumes and asserts the output, text and values its comments claim.
package com.vynatix.holdfast.snippets.twins.guidetags

import com.vynatix.holdfast.ExperimentalStoreApi
import com.vynatix.holdfast.RestorePolicy
import com.vynatix.holdfast.SnapshotScope
import com.vynatix.holdfast.StateTag
import com.vynatix.holdfast.Store
import com.vynatix.holdfast.StoreSnapshot
import com.vynatix.holdfast.bridge.IntCodec
import com.vynatix.holdfast.bridge.StringCodec
import com.vynatix.holdfast.restore
import com.vynatix.holdfast.snapshot
import com.vynatix.holdfast.snippets.capturePrintln
import kotlin.test.Test
import kotlin.test.assertEquals

// DOC-SNIPPET holdfast/GUIDE.md#71
@OptIn(ExperimentalStoreApi::class)
class MailStore : Store<MailStore>() {
    val token by state(codec = StringCodec, tags = setOf(StateTag.Secret)) { "" }
    val pinned by state(codec = StringCodec, tags = setOf(StateTag.UserAuthored)) { "" }
    val unread by state(codec = IntCodec, tags = setOf(StateTag.Remote)) { 0 }
}

@OptIn(ExperimentalStoreApi::class)
fun saveAndReboot(mail: MailStore): MailStore {
    // Say mail holds token = "t0k3n", pinned = "m1", unread = 42.
    println(mail.snapshot()[mail.token])                            // "null": withheld outside SnapshotScope.Raw
    println(mail.snapshot(SnapshotScope.Raw)[mail.token])           // "t0k3n", in memory only
    println(mail.snapshot(SnapshotScope.UserAuthored).stateNames)   // "[pinned]"
    val saved = mail.snapshot().encode()
    // {"format":"holdfast.store","v":1,"schema":1,"states":{"pinned":"m1","token":null},"skipped":[]}
    val rebooted = MailStore()
    rebooted.restore(StoreSnapshot.decode(saved), RestorePolicy.Strict, sterile = true).getOrThrow()
    return rebooted   // pinned = "m1", token = "" (never written out), unread = 0 (reset)
}
// DOC-SNIPPET-END

@OptIn(ExperimentalStoreApi::class)
class GuideStateTagsTwin {
    @Test
    fun saveAndRebootWithholdsTheSecretAndResetsTheRemoteState() {
        val mail = MailStore()
        mail action {
            token mutate "t0k3n"
            pinned mutate "m1"
            unread mutate 42
        }

        lateinit var rebooted: MailStore
        val printed = capturePrintln { rebooted = saveAndReboot(mail) }

        assertEquals(listOf("null", "t0k3n", "[pinned]"), printed)
        assertEquals(
            """{"format":"holdfast.store","v":1,"schema":1,"states":{"pinned":"m1","token":null},"skipped":[]}""",
            mail.snapshot().encode(),
            "the text the comment shows",
        )
        assertEquals("m1", rebooted.pinned.value)
        assertEquals("", rebooted.token.value, "never written out")
        assertEquals(0, rebooted.unread.value, "reset")
    }
}
