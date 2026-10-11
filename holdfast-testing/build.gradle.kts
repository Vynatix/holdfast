import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("holdfast.kmp.library")
    id("holdfast.quality")
    id("holdfast.dokka")
    id("holdfast.abi")
    id("holdfast.publish")
}

kotlin {
    android {
        namespace = "com.vynatix.holdfast.testing"
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        // Real-thread tests (a suspending entry that ends on another thread):
        // the hop helpers need JVM executor/thread APIs, so they stay off the
        // iOS targets — same shape as :holdfast-coroutines' host-test set.
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
            api(project(":holdfast-coroutines"))
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.coroutines.test)
            implementation(libs.atomicfu)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
