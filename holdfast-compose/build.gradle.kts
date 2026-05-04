import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("astrid.kmp.library")
    id("astrid.kmp.jvm")
    id("astrid.kmp.wasmJs")
    id("astrid.compose.multiplatform")
    id("astrid.quality")
    id("astrid.dokka")
    id("astrid.abi")
    id("astrid.publish.sonatype")
}

kotlin {
    android {
        namespace = "com.vynatix.vault.compose"
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
            implementation(compose.runtime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
