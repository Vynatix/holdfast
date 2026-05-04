import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("holdfast.kmp.library")
    id("holdfast.quality")
    id("holdfast.dokka")
    id("holdfast.abi")
    id("holdfast.publish.sonatype")
}

kotlin {
    android {
        namespace = "com.vynatix.holdfast.hallmark.coroutines"
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
            api(project(":holdfast-coroutines"))
            api("com.vynatix:hallmark:0.1.0")
            api("com.vynatix:hallmark-coroutines:0.1.0")
            api(project(":holdfast-hallmark"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
