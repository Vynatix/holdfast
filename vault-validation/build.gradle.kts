plugins {
    id("astrid.kmp.library")
    id("astrid.quality")
    id("astrid.dokka")
    id("astrid.abi")
    id("astrid.publish")
}

kotlin {
    android {
        namespace = "com.vynatix.vault.validation"
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":vault"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
