import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Opt-in: adds the JVM target to a KMP library. Apply alongside
// `holdfast.kmp.library` only on modules that need a desktop / pure-JVM
// consumer. Adding this changes the published artifact matrix, so it
// is intentionally per-module rather than universal.

plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
val javaTarget = libs.findVersion("java-target").get().requiredVersion

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.fromTarget(javaTarget))
        }
    }
}
