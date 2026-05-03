import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("astrid.kmp.library")
    id("astrid.quality")
    id("astrid.dokka")
    id("astrid.abi")
    id("astrid.publish.sonatype")
}

kotlin {
    android {
        namespace = "com.vynatix.vault.testing"
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            api(project(":vault"))
            api(project(":vault-coroutines"))
            api(project(":vault-validation"))
            api(project(":validation"))
            api(libs.kotest.assertions.core)
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.coroutines.test)
            implementation(libs.atomicfu)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
