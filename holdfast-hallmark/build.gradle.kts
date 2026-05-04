import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("astrid.kmp.library")
    id("astrid.quality")
    id("astrid.dokka")
    id("astrid.abi")
    id("astrid.publish.sonatype")
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
        namespace = "com.vynatix.vault.validation"
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            api(project(":vault"))
            api(project(":validation"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
