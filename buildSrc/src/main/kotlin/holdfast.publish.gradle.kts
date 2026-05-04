// Convention plugin: configures `maven-publish` for a KMP library module.
//
// Apply alongside `holdfast.kmp.library` on any module that should publish Maven
// artifacts. Coordinates are derived from the module's path:
//   :holdfast                      → com.vynatix:holdfast:<version>
//   :holdfast-coroutines           → com.vynatix:holdfast-coroutines:<version>
//   :holdfast-compose              → com.vynatix:holdfast-compose:<version>
//   :holdfast-testing              → com.vynatix:holdfast-testing:<version>
//   :holdfast-hallmark             → com.vynatix:holdfast-hallmark:<version>
//   :holdfast-hallmark-coroutines  → com.vynatix:holdfast-hallmark-coroutines:<version>
//
// Run `./gradlew publishToMavenLocal` to verify the resulting POMs and JARs in
// ~/.m2/repository/com/vynatix/. For Sonatype/staging publication, an additional
// signing convention (out of scope for 1.0) layers on top of this plugin.

plugins {
    `maven-publish`
}

group = "com.vynatix"
version = (extra.has("holdfast.version").let { if (it) extra["holdfast.version"] as String else "0.1.0" })

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
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
            }
        }
    }
    repositories {
        mavenLocal()
    }
}
