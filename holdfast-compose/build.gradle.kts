import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("holdfast.kmp.library")
    id("holdfast.kmp.jvm")
    id("holdfast.kmp.wasmJs")
    id("holdfast.compose.multiplatform")
    id("holdfast.quality")
    id("holdfast.dokka")
    id("holdfast.abi")
    id("holdfast.publish.sonatype")
}

kotlin {
    android {
        namespace = "com.vynatix.holdfast.compose"
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
            implementation(compose.runtime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
