package com.vynatix.vault.testing

import com.vynatix.vault.Vault

/**
 * Test-scope handle to a tracked [Vault]. Returned by [VaultTestScope.track]; the
 * registry keeps it alive for the duration of the test so subsequent `track`
 * calls with the same vault instance return the same handle.
 *
 * In Issue 02 only [vault], [captureMode], and [read] are exposed — the rest of
 * the surface (timeline views, action shortcuts, eventually/barrier helpers)
 * lands in later issues.
 */
class VaultHandle<V : Vault<V>> internal constructor(val vault: V, val captureMode: Capture) {
    /**
     * Run [block] with the tracked vault as receiver and return its value. The
     * block sees `vault.value` for each [com.vynatix.vault.State] without going
     * through an action — useful for plain read assertions.
     */
    fun <R> read(block: V.() -> R): R = block(vault)
}
