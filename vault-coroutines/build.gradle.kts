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
        namespace = "com.vynatix.vault.coroutines"
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":vault"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.atomicfu)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// Tests use `runBlocking`, which doesn't exist on wasmJs; coverage runs on
// android/jvm/ios. Main code compiles cleanly for wasmJs so :shared/wasmJs
// can resolve this module.
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
