plugins {
    id("astrid.kmp.library")
    id("astrid.quality")
}

kotlin {
    android {
        namespace = "com.vynatix.vault"
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.atomicfu)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
