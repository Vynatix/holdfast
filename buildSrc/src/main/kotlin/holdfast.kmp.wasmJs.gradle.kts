import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

// Opt-in: adds the wasmJs/browser target to a KMP library. Apply
// alongside `holdfast.kmp.library` only on modules that need a web
// consumer. wasmJs is single-threaded and does not provide
// `kotlinx.coroutines.newSingleThreadContext`, so library tests that
// assume real OS threads must be moved out of `commonTest` before
// applying this plugin.

plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }
}
