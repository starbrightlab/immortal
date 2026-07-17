#!/usr/bin/env bash
#
# Deploy a locally-built APK to a Portal via its Fleet Agent — no fleetctl / rustc required.
#
#   scripts/fleet-deploy.sh --to <fleet-name> --apk <path> [--package PKG] [--no-pause]
#
# Uses the fleet agent HTTP API directly (curl). Reads device ip/port/token from
# provisioning/fleet/<serial>.<name>.json — same registry fleetctl uses. Safe to run
# from a machine that has no Rust toolchain (fleetctl needs rustc; this doesn't).
#
# What it does — mirrors fleetctl's `dev update`:
#   1. POST /dev {enabled:true}     — pause the official self-updater on the target
#      (so the pushed build isn't clobbered on next check-in).
#   2. POST /fs/write?path=<remote> — push APK bytes to a scratch path the target
#      agent can write. Default path lives under the *agent's own* app files dir,
#      so cross-package installs (e.g. installing release via the LIFEBOAT agent)
#      still succeed on Android 9's legacy storage.
#   3. POST /install {path, packageName}
#                                   — trigger PackageInstaller on the pushed APK.
#      Signature must match — the release keystore signs both variants (see
#      docs/releasing.md#signing).
#
# Exit codes: 0 ok · 1 transport/HTTP error · 2 usage error.

set -euo pipefail

usage() {
  cat >&2 <<EOF
Usage: $0 --to <fleet-name> --apk <path> [--package PKG] [--no-pause]

  --to        Friendly name in provisioning/fleet/*.json (e.g. star, star-eye)
  --apk       Local APK path
  --package   Package name to install (default: com.immortal.launcher)
  --no-pause  Skip the pause-self-updater step (POST /dev enabled:true)
EOF
  exit 2
}

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FLEET_DIR="${IMMORTAL_FLEET_DIR:-$REPO_ROOT/provisioning/fleet}"

TO=""; APK=""; PKG="com.immortal.launcher"; NO_PAUSE=0
while [ $# -gt 0 ]; do
  case "$1" in
    --to)      TO="$2"; shift 2 ;;
    --apk)     APK="$2"; shift 2 ;;
    --package) PKG="$2"; shift 2 ;;
    --no-pause) NO_PAUSE=1; shift ;;
    -h|--help) usage ;;
    *) echo "unknown arg: $1" >&2; usage ;;
  esac
done
[ -n "$TO" ] && [ -n "$APK" ] || usage
[ -f "$APK" ] || { echo "APK not found: $APK" >&2; exit 2; }
command -v jq >/dev/null || { echo "jq required" >&2; exit 2; }
command -v curl >/dev/null || { echo "curl required" >&2; exit 2; }

# Registry lookup — friendly name is case-insensitive.
match=$(grep -lFi "\"name\": \"$TO\"" "$FLEET_DIR"/*.json 2>/dev/null || true)
if [ -z "$match" ]; then
  echo "no fleet entry named '$TO' under $FLEET_DIR" >&2
  echo "known:" >&2
  jq -r '.name' "$FLEET_DIR"/*.json 2>/dev/null | sed 's/^/  - /' >&2
  exit 2
fi
[ "$(echo "$match" | wc -l)" -eq 1 ] || { echo "ambiguous name '$TO': $match" >&2; exit 2; }

IP=$(jq -r .ip "$match")
PORT=$(jq -r .agentPort "$match")
TOKEN=$(jq -r .token "$match")
AGENT_PKG=$(jq -r '.agentPackage // "com.immortal.launcher"' "$match")

# Scratch path under the AGENT's own files dir — guaranteed writable regardless of
# whether the target package is currently installed.
REMOTE="/sdcard/Android/data/$AGENT_PKG/files/dev/fleet-deploy-install.apk"

BASE="http://$IP:$PORT"
AUTH="Authorization: Bearer $TOKEN"

echo "→ target: $TO ($BASE)  install-pkg: $PKG  via agent-pkg: $AGENT_PKG"

if [ "$NO_PAUSE" -eq 0 ]; then
  code=$(curl -sk -o /tmp/fleet-deploy.dev.$$ -w '%{http_code}' \
    -X POST -H "$AUTH" -H 'Content-Type: application/json' \
    -d '{"enabled":true}' "$BASE/dev")
  if [ "$code" -ge 200 ] && [ "$code" -lt 300 ]; then
    echo "  dev mode: on (self-updater paused)"
  else
    echo "  WARN: /dev returned $code — continuing anyway" >&2
  fi
  rm -f /tmp/fleet-deploy.dev.$$
fi

echo "→ pushing $(stat -c%s "$APK") bytes to $REMOTE"
encoded_path=$(jq -rn --arg p "$REMOTE" '$p|@uri')
code=$(curl -sk -o /tmp/fleet-deploy.push.$$ -w '%{http_code}' \
  -X POST -H "$AUTH" --data-binary "@$APK" \
  "$BASE/fs/write?path=$encoded_path")
if [ "$code" -lt 200 ] || [ "$code" -ge 300 ]; then
  echo "  push failed ($code): $(cat /tmp/fleet-deploy.push.$$)" >&2
  rm -f /tmp/fleet-deploy.push.$$
  exit 1
fi
rm -f /tmp/fleet-deploy.push.$$

echo "→ installing $PKG"
body=$(jq -n --arg path "$REMOTE" --arg pkg "$PKG" '{path:$path, packageName:$pkg}')
code=$(curl -sk -o /tmp/fleet-deploy.install.$$ -w '%{http_code}' \
  -X POST -H "$AUTH" -H 'Content-Type: application/json' \
  -d "$body" "$BASE/install")
echo "  install response ($code):"
cat /tmp/fleet-deploy.install.$$ | jq . 2>/dev/null || cat /tmp/fleet-deploy.install.$$
echo
rm -f /tmp/fleet-deploy.install.$$
[ "$code" -ge 200 ] && [ "$code" -lt 300 ] || exit 1

echo "✓ install triggered — allow ~5s for PackageInstaller, then probe target port."
