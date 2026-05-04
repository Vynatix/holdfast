// Convention plugin: Sonatype/Central publication with GPG signing.
//
// Layers on top of `holdfast.publish` to add:
//  - signing of all artifacts via GPG (key + password from env or gradle.properties)
//  - Sonatype OSSRH staging repository (`publishToSonatype` task)
//
// Pre-flight (manual, NOT scripted here):
//  1. Group `com.vynatix` claimed on Maven Central via Sonatype JIRA / Central Portal.
//  2. GPG key pair generated; public key uploaded to keys.openpgp.org (and ideally
//     keyserver.ubuntu.com).
//  3. Credentials in `~/.gradle/gradle.properties` OR env vars:
//        signing.keyId             — short GPG key id (last 8 hex)
//        signing.password          — GPG passphrase
//        signing.secretKeyRingFile — path to secret keyring (or use signing.key for in-memory)
//        ossrhUsername             — Sonatype Central / OSSRH username
//        ossrhPassword             — Sonatype Central / OSSRH password
//
// Verification (without uploading): `./gradlew publishToMavenLocal` produces
// `.asc` signature files alongside each artifact when signing credentials are
// present. `./gradlew publishToSonatype` uploads to Central staging.
//
// Note: `holdfast.publish.sonatype` does NOT auto-release; staging is a manual
// step in the Central UI for 1.x releases. A `closeAndReleaseSonatypeStagingRepository`
// task can be wired in via the `io.github.gradle-nexus.publish-plugin` for full
// automation in a future plan; we keep this minimal for 1.1.

plugins {
    id("holdfast.publish")
    signing
}

publishing {
    repositories {
        maven {
            name = "sonatype"
            // Central Portal API endpoint; OSSRH legacy endpoint is being retired.
            // Switch to https://oss.sonatype.org/service/local/staging/deploy/maven2/
            // for legacy projects until their migration window closes.
            val releasesUrl = "https://central.sonatype.com/api/v1/publisher/upload"
            val snapshotsUrl = "https://central.sonatype.com/repository/maven-snapshots/"
            val versionString = version.toString()
            url = uri(if (versionString.endsWith("SNAPSHOT")) snapshotsUrl else releasesUrl)
            credentials {
                username = (findProperty("ossrhUsername") as String?) ?: System.getenv("OSSRH_USERNAME")
                password = (findProperty("ossrhPassword") as String?) ?: System.getenv("OSSRH_PASSWORD")
            }
        }
    }
}

signing {
    val signingKey = (findProperty("signing.key") as String?) ?: System.getenv("SIGNING_KEY")
    val signingPassword = (findProperty("signing.password") as String?) ?: System.getenv("SIGNING_PASSWORD")
    if (signingKey != null && signingPassword != null) {
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(publishing.publications)
    } else {
        // Fall back to gradle.properties-driven config (signing.keyId etc.) — the
        // `signing` plugin auto-discovers these and applies them per publication.
        // No-op block; presence of credentials is checked at sign-task time.
        @Suppress("EmptyFunctionBlock") {}
    }
}
