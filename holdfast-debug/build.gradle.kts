import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("holdfast.kmp.library")
    id("holdfast.kmp.jvm")
    id("holdfast.quality")
    id("holdfast.dokka")
    id("holdfast.abi")
    id("holdfast.publish.sonatype")
}

kotlin {
    // Companion-module privilege: the journal observes states through
    // `MutableState.observe` and snapshots under `runUnderLock`, both
    // @StoreInternalApi. Everything this module exports is itself
    // @ExperimentalStoreApi (see README: pre-1.0 DevTools surface).
    compilerOptions {
        optIn.add("com.vynatix.holdfast.StoreInternalApi")
        optIn.add("com.vynatix.holdfast.ExperimentalStoreApi")
    }

    android {
        namespace = "com.vynatix.holdfast.debug"
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        // JVM and Android share the reflection-backed actuals (enum constants,
        // thread names); iOS has its own.
        val jvmAndAndroidMain by creating {
            dependsOn(commonMain.get())
        }
        androidMain {
            dependsOn(jvmAndAndroidMain)
        }
        jvmMain {
            dependsOn(jvmAndAndroidMain)
        }

        commonMain.dependencies {
            api(project(":holdfast"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.atomicfu)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
