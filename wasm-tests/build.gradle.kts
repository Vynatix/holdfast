// wasmJs regression suite — never published.
//
// :holdfast's and :holdfast-coroutines' own test suites cannot run on wasmJs
// (they use runBlocking/newSingleThreadContext and :holdfast-testing, none of
// which exist there), so their wasmJs test tasks stay disabled. This module
// runs smoke tests through the public, @ExperimentalStoreApi and
// @StoreInternalApi surface on wasmJs (Node.js) — the only place wasmJs code
// paths execute in CI — and on the JVM as the control: a test that passes on
// the JVM and fails on wasmJs is a wasmJs bug (issue #26).
//
// commonTest must compile for wasmJs: no runBlocking, no threads, no
// :holdfast-testing; suspending tests use kotlinx.coroutines.test.runTest.
//
// Deliberately NOT applied: holdfast.publish.*, holdfast.abi, holdfast.dokka —
// this module is build infrastructure, not an artifact.

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.time.Duration

plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    // Node.js only: the library has no DOM surface, and the js/wasm actuals
    // that broke (atomicfu's) are the same in the browser.
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        nodejs {
            testTask {
                useMocha {
                    timeout = "30s"
                }
            }
        }
    }

    sourceSets {
        commonTest.dependencies {
            implementation(project(":holdfast"))
            implementation(project(":holdfast-coroutines"))
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// Same hard cap as the library modules (holdfast.kmp.library): a hung test
// fails the build in minutes instead of stalling a CI runner.
tasks.withType<AbstractTestTask>().configureEach {
    timeout.set(Duration.ofMinutes(10))
}
