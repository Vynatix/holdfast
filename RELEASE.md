# Releasing Holdfast

Runbook for publishing `com.vynatix:holdfast{,-coroutines,-compose,-lifecycle,-testing}` to
Maven Central. The pipeline is the [vanniktech publication plugin]
(https://vanniktech.github.io/gradle-maven-publish-plugin/) (`com.vanniktech:
gradle-maven-publish-plugin`, applied through the `holdfast.publish` buildSrc
convention), which uploads through the Central Portal publisher API, signs
every artifact with GPG, triggers the release and waits for Central's
validation. Nothing has been published yet; the first release additionally
needs the one-time namespace claim below.

Everything unreleased; the release version comes **only** from the git tag
(`vX.Y.Z`), passed to Gradle as `-Pholdfast.version` — never from a committed
file (the `holdfast` version in `gradle/libs.versions.toml` is a local-dev
fallback only, used when no property is given).

## Prerequisites (one-time)

1. **Central Portal account + namespace.** Account at
   <https://central.sonatype.com>; the namespace `com.vynatix` must be claimed
   and verified there (namespace registration links the GitHub `Vynatix`
   account or the `vynatix.com` domain). Until that is done, every upload for
   this group is rejected. Check any time with `scripts/check-namespace.sh`.
2. **Central Portal user token** (account settings → "Generate User Token").
   Two values, a cryptic username and password — these are the
   `mavenCentralUsername` / `mavenCentralPassword` below.
3. **GPG key.** The repo key (the one that signs commits/tags,
   `D688549C778E5A82`, `osama.raddad@vynatix.com`). Its public key must be
   distributed so Central can verify signatures: upload it to a public keyserver
   (keyserver.ubuntu.com and/or keys.openpgp.org):
   `gpg --keyserver keyserver.ubuntu.com --send-keys D688549C778E5A82`.

## Required secrets

Local machine — put in `~/.gradle/gradle.properties` (never committed) or
export the `ORG_GRADLE_PROJECT_*` environment form:

| Gradle property               | Env var (CI form)                                  | What it is |
| ----------------------------- | -------------------------------------------------- | ---------- |
| `mavenCentralUsername`        | `ORG_GRADLE_PROJECT_mavenCentralUsername`          | Portal user-token username |
| `mavenCentralPassword`        | `ORG_GRADLE_PROJECT_mavenCentralPassword`          | Portal user-token password |
| `signingInMemoryKey`          | `ORG_GRADLE_PROJECT_signingInMemoryKey`            | ASCII-armored GPG secret key (CI) |
| `signingInMemoryKeyId`        | `ORG_GRADLE_PROJECT_signingInMemoryKeyId`          | Key id, e.g. `D688549C778E5A82` (optional) |
| `signingInMemoryKeyPassword`  | `ORG_GRADLE_PROJECT_signingInMemoryKeyPassword`    | GPG passphrase (empty if none) |

Local GPG alternative (recommended locally — no key export): in
`~/.gradle/gradle.properties`

```properties
signing.useGpgCmd=true
signing.gnupg.keyName=D688549C778E5A82
```

The signing plugin then shells out to `gpg`, taking key and passphrase from the
agent (unlock once, `gpg --clearsign /dev/null` to warm the cache). SNAPSHOT
versions skip signing entirely; release versions fail the build without a
working signing setup — by design.

GitHub (repo settings → Secrets, used by `.github/workflows/publish.yml`):
`SONATYPE_USERNAME`, `SONATYPE_PASSWORD` (the Portal token),
`SIGNING_KEY`, `SIGNING_PASSWORD`, `SIGNING_KEY_ID` (export via
`gpg --export-secret-keys --armor <keyid>`, then strip the first line and the
last two lines and join to one line).

## Local release path (canonical)

```bash
scripts/release.sh 0.2.0              # check -> preflights -> tag -> publish -> verify
scripts/release.sh 0.2.0 --dry-run    # everything except tag + upload
scripts/release.sh 0.2.0 --tag-only   # stop after the signed tag (let CI publish)
```

What it does, in order, failing loudly at every step:

1. Preflight: SemVer validation (same rule as publish.yml), **clean working
   tree**, HEAD == `origin/main`, tag `vX.Y.Z` not taken.
2. `scripts/check-namespace.sh` — Central namespace preflight (see below).
3. Secrets preflight: refuses to start a 20-minute build that can only fail at
   upload because a token is missing; names the exact missing properties.
4. `./gradlew check` — the full CI gate (tests, apiCheck, detekt, ktlint,
   dokka, doc-snippets).
5. `git tag -s vX.Y.Z` — **signed** tag; the signature is verified immediately.
6. `./gradlew publishAndReleaseToMavenCentral -Pholdfast.version=X.Y.Z` —
   uploads all five modules, triggers release, waits for Central validation.
7. Verifies `https://repo1.maven.org/maven2/com/vynatix/...` serves the `.pom`
   and `.module` of all five artifacts (retries; Central→repo1 sync usually
   takes a few minutes, default timeout 30 min, `HOLDFAST_VERIFY_TIMEOUT`
   overrides).

Local sanity check without touching Central:

```bash
./gradlew publishToMavenLocal -Pholdfast.version=0.2.0
ls ~/.m2/repository/com/vynatix/
```

## CI path (tag push)

Pushing a tag `v*` to `origin` triggers `.github/workflows/publish.yml`
(macos-15 — needed for the iOS artifacts): derives the version from the tag,
runs the namespace preflight, runs `allTests` + `apiCheck` for the five
modules, then `publishAndReleaseToMavenCentral`. It uses the same tasks,
properties and secrets names as the local path. Use `--tag-only` locally to
delegate the upload to CI.

## Namespace preflight

`scripts/check-namespace.sh` prints:

- whether anything under `com.vynatix` is already visible on repo1 /
  search.maven.org (nothing published yet → expected before the first release),
- with a Portal token available (env or `~/.gradle/gradle.properties`): a
  definitive ownership check via the Portal's
  `GET /api/v1/publisher/status?groupId=com.vynatix` (200 = owned, 403 = not
  yours, 401 = bad token). Without a token it degrades to a clear warning —
  unauthenticated checks cannot prove ownership.

## Rollback / abort

- **Before release** (upload done, deployment still in `publishing`/`validated`
  state): `./gradlew dropMavenCentralDeployment` (vanniktech task) or the
  "Drop" button at <https://central.sonatype.com/publishing/deployments>.
  Nothing ever reached repo1; the version number stays usable.
- **After release** (artifacts visible on repo1): immutable. Central does not
  delete released versions. Ship `X.Y.Z+1` (or `X.Y.(Z+1)`) and, if the bad
  version is actively harmful, request a deletion through Sonatype support.
  Never re-publish the same version.
- A local tag that has not been pushed: `git tag -d vX.Y.Z`. Once pushed and
  released, the tag must stay (it names an immutable artifact).

## DO NOT

- **Never publish from a dirty tree.** `scripts/release.sh` enforces it;
  `publish.yml` always builds the tagged commit.
- **Never take the version from a committed file.** Only from the tag, via
  `-Pholdfast.version` (scripts/release.sh and publish.yml both do this).
- **Never publish the hallmark modules** (`holdfast-hallmark*`): they depend on
  the mavenLocal-only `com.vynatix:hallmark` and stay behind
  `-Pholdfast.includeHallmark=true`, out of the default publish set.
- **Never disable signing** or ship an unsigned artifact; Central rejects it
  and the build is wired to fail first.
- **Never commit secrets.** `~/.gradle/gradle.properties` only, or CI secrets.
- **Never re-publish a released version**, and never rewrite history for a tag
  that has been released.
- Do not publish `:doc-snippets` / `:wasm-tests` — internal, not wired to the
  publish convention.

## Notes

- Changelogs (`holdfast/CHANGELOG.md`, `holdfast-testing/CHANGELOG.md`) document
  *library behavior*; release-engineering changes are recorded here and in git
  history, not in the module changelogs.
- 0.x is pre-stable: breaking changes may ship in a minor bump — say so in the
  release notes and mark the changelog section accordingly.
