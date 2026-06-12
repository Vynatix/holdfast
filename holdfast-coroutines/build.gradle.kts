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
        namespace = "com.vynatix.holdfast.coroutines"
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
            api(project(":holdfast"))
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
