package com.vynatix.holdfast.coroutines.platform

// wasmJs is single-threaded: a suspending holder can only resume on the thread
// a blocking waiter would be spinning on.
internal actual val blockingWaitCanEnd: Boolean = false
