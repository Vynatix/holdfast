// Convention plugin: applies Dokka HTML for KDoc rendering.
//
// Apply alongside `holdfast.kmp.library` on any module whose public API should
// be browseable as a doc site. Generates `build/dokka/html/` per module.
//
// Run `./gradlew :module:dokkaGenerate` to produce HTML; the root project
// can aggregate via `dokkaHtmlMultiModule` if configured separately.

plugins {
    id("org.jetbrains.dokka")
}
