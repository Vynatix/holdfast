// Convention plugin: publishes a KMP library module to Maven Central through
// the vanniktech plugin (com.vanniktech:gradle-maven-publish-plugin), which
// drives the Central Portal publisher API. This replaces the former homegrown
// stack (plain `maven-publish` pointed at the Portal upload endpoint — a
// non-Maven HTTP API it cannot speak) and the broken
// `publishAndReleaseToMavenCentral` invocation in publish.yml.
//
// Apply alongside `holdfast.kmp.library` and `holdfast.dokka` (the javadoc jar
// is Dokka's publication HTML) on any module that should publish Maven
// artifacts. Coordinates are derived from the module's path:
//   :holdfast                       → com.vynatix:holdfast:<version>
//   :holdfast-coroutines            → com.vynatix:holdfast-coroutines:<version>
//   :holdfast-compose               → com.vynatix:holdfast-compose:<version>
//   :holdfast-testing               → com.vynatix:holdfast-testing:<version>
//   :holdfast-debug                 → com.vynatix:holdfast-debug:<version>
//   :holdfast-hallmark, :holdfast-hallmark-coroutines (only included with
//     -Pholdfast.includeHallmark=true) are wired the same way but are not part
//     of the publish set — see RELEASE.md.
//
// Version comes from `-Pholdfast.version` (scripts/release.sh and publish.yml
// derive it from the git tag); the catalog version below is only a local-dev
// fallback, never a release source.
//
// Tasks:
//   ./gradlew publishToMavenLocal -Pholdfast.version=X.Y.Z
//   ./gradlew publishAndReleaseToMavenCentral -Pholdfast.version=X.Y.Z
//   ./gradlew dropMavenCentralDeployment   # abort a not-yet-released upload
//
// Secrets (env `ORG_GRADLE_PROJECT_<name>` or ~/.gradle/gradle.properties):
//   mavenCentralUsername / mavenCentralPassword          Central Portal token
//   signingInMemoryKey / signingInMemoryKeyId /
//   signingInMemoryKeyPassword                           CI GPG signing
//   signing.useGpgCmd (+ signing.gnupg.keyName)          local GPG signing via
//                                                        the gpg agent; the
//                                                        standard signing.*
//                                                        properties also work
// Full runbook: RELEASE.md.

import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.plugins.signing.SigningExtension

plugins {
    id("com.vanniktech.maven.publish.base")
}

group = "com.vynatix"
// `libs`-style accessors are ambiguous inside precompiled script plugins once
// buildSrc's own script uses the catalog too, so resolve it explicitly.
val versionCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
version = providers.gradleProperty("holdfast.version")
    .orElse(versionCatalog.findVersion("holdfast").get().requiredVersion)
    .get()

val pomMetadata = mapOf(
    "holdfast" to ("Holdfast" to
        "Transactional state containers for Kotlin Multiplatform: atomic commits, savepoints, middleware bridges."),
    "holdfast-coroutines" to ("Holdfast Coroutines" to
        "Flow / StateFlow / suspend integration for the Holdfast Store library."),
    "holdfast-compose" to ("Holdfast Compose" to
        "Compose Multiplatform runtime adapter for the Holdfast Store library."),
    "holdfast-testing" to ("Holdfast Testing" to
        "Testing harness for the Holdfast Store library: storeTest {}, tracked timelines, infix matchers."),
    "holdfast-debug" to ("Holdfast Debug" to
        "On-device debugging console for Holdfast stores, driven from adb or HoldfastDebug.execute()."),
    "holdfast-hallmark" to ("Holdfast Hallmark" to
        "Hallmark-backed validating transformers and boxed states for the Holdfast Store library."),
    "holdfast-hallmark-coroutines" to ("Holdfast Hallmark Coroutines" to
        "Suspend-validate-and-mutate integration over Holdfast, coroutines and Hallmark."),
)
val defaultMetadata = "Holdfast" to
    "Transactional state containers for Kotlin Multiplatform."
val (pomName, pomDescription) = pomMetadata[project.name] ?: defaultMetadata

mavenPublishing {
    publishToMavenCentral()

    // Central rejects unsigned artifacts; signing is skipped only for
    // -SNAPSHOT versions (the plugin wires that rule).
    signAllPublications()

    // KMP: one javadoc jar per publication, filled from Dokka's publication
    // HTML (Dokka V2 task; `dokkaHtml` no longer exists).
    configureBasedOnAppliedPlugins(
        javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationHtml"),
        sourcesJar = SourcesJar.Sources(),
    )

    pom {
        name.set(pomName)
        description.set(pomDescription)
        url.set("https://github.com/vynatix/holdfast")
        licenses {
            license {
                name.set("Apache-2.0")
                url.set("https://opensource.org/licenses/Apache-2.0")
            }
        }
        developers {
            developer {
                id.set("vynatix")
                name.set("Vynatix")
                organization.set("Vynatix")
                organizationUrl.set("https://vynatix.com")
            }
            developer {
                id.set("osama-raddad")
                name.set("Osama Raddad")
                email.set("front.desk@vynatix.com")
            }
        }
        scm {
            url.set("https://github.com/vynatix/holdfast")
            connection.set("scm:git:git://github.com/vynatix/holdfast.git")
            developerConnection.set("scm:git:ssh://git@github.com/vynatix/holdfast.git")
        }
    }
}

// Local signing without exporting the key: with `signing.useGpgCmd=true`
// (optionally `signing.gnupg.keyName=<id>`) in ~/.gradle/gradle.properties
// the standard signing plugin shells out to gpg, which resolves the repo key
// and passphrase from the agent. CI instead provides the exported key through
// `signingInMemoryKey` / `signingInMemoryKeyId` / `signingInMemoryKeyPassword`.
if (providers.gradleProperty("signing.useGpgCmd").map(String::toBoolean).getOrElse(false)) {
    extensions.configure<SigningExtension> { useGpgCmd() }
}
