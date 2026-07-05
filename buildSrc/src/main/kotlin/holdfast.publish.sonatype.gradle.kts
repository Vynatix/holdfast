// Convention plugin: Maven Central (Central Portal) publication with GPG signing,
// via the vanniktech `com.vanniktech.maven.publish` plugin.
//
// We apply the *base* variant (`com.vanniktech.maven.publish.base`) so the POM,
// coordinates, host, and signing are configured entirely from this DSL rather
// than from `gradle.properties` (`POM_*` / `SONATYPE_HOST` keys). The base
// plugin auto-creates the per-target KMP/Android publications for us.
//
// Tasks this exposes (used by `.github/workflows/publish.yml`):
//  - `publishToMavenLocal`               — smoke test; unsigned unless a signing
//                                           key is present (see guard below).
//  - `publishAllPublicationsToMavenCentralRepository` — upload a bundle to the
//                                           Central Portal.
//  - `publishAndReleaseToMavenCentral`   — upload AND automatically release.
//
// Credentials (Central Portal user token + in-memory GPG key) are read by the
// plugin from these Gradle properties, conventionally supplied as
// `ORG_GRADLE_PROJECT_*` env vars in CI:
//        mavenCentralUsername / mavenCentralPassword
//        signingInMemoryKey / signingInMemoryKeyPassword / signingInMemoryKeyId
//
// Signing is only wired when key material is actually present, so
// `./gradlew publishToMavenLocal -Pholdfast.version=<v>` succeeds UNSIGNED for
// local verification; CI (which sets `signingInMemoryKey`) publishes signed.

import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform

plugins {
    id("holdfast.publish")
    id("com.vanniktech.maven.publish.base")
}

// Present when `signingInMemoryKey` is supplied via -P or the
// `ORG_GRADLE_PROJECT_signingInMemoryKey` env var (Gradle maps the latter to a
// project property). Absent for a plain local `publishToMavenLocal`.
val hasSigningKey = providers.gradleProperty("signingInMemoryKey").isPresent

mavenPublishing {
    // No-arg: Central Portal host. Release is driven by the
    // `publishAndReleaseToMavenCentral` task (used by publish.yml).
    publishToMavenCentral()

    if (hasSigningKey) {
        signAllPublications()
    }

    // Base plugin: we must opt each publishable module (all KMP) into the
    // platform's publication wiring. `JavadocJar.Empty()` attaches the
    // Central-required javadoc jar without coupling the publish path to a full
    // Dokka run (API docs are generated separately via `dokkaGenerate`).
    configure(KotlinMultiplatform(javadocJar = JavadocJar.Empty()))

    coordinates(group.toString(), project.name, version.toString())

    pom {
        name.set(project.name)
        description.set("Transactional state containers for Kotlin Multiplatform.")
        inceptionYear.set("2025")
        url.set("https://github.com/vynatix/holdfast")
        licenses {
            license {
                name.set("Apache-2.0")
                url.set("https://opensource.org/licenses/Apache-2.0")
                distribution.set("https://opensource.org/licenses/Apache-2.0")
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
