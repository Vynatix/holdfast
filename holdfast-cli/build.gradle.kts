// Internal developer CLI — never published, JVM-only.
//
// `bin/holdfast` (from `./gradlew :holdfast-cli:installDist`) inspects holdfast
// snapshot artifacts offline: decode/validate/diff store snapshot bodies and
// report environment facts. It parses everything through holdfast's PUBLIC API
// (StoreSnapshot.decode/encode/render) — the wire format is never
// reimplemented here, so the CLI cannot drift from the reader it reports on.
//
// Deliberately NOT applied: holdfast.publish.*, holdfast.abi, holdfast.dokka —
// this module is tooling, not an artifact.

import org.gradle.jvm.application.tasks.CreateStartScripts

plugins {
    id("application")
    id("org.jetbrains.kotlin.jvm")
    id("holdfast.quality")
}

val javaTarget = libs.versions.java.target

kotlin {
    jvmToolchain(javaTarget.get().toInt())
}

dependencies {
    implementation(project(":holdfast"))
    implementation(libs.clikt)
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("com.vynatix.holdfast.cli.MainKt")
}

// The launcher is `holdfast`, not the Gradle project name.
tasks.named<CreateStartScripts>("startScripts") {
    applicationName = "holdfast"
}

// The doctor command reports the holdfast version this CLI was built against.
// Generated (not hardcoded) so a version bump in the catalog flows through.
val holdfastVersion = libs.versions.holdfast.get()

val generateBuildInfo =
    tasks.register("generateBuildInfo") {
        val outputFile =
            layout.buildDirectory.file("generated/build-info/com/vynatix/holdfast/cli/build-info.properties")
        inputs.property("holdfastVersion", holdfastVersion)
        outputs.file(outputFile)
        doLast {
            val file = outputFile.get().asFile
            file.parentFile.mkdirs()
            file.writeText("holdfastVersion=$holdfastVersion\n")
        }
    }

sourceSets {
    main {
        resources {
            srcDir(layout.buildDirectory.dir("generated/build-info"))
        }
    }
}

tasks.processResources {
    dependsOn(generateBuildInfo)
}
