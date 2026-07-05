// Convention plugin: shared publish coordinates for a publishable module.
//
// This carries only the Maven coordinates (group + version). The actual
// publication wiring — KMP/Android publications, POM metadata, GPG signing, and
// the Maven Central (Central Portal) upload/release tasks — lives in
// `holdfast.publish.sonatype`, which applies the vanniktech
// `com.vanniktech.maven.publish` plugin. Every publishable module applies
// `holdfast.publish.sonatype`, which applies this plugin transitively.
//
// Coordinates resolve to `com.vynatix:<module-name>:<version>`, e.g.
//   :holdfast                      → com.vynatix:holdfast:<version>
//   :holdfast-coroutines           → com.vynatix:holdfast-coroutines:<version>
//   :holdfast-compose              → com.vynatix:holdfast-compose:<version>
//   :holdfast-testing              → com.vynatix:holdfast-testing:<version>
//   :holdfast-hallmark             → com.vynatix:holdfast-hallmark:<version>
//   :holdfast-hallmark-coroutines  → com.vynatix:holdfast-hallmark-coroutines:<version>
//
// The version comes from `-Pholdfast.version` (set from the git tag by
// `.github/workflows/publish.yml`), falling back to `0.1.0` for local
// `publishToMavenLocal` smoke tests — never from a committed version file.

group = "com.vynatix"
version = (extra.has("holdfast.version").let { if (it) extra["holdfast.version"] as String else "0.1.0" })
