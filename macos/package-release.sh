#!/usr/bin/env bash
#
# Verify an externally signed and notarized Portal Manager Release candidate.
#
# Usage:
#   macos/package-release.sh /path/to/PortalManager.app /path/to/notarization.json
#   macos/package-release.sh --check-tools
#
# This script never submits data to Apple and never reads credentials. The
# candidate must already have passed notarization in the operator's release
# environment; the local gate only validates the resulting artifact.
set -euo pipefail

readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly EXPECTED_BUNDLE_ID="com.starbrightlab.portalmanager"

fail() {
  printf 'Portal Manager Release verification failed: %s\n' "$*" >&2
  exit 1
}

[[ "$(uname -s)" == "Darwin" ]] || fail "Release verification requires macOS."

if [[ "${1:-}" == "--check-tools" ]]; then
  [[ $# -eq 1 ]] || fail "--check-tools takes no other arguments"
  for tool in /usr/bin/codesign /usr/sbin/spctl /usr/bin/stapler \
      /usr/libexec/PlistBuddy /usr/bin/shasum /usr/bin/xcrun; do
    [[ -x "$tool" ]] || fail "missing verification tool: $tool"
  done
  printf '{"codesign":true,"gatekeeper":true,"stapler":true,"plist":true,"sha256":true}\n'
  exit 0
fi

[[ $# -eq 2 ]] || fail "usage: macos/package-release.sh APP_PATH TICKET_PATH"

app_path="$1"
ticket_path="$2"
[[ -d "$app_path" ]] || fail "APP_PATH must be an existing .app directory: $app_path"
[[ -f "$ticket_path" ]] || fail "TICKET_PATH must be an existing file: $ticket_path"

/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' \
  "$app_path/Contents/Info.plist" > /dev/null ||
  fail "missing or malformed Info.plist"
bundle_id="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$app_path/Contents/Info.plist")"
[[ "$bundle_id" == "$EXPECTED_BUNDLE_ID" ]] || fail "unexpected bundle id: $bundle_id"

/usr/bin/codesign --verify --strict --verbose=2 "$app_path"
/usr/sbin/spctl --assess --type execute --verbose=2 "$app_path"
/usr/bin/stapler validate "$app_path"

version="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$app_path/Contents/Info.plist")"
build="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleVersion' "$app_path/Contents/Info.plist")"
zip_path="${app_path%/}.zip"
if [[ -f "$zip_path" ]]; then
  artifact_sha256="$(/usr/bin/shasum -a 256 "$zip_path" | awk '{print $1}')"
else
  artifact_sha256="unavailable"
fi

printf '{\n'
printf '  "bundleIdentifier": "%s",\n' "$EXPECTED_BUNDLE_ID"
printf '  "version": "%s",\n' "$version"
printf '  "build": "%s",\n' "$build"
printf '  "codesignStrict": true,\n'
printf '  "gatekeeperAssessment": true,\n'
printf '  "notarizationTicket": true,\n'
printf '  "artifactSha256": "%s"\n' "$artifact_sha256"
printf '}\n'
