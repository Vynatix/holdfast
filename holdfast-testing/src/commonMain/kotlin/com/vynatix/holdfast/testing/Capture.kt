package com.vynatix.holdfast.testing

/**
 * Selects what a [StoreHandle] records about its tracked vault during a test.
 *
 * The chosen mode is preserved on the handle and drives the privileged
 * recorder middleware installed by [StoreTestScope.track]:
 *
 *  - [All] grows the [StoreHandle.timeline] unbounded for the test's lifetime.
 *  - [None] does not install the recorder at all, so no events are captured
 *    and there is no per-action overhead. [StoreHandle.timeline] returns an
 *    empty list. Use this when only direct reads are exercised.
 *  - [RingBuffer] keeps at most [RingBuffer.size] most-recent events, dropping
 *    the oldest when the window fills. Useful for very long-running test loops
 *    that only need the tail of the timeline for assertions.
 */
sealed interface Capture {
    /** Record every event the recorder fires for the lifetime of the test. */
    object All : Capture

    /** Skip recording entirely — no recorder is installed on the vault. */
    object None : Capture

    /**
     * Keep at most [size] most-recent events in a fixed-size ring. The buffer
     * truncates from the front so the stored window is the tail of the
     * timeline.
     */
    data class RingBuffer(val size: Int) : Capture {
        init {
            require(size > 0) { "RingBuffer size must be > 0, was $size" }
        }
    }
}
