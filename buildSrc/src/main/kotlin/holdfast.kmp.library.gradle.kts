import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.time.Duration

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
val javaTarget = libs.findVersion("java-target").get().requiredVersion

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Wextra")
        // Stable since Kotlin 2.2; gates the `context(name: Type)` syntax.
        freeCompilerArgs.add("-Xcontext-parameters")
        // Beta in Kotlin 2.x; opts in to expect/actual classes (used by
        // FileSystemKvStore et al.) and silences KT-61573 warnings.
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    android {
        compileSdk = libs.findVersion("android-compileSdk").get().requiredVersion.toInt()
        minSdk = libs.findVersion("android-minSdk").get().requiredVersion.toInt()

        compilerOptions {
            jvmTarget.set(JvmTarget.fromTarget(javaTarget))
        }

        withHostTest {}
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Holdfast"
            isStatic = true
            binaryOption("bundleId", "com.vynatix.holdfast")
        }
    }
}

// Hard cap on every test task so a hung/livelocked test fails the build in
// minutes instead of stalling a CI runner until the 6-hour job timeout.
// A full per-target suite finishes in well under a minute today.
tasks.withType<org.gradle.api.tasks.testing.AbstractTestTask>().configureEach {
    timeout.set(Duration.ofMinutes(10))
}
