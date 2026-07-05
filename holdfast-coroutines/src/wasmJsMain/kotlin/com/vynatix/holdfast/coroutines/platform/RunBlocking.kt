package com.vynatix.holdfast.coroutines.platform

internal actual fun <T> runBlockingForInitialSeed(body: suspend () -> T): T =
    throw UnsupportedOperationException(
        "The seedless suspendDerived(vararg sources) { compute } eagerly seeds via " +
            "runBlocking, which is not available on wasmJs (the JS event loop cannot " +
            "block). Use the seeded overload instead: " +
            "suspendDerived(vararg sources, initial = <seed>) { compute } — it holds " +
            "`initial` until the first async compute lands, with no runBlocking.",
    )
