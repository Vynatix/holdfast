package com.vynatix.vault.testing

/**
 * Selects what a [VaultHandle] records about its tracked vault during a test.
 *
 * In the walking-skeleton issue (02), the chosen mode is preserved on the handle
 * but only [All] has any visible effect — instrumentation lands in Issue 06.
 * [None] and [RingBuffer] are accepted today so test code can declare intent
 * without churning when capture turns on.
 */
sealed interface Capture {
    /** Record every transaction the vault completes for the lifetime of the test. */
    object All : Capture

    /** Skip recording entirely — used when only direct reads are exercised. */
    object None : Capture

    /** Keep at most [size] most-recent transactions in a fixed-size ring. */
    data class RingBuffer(val size: Int) : Capture
}
