#!/usr/bin/env bash
# Local release pipeline for com.vynatix:holdfast* on Maven Central.
#
#   check -> namespace preflight -> secrets preflight -> (signed) tag ->
#   publish + release via the vanniktech Central Portal task ->
#   verify repo1.maven.org serves the new artifacts.
#
# Usage:
#   scripts/release.sh <version> [options]     e.g. scripts/release.sh 0.2.0
#
#   --dry-run          run everything EXCEPT creating the tag and uploading
#                      (check, preflights and printing of the exact commands)
#   --tag-only         stop after creating the signed tag; no upload. Use when
#                      you want CI (publish.yml, on tag push) to do the upload.
#   --skip-check       skip the full `./gradlew check` gate (not recommended;
#                      the CI path always runs it)
#   --allow-non-main   permit running from a commit that is not origin/main
#                      (loudly flagged; for testing the pipeline itself —
#                      real releases cut from main only)
#   --skip-verify      do not wait for repo1.maven.org propagation
#   --verify-timeout S seconds to wait for Central -> repo1 sync
#                      (default 1800; Central usually lands within minutes)
#
# Required secrets (env ORG_GRADLE_PROJECT_<name> or ~/.gradle/gradle.properties):
#   mavenCentralUsername / mavenCentralPassword        Central Portal user token
#   signing: EITHER signingInMemoryKey (+ signingInMemoryKeyId/KeyPassword)
#            OR signing.useGpgCmd=true (+ signing.gnupg.keyName) — local gpg
# See RELEASE.md for the full runbook, prerequisites and the DO-NOT list.

set -euo pipefail

VERSION=""
DRY_RUN=false
TAG_ONLY=false
SKIP_CHECK=false
SKIP_VERIFY=false
ALLOW_NON_MAIN=false
VERIFY_TIMEOUT="${HOLDFAST_VERIFY_TIMEOUT:-1800}"

REPO_ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$REPO_ROOT"

GROUP_PATH="com/vynatix"
ARTIFACTS=(holdfast holdfast-coroutines holdfast-compose holdfast-lifecycle holdfast-testing)

usage() { grep -E '^#( |$)' "$0" | sed 's/^# \{0,1\}//'; }

while [[ $# -gt 0 ]]; do
    case "$1" in
        --dry-run) DRY_RUN=true; shift ;;
        --tag-only) TAG_ONLY=true; shift ;;
        --skip-check) SKIP_CHECK=true; shift ;;
        --allow-non-main) ALLOW_NON_MAIN=true; shift ;;
        --skip-verify) SKIP_VERIFY=true; shift ;;
        --verify-timeout) VERIFY_TIMEOUT="${2:?--verify-timeout needs a value}"; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        -*) echo "release: unknown option: $1" >&2; usage >&2; exit 1 ;;
        *)
            if [[ -n "$VERSION" ]]; then
                echo "release: version already set to ${VERSION}" >&2; exit 1
            fi
            VERSION="$1"; shift ;;
    esac
done

if [[ -z "$VERSION" ]]; then
    echo "release: missing <version> (e.g. scripts/release.sh 0.2.0)" >&2
    usage >&2
    exit 1
fi

tag="v${VERSION}"

step() { printf '\n== %s ==\n' "$*"; }
die() { echo "RELEASE ABORTED: $*" >&2; exit 1; }

# --- 1. Version + repository state -----------------------------------------
step "Preflight: version ${VERSION}, tag ${tag}"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9.]+)?$ ]] ||
    die "version '${VERSION}' is not SemVer (X.Y.Z[-pre], same rule as publish.yml)"

[[ -z "$(git status --porcelain)" ]] ||
    die "working tree is dirty — never publish from a dirty tree (see RELEASE.md)"
git fetch origin --quiet
if [[ "$(git rev-parse HEAD)" != "$(git rev-parse origin/main)" ]]; then
    if [[ "$ALLOW_NON_MAIN" == true ]]; then
        echo "[WARN] --allow-non-main: HEAD ($(git rev-parse --short HEAD)) is not origin/main" \
            "($(git rev-parse --short origin/main)) — proceeding because it was requested."
    else
        die "HEAD is not origin/main ($(git rev-parse --short HEAD) vs $(git rev-parse --short origin/main)) — merge/rebase first (or --allow-non-main to override)"
    fi
fi
git tag -l "$tag" | grep -q . && die "tag ${tag} already exists — pick a new version" || true
echo "clean tree on origin/main, tag ${tag} is free"

# --- 2. Namespace preflight -------------------------------------------------
step "Namespace preflight (scripts/check-namespace.sh)"
"$REPO_ROOT/scripts/check-namespace.sh"

# --- 3. Secrets preflight ---------------------------------------------------
step "Secrets preflight"
gradle_props="${HOME}/.gradle/gradle.properties"
prop_or_env() { # $1 = gradle property name
    local env_name="ORG_GRADLE_PROJECT_${1}"
    if [[ -n "${!env_name:-}" ]]; then echo env; return 0; fi
    if [[ -f "$gradle_props" ]] && grep -q "^${1}=" "$gradle_props"; then echo file; return 0; fi
    return 1
}
if [[ "$DRY_RUN" != true ]]; then
    for prop in mavenCentralUsername mavenCentralPassword; do
        prop_or_env "$prop" >/dev/null ||
            die "missing '${prop}' — provide it via env ORG_GRADLE_PROJECT_${prop} or ${gradle_props}"
    done
    if prop_or_env signingInMemoryKey >/dev/null; then
        echo "signing: signingInMemoryKey found ($(prop_or_env signingInMemoryKey))"
    elif prop_or_env signing.useGpgCmd >/dev/null; then
        command -v gpg >/dev/null || die "signing.useGpgCmd is set but gpg is not on PATH"
        echo "signing: gpg command mode ($(prop_or_env signing.useGpgCmd))"
    elif prop_or_env signing.keyId >/dev/null; then
        echo "signing: signing.keyId found ($(prop_or_env signing.keyId))"
    else
        die "no signing configuration found — need signingInMemoryKey (env) or signing.useGpgCmd/signing.keyId in ${gradle_props}"
    fi
    echo "secrets: OK"
else
    echo "(dry run: secrets preflight skipped)"
fi

# --- 4. Full check gate -----------------------------------------------------
step "Build gate: ./gradlew check"
if [[ "$SKIP_CHECK" == true ]]; then
    echo "skipped (--skip-check)"
else
    ./gradlew check
fi

# --- 5. Tag -----------------------------------------------------------------
step "Tag ${tag} (signed)"
if [[ "$DRY_RUN" == true ]]; then
    echo "(dry run) would run: git tag -s ${tag} -m \"Holdfast ${tag}\""
else
    git tag -s "$tag" -m "Holdfast ${tag}"
    git tag -v "$tag" >/dev/null || die "created tag ${tag} but its signature does not verify"
    echo "created and verified signed tag ${tag}"
fi
[[ "$TAG_ONLY" == true ]] && { echo "--tag-only: stopping before publish"; exit 0; }

# --- 6. Publish + release ---------------------------------------------------
step "Publish ${VERSION} to Maven Central (publishAndReleaseToMavenCentral)"
publish_cmd=(./gradlew publishAndReleaseToMavenCentral "-Pholdfast.version=${VERSION}" --no-configuration-cache)
if [[ "$DRY_RUN" == true ]]; then
    echo "(dry run) would run: ${publish_cmd[*]}"
else
    "${publish_cmd[@]}"
fi

# --- 7. Verify on repo1.maven.org -------------------------------------------
step "Verify artifacts on repo1.maven.org"
verify_artifacts() {
    local missing=0 url code artifact kind
    for artifact in "${ARTIFACTS[@]}"; do
        for kind in pom module; do
            url="https://repo1.maven.org/maven2/${GROUP_PATH}/${artifact}/${VERSION}/${artifact}-${VERSION}.${kind}"
            code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 30 "$url" || echo "000")
            [[ "$code" == "200" ]] || { echo "  waiting: ${artifact}-${VERSION}.${kind} (HTTP ${code})"; missing=1; }
        done
    done
    return "$missing"
}
if [[ "$SKIP_VERIFY" == true ]]; then
    echo "skipped (--skip-verify)"
elif [[ "$DRY_RUN" == true ]]; then
    echo "(dry run) would poll https://repo1.maven.org/maven2/${GROUP_PATH}/{${ARTIFACTS[*]}}/${VERSION}/ for .pom and .module (timeout ${VERIFY_TIMEOUT}s)"
else
    deadline=$(( $(date +%s) + VERIFY_TIMEOUT ))
    until verify_artifacts; do
        if [[ "$(date +%s)" -ge "$deadline" ]]; then
            die "artifacts of ${VERSION} not visible on repo1.maven.org after ${VERIFY_TIMEOUT}s. The upload may still be syncing — re-check manually: scripts/release.sh --verify-only is not implemented; curl https://repo1.maven.org/maven2/${GROUP_PATH}/holdfast/${VERSION}/"
        fi
        sleep 30
    done
    echo "all four artifacts' .pom and .module files are live on repo1.maven.org"
fi

step "Done"
if [[ "$DRY_RUN" == true ]]; then
    echo "Dry run complete — nothing was tagged or published. Push the tag with: git push origin ${tag}"
else
    echo "Released ${VERSION}. Don't forget: git push origin ${tag} (if CI should also see it) and the changelog."
fi
