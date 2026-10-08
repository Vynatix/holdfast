package com.vynatix.holdfast.coroutines.platform

// Whether a blocking caller (action, atomic, reset, restore) that finds a
// store's serializer taken by a suspendAction/suspendAtomic/hydration decision
// can get it by waiting: true where another thread can run that holder to its
// end (jvm/android/ios). False on wasmJs: the waiting call holds the only
// thread the holder could resume on, so the wait would spin forever. Same
// platform split as runBlockingForInitialSeed (RunBlocking.kt).
internal expect val blockingWaitCanEnd: Boolean
