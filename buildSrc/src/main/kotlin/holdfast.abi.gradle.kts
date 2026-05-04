// Convention plugin: applies binary-compatibility-validator with KLib API tracking
// enabled (required for KMP modules — JVM-only modules get .api by default).
//
// Apply alongside `holdfast.kmp.library` on any module whose ABI should be
// versioned and reviewed across changes. Generates:
//   <module>/api/<module>.api                  ← JVM (Android target) ABI
//   <module>/api/<module>.klib.api             ← merged KLib ABI across native targets
//
// Workflow:
//   - `./gradlew :module:apiDump` writes the current ABI as a baseline.
//   - `./gradlew :module:apiCheck` fails if the committed baseline differs from
//     the current source.
//
// Commit the generated `.api` and `.klib.api` files so changes are reviewable.

import kotlinx.validation.ApiValidationExtension

plugins {
    id("org.jetbrains.kotlinx.binary-compatibility-validator")
}

extensions.configure<ApiValidationExtension> {
    @OptIn(kotlinx.validation.ExperimentalBCVApi::class)
    klib {
        enabled = true
    }
}
