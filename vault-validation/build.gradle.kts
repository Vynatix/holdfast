plugins {
    id("astrid.kmp.library")
    id("astrid.quality")
    id("astrid.dokka")
    id("astrid.abi")
    id("astrid.publish.sonatype")
}

kotlin {
    android {
        namespace = "com.vynatix.vault.validation"
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":vault"))
            api(project(":validation"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
