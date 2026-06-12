pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "Holdfast"
include(":holdfast")
include(":holdfast-coroutines")
include(":holdfast-compose")
include(":holdfast-testing")
// Internal doc-snippet harness (never published): compiles every fenced Kotlin
// block of the user-facing docs and fails `check` when docs and twins drift.
include(":doc-snippets")

// The hallmark modules depend on com.vynatix:hallmark, which is not yet on
// Maven Central and only resolves from mavenLocal (publish the sibling
// https://github.com/vynatix/hallmark repo first). Keep them out of the
// default build so a fresh clone can `./gradlew check`.
if (providers.gradleProperty("holdfast.includeHallmark").orNull?.toBoolean() == true) {
    include(":holdfast-hallmark")
    include(":holdfast-hallmark-coroutines")
}
