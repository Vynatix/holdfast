import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("holdfast.kmp.library")
    id("holdfast.quality")
    id("holdfast.dokka")
    id("holdfast.abi")
    id("holdfast.publish.sonatype")
}

// ktlint 1.7.x does not yet parse Kotlin 2.2+ `context(name: Type)` parameters.
// Exclude files that use the syntax until ktlint catches up.
ktlint {
    filter {
        exclude("**/BoxedHandle.kt")
    }
}

kotlin {
    android {
        namespace = "com.vynatix.holdfast.hallmark"
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            api(project(":holdfast"))
            api("com.vynatix:hallmark:0.1.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
