import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("holdfast.kmp.library")
    id("holdfast.kmp.jvm")
    id("holdfast.kmp.wasmJs")
    id("holdfast.quality")
    id("holdfast.dokka")
    id("holdfast.abi")
    id("holdfast.publish.sonatype")
}

kotlin {
    android {
        namespace = "com.vynatix.holdfast"
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        val jvmAndAndroidMain by creating {
            dependsOn(commonMain.get())
        }
        androidMain {
            dependsOn(jvmAndAndroidMain)
        }
        jvmMain {
            dependsOn(jvmAndAndroidMain)
        }

        val jvmAndAndroidHostTest by creating {
            dependsOn(commonTest.get())
        }
        androidHostTest {
            dependsOn(jvmAndAndroidHostTest)
        }
        jvmTest {
            dependsOn(jvmAndAndroidHostTest)
        }

        commonMain.dependencies {
            implementation(libs.atomicfu)
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.coroutines.test)
            implementation(project(":holdfast-testing"))
        }
    }
}

// :holdfast publishes a wasmJs main artifact so :shared (which targets web via
// :web) can resolve it. The test suite uses `runBlocking` and
// `newSingleThreadContext`, neither of which exists on wasmJs; coverage runs
// on android/jvm/ios via `:check`. Disable wasmJs test compilation/execution
// so the suite compiles cleanly.
val wasmJsTestTasks =
    setOf(
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

// Disabled tasks still get their dependencies computed during task-graph
// construction, and :holdfast-testing has no wasmJs variant — keep it out of
// the wasmJs test classpaths so a bare `./gradlew check` can schedule at all.
configurations.matching { it.name.startsWith("wasmJsTest") }.configureEach {
    exclude(group = "com.vynatix", module = "holdfast-testing")
}
