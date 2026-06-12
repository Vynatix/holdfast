// Internal documentation-snippet harness — never published, JVM-only.
//
// Twin files under src/test/kotlin/.../snippets/twins embed every fenced
// ```kotlin block of the user-facing docs verbatim inside a compilable
// scaffold; the README quick-start twins also execute their block and assert
// the output the doc claims. DocSnippetDriftTest fails `check` when a doc
// block has no up-to-date twin and no entry in snippet-exclusions.txt, so a
// doc edit that breaks a snippet breaks the build naming the doc and block.
//
// Deliberately NOT applied: holdfast.publish.*, holdfast.abi, holdfast.dokka —
// this module is build infrastructure, not an artifact.

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("holdfast.quality")
}

val javaTarget = libs.versions.java.target

kotlin {
    jvmToolchain(javaTarget.get().toInt())
}

dependencies {
    testImplementation(project(":holdfast"))
    testImplementation(project(":holdfast-coroutines"))
    testImplementation(project(":holdfast-compose"))
    testImplementation(project(":holdfast-testing"))
    testImplementation(kotlin("test"))
}

tasks.test {
    // DocSnippetDriftTest reads the tracked docs and the exclusion list at
    // runtime; declare them as inputs so a doc edit re-runs the verification
    // instead of leaving the task UP-TO-DATE.
    inputs
        .files(
            rootProject.layout.projectDirectory.file("README.md"),
            rootProject.layout.projectDirectory.file("holdfast/README.md"),
            rootProject.layout.projectDirectory.file("holdfast/GUIDE.md"),
            rootProject.layout.projectDirectory.file("holdfast-coroutines/README.md"),
            rootProject.layout.projectDirectory.file("holdfast-compose/README.md"),
            layout.projectDirectory.file("snippet-exclusions.txt"),
        ).withPathSensitivity(PathSensitivity.RELATIVE)
}

ktlint {
    filter {
        // Twin files reproduce doc blocks verbatim; their style is the docs'
        // teaching style (inline comments, semicolons), not ktlint's. The
        // extractor, drift test, and helpers stay ktlint-checked.
        exclude { it.file.path.contains("snippets/twins") }
    }
}
