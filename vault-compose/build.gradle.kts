plugins {
    id("astrid.kmp.library")
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
