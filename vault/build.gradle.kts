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
        namespace = "com.vynatix.vault"
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        val jvmAndAndroidMain by creating {
            dependsOn(commonMain.get())
        }
        androidMain {
            dependsOn(jvmAndAndroidMain)
        }
        jvmMain {
            dependsOn(jvmAndAndroidMain)
        }

        val jvmAndAndroidHostTest by creating {
            dependsOn(commonTest.get())
        }
        androidHostTest {
            dependsOn(jvmAndAndroidHostTest)
        }
        jvmTest {
            dependsOn(jvmAndAndroidHostTest)
        }

        commonMain.dependencies {
            implementation(libs.atomicfu)
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
