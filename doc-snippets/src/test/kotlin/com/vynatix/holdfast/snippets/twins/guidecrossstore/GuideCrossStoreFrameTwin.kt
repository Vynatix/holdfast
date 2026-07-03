// Twins of GUIDE §15 (Cross-Store Transactions) examples. Compile-only —
// the §15.6 block in particular pairs shouldCommitTogether with its negation
// for teaching purposes and is not meant to pass as a single runtime test.
package com.vynatix.holdfast.snippets.twins.guidecrossstore

import com.vynatix.holdfast.Store
import com.vynatix.holdfast.atomic
import com.vynatix.holdfast.coroutines.suspendAtomic
import com.vynatix.holdfast.testing.matcher.and
import com.vynatix.holdfast.testing.matcher.committedFrameIds
import com.vynatix.holdfast.testing.matcher.shouldCommitTogether
import com.vynatix.holdfast.testing.matcher.shouldNotCommitTogether
import com.vynatix.holdfast.testing.storeTest

// Scaffold: the per-domain stores the §15 intro coordinates, named but not
// defined by the doc.
class SettingsStore : Store<SettingsStore>() {
    val backendToken by state { "" }
}

class BackendStatusStore : Store<BackendStatusStore>() {
    val authFailed by state { false }
}

class AccountStore : Store<AccountStore>() {
    val balance by state { 0L }
}

@Suppress("unused", "UNUSED_VARIABLE")
private suspend fun tokenUpdateFrame(
    settings: SettingsStore,
    backendStatus: BackendStatusStore,
    token: String,
) {
    // DOC-SNIPPET holdfast/GUIDE.md#60
    val r = atomic(settings, backendStatus) {
        settings.action { backendToken mutate token }
        backendStatus.action { authFailed mutate false }
    }

    // Suspending peer (:holdfast-coroutines) — same contract, suspending body:
    val r2 = suspendAtomic(settings, backendStatus) {
        settings { backendToken mutate token }
        backendStatus { authFailed mutate false }
    }
    // DOC-SNIPPET-END
}

@Suppress("unused")
private fun frameMatchers(
    accountA: AccountStore,
    accountB: AccountStore,
) {
    // DOC-SNIPPET holdfast/GUIDE.md#62
    storeTest {
        val ha = track(accountA)
        val hb = track(accountB)

        atomic(accountA, accountB) { /* transfer */ }

        (ha and hb).shouldCommitTogether()      // same frameId committed on both
        ha.committedFrameIds()                  // all frame commits, in order
        (ha and hb).shouldNotCommitTogether()   // negation, e.g. after a rollback
    }
    // DOC-SNIPPET-END
}
