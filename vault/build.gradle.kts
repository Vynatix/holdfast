plugins {
    id("astrid.kmp.library")
    id("astrid.kmp.jvm")
    id("astrid.kmp.wasmJs")
    id("astrid.quality")
    id("astrid.dokka")
    id("astrid.abi")
    id("astrid.publish.sonatype")
}

kotlin {
    android {
        namespace = "com.vynatix.vault"
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.atomicfu)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.coroutines.test)
            implementation(project(":vault-testing"))
        }
    }
}

// :vault publishes a wasmJs main artifact so :shared (which targets web via
// :web) can resolve it. The test suite uses `runBlocking` and
// `newSingleThreadContext`, neither of which exists on wasmJs; coverage runs
// on android/jvm/ios via `:check`. Disable wasmJs test compilation/execution
// so the suite compiles cleanly.
val wasmJsTestTasks = setOf(
    "compileTestKotlinWasmJs",
    "compileTestDevelopmentExecutableKotlinWasmJs",
    "compileTestProductionExecutableKotlinWasmJs",
    "wasmJsTest",
    "wasmJsBrowserTest",
    "wasmJsBrowserDevelopmentTest",
    "wasmJsBrowserProductionTest",
)
tasks.matching { it.name in wasmJsTestTasks }.configureEach {
    enabled = false
}
