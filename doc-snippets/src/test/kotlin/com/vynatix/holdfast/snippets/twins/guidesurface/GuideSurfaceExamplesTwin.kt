// Twins of GUIDE.md §14.1/§14.3/§14.5 examples (snapshot/restore, atomic,
// FileSystemKvStore). Compile-only — nothing here touches the file system.
package com.vynatix.holdfast.snippets.twins.guidesurface

import com.vynatix.holdfast.Store
import com.vynatix.holdfast.atomic
import com.vynatix.holdfast.bridge.FileSystemKvStore
import com.vynatix.holdfast.bridge.KvBridge
import com.vynatix.holdfast.bridge.LongCodec
import com.vynatix.holdfast.restore
import com.vynatix.holdfast.snapshot

// Scaffold stores for the three examples.
private class SnapStore : Store<SnapStore>() {
    val count by state { 0 }
    val label by state { "" }
}

private class AccountStore : Store<AccountStore>() {
    val balance by state { 0L }
}

private class WalletStore : Store<WalletStore>() {
    val balance by state { 0L }
}

@Suppress("unused")
private fun snapshotRestoreRoundTrip() {
    val holdfast = SnapStore()
    // DOC-SNIPPET holdfast/GUIDE.md#33
    val snap = holdfast.snapshot()
    holdfast action { count mutate 9999; label mutate "wrong" }
    holdfast.restore(snap)               // count + label back to snapshot values
    // DOC-SNIPPET-END
}

@Suppress("unused", "UNUSED_VARIABLE")
private fun atomicCrossStoreTransfer() {
    val accountA = AccountStore()
    val accountB = AccountStore()
    val amount = 100L
    // DOC-SNIPPET holdfast/GUIDE.md#36
    val r = atomic(accountA, accountB) {
        accountA.action { balance update { it - amount } }
        accountB.action { balance update { it + amount } }
    }
    // DOC-SNIPPET-END
}

@Suppress("unused")
private fun fileSystemKvStorePersistence() {
    val home = System.getProperty("user.home")
    val holdfast = WalletStore()
    // DOC-SNIPPET holdfast/GUIDE.md#39
    val kv = FileSystemKvStore(rootPath = "$home/.myapp")
    holdfast { balance bridge KvBridge(kv, "balance:1", LongCodec) }
    // balance auto-persists on every commit; new holdfasts attaching the same
    // KvBridge hydrate from disk via load-on-attach.
    // DOC-SNIPPET-END
}
