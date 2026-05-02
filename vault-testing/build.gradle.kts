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

    sourceSets {
        commonMain.dependencies {
            api(project(":vault"))
            api(project(":vault-coroutines"))
            api(libs.kotest.assertions.core)
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.coroutines.test)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
