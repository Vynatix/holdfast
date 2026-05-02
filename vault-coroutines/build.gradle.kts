plugins {
    id("astrid.kmp.library")
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
