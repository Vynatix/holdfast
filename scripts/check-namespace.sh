#!/usr/bin/env bash
# Namespace preflight for com.vynatix on Maven Central.
#
# Answers, as far as it can be answered without secrets, the question
# "can this machine publish com.vynatix to Central today?":
#
#   1. Unauthenticated evidence (always): does the group exist on repo1 /
#      search.maven.org? A 404 group directory with zero search hits means
#      nothing was ever published under com.vynatix — expected before the
#      first release, and it also means ownership CANNOT be proven from here.
#   2. Authenticated proof (optional, recommended): with a Central Portal
#      user token, GET /api/v1/publisher/status?groupId=com.vynatix answers
#      definitively — 200 means the token's account owns the namespace.
#
# Credentials are taken from (first match wins):
#   --user / --password            command line (careful: shell history)
#   ORG_GRADLE_PROJECT_mavenCentralUsername / ...Password   environment
#   mavenCentralUsername / mavenCentralPassword            ~/.gradle/gradle.properties
#
# Exit codes: 0 = OK (or inconclusive with a clear warning),
#             1 = definitive problem (namespace not owned by the token, or
#                 the token itself is rejected).

set -euo pipefail

GROUP="com.vynatix"
PORTAL_API="https://central.sonatype.com/api/v1"
USER=""
PASS=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --user) USER="${2:?--user needs a value}"; shift 2 ;;
        --password) PASS="${2:?--password needs a value}"; shift 2 ;;
        -h|--help)
            sed -n '2,20p' "$0"; exit 0 ;;
        *) echo "check-namespace: unknown argument: $1" >&2; exit 1 ;;
    esac
done

if [[ -z "$USER" && -n "${ORG_GRADLE_PROJECT_mavenCentralUsername:-}" ]]; then
    USER="$ORG_GRADLE_PROJECT_mavenCentralUsername"
    PASS="${ORG_GRADLE_PROJECT_mavenCentralPassword:-}"
fi
if [[ -z "$USER" && -f "$HOME/.gradle/gradle.properties" ]]; then
    USER=$(sed -n 's/^mavenCentralUsername=//p' "$HOME/.gradle/gradle.properties" | tail -1)
    PASS=$(sed -n 's/^mavenCentralPassword=//p' "$HOME/.gradle/gradle.properties" | tail -1)
fi

echo "Namespace preflight: ${GROUP}"
echo "--------------------------------------------------"

problem=0

# (1) Is anything published under the group already?
group_dir_code=$(curl -s -o /dev/null -w '%{http_code}' \
    "https://repo1.maven.org/maven2/${GROUP//.//}/")
num_found=$(curl -s \
    "https://search.maven.org/solrsearch/select?q=g:${GROUP}&rows=5&wt=json" |
    python3 -c 'import json,sys; print(json.load(sys.stdin)["response"]["numFound"])')

if [[ "$group_dir_code" == "200" || "$num_found" != "0" ]]; then
    echo "[OK]   com.vynatix is live on Maven Central (repo1 dir: ${group_dir_code}, search hits: ${num_found})."
    echo "       The namespace is claimed; publishing version X needs only that X is new."
else
    echo "[INFO] Nothing published under com.vynatix yet (repo1 dir: ${group_dir_code}, search hits: ${num_found})."
    echo "       This is expected before the first release, but it also means ownership"
    echo "       cannot be proven without a Central Portal token (see next step)."
fi

# (2) Definitive ownership proof when a token is available.
if [[ -n "$USER" && -n "$PASS" ]]; then
    status_code=$(curl -s -o /tmp/holdfast-ns-status.$$ -w '%{http_code}' \
        -u "$USER:$PASS" "${PORTAL_API}/publisher/status?groupId=${GROUP}" || echo "000")
    body=$(cat "/tmp/holdfast-ns-status.$$" 2>/dev/null || true)
    rm -f "/tmp/holdfast-ns-status.$$"
    case "$status_code" in
        200)
            echo "[OK]   Central Portal token accepted and namespace ${GROUP} is OWNED by this account."
            ;;
        401|403)
            echo "[FAIL] Portal answered ${status_code}: the token is invalid (401) or the account"
            echo "       does not own namespace ${GROUP} (403). Response: ${body:-<empty>}"
            echo "       Claim the namespace once at https://central.sonatype.org/register/namespace/"
            echo "       (requires a verified domain/codeberg-github linkage for vynatix.com or the"
            echo "       github.com/Vynatix account)."
            problem=1
            ;;
        *)
            echo "[WARN] Portal ownership check inconclusive (HTTP ${status_code}). Continuing; the"
            echo "       upload itself will fail loudly if the namespace is not yours."
            ;;
    esac
else
    echo "[WARN] No Central Portal token found (checked --user/--password,"
    echo "       ORG_GRADLE_PROJECT_mavenCentralUsername/Password, ~/.gradle/gradle.properties)."
    echo "       Ownership NOT verified. For the very first release of com.vynatix, verify"
    echo "       manually at https://central.sonatype.com/ that the namespace is claimed."
fi

echo "--------------------------------------------------"
if [[ "$problem" -ne 0 ]]; then
    echo "Verdict: DO NOT PUBLISH — see [FAIL] above."
    exit 1
fi
echo "Verdict: no blocking findings (see any [WARN] lines above)."
