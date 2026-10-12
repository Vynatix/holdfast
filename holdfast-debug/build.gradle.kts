import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Experimental tracing module (issue 016): DebugTraceMiddleware emits
// versioned JSONL transaction traces to a sink, on every platform holdfast
// targets; :holdfast-cli's `trace` commands render them offline.
//
// Uses the @StoreInternalApi boundary (Transaction.parent, keyedEntryName)
// for savepoint linkage and keyed-family naming — never application-facing
// API. Everything else rides the public Middleware SPI.

plugins {
    id("holdfast.kmp.library")
    id("holdfast.quality")
    id("holdfast.dokka")
    id("holdfast.abi")
    id("holdfast.publish")
}

kotlin {
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
        commonMain.dependencies {
            api(project(":holdfast"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
