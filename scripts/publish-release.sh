#!/usr/bin/env bash
#
# Publish a signed Immortal release so self-update and the store keep working.
#
# The self-update manifest (version.json) and the store catalog (catalog.json)
# both point at a STABLE asset URL:
#
#     https://github.com/<repo>/releases/latest/download/immortal.apk
#
# That URL only resolves if the release actually has an asset literally named
# `immortal.apk`. History: a release once attached the apk as `immortal-1.42.apk`
# (versioned) and nothing else, so `latest/download/immortal.apk` started 404ing
# and EVERY device's self-update broke with "Update failed". This script makes
# that impossible to forget: it always uploads the stable `immortal.apk`, plus a
# human-friendly versioned copy, and then verifies the stable URL resolves.
#
#   scripts/publish-release.sh <tag> <path-to-signed.apk>
#   scripts/publish-release.sh v1.42.1 app/build/outputs/apk/release/app-release.apk
#
# Assumes the GitHub Release <tag> already exists (e.g. created by `gh release
# create`). Needs the `gh` CLI, authenticated with write access to the repo.
set -euo pipefail
cd "$(dirname "$0")/.."

tag="${1:-}"
apk="${2:-}"
repo="${RELEASE_REPO:-starbrightlab/immortal}"

if [ -z "$tag" ] || [ -z "$apk" ]; then
  echo "usage: scripts/publish-release.sh <tag> <path-to-signed.apk>" >&2
  exit 2
fi
[ -f "$apk" ] || { echo "error: APK not found: $apk" >&2; exit 1; }

# Pull the version we're shipping straight from the manifest so the versioned
# asset name matches what devices are told to expect.
version_name="$(sed -n 's/.*"versionName"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' version.json | head -1)"
[ -n "$version_name" ] || { echo "error: could not read versionName from version.json" >&2; exit 1; }

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
cp "$apk" "$work/immortal.apk"
cp "$apk" "$work/immortal-${version_name}.apk"

echo "• Uploading immortal.apk (stable, required for self-update) + immortal-${version_name}.apk to $repo $tag"
gh release upload "$tag" "$work/immortal.apk" "$work/immortal-${version_name}.apk" \
  --repo "$repo" --clobber

echo "• Verifying the stable self-update URL resolves"
url="https://github.com/$repo/releases/latest/download/immortal.apk"
code="$(curl -fsSL -o /dev/null -w '%{http_code}' "$url" || true)"
if [ "$code" = "200" ]; then
  echo "  OK ($url -> 200)"
else
  echo "  FAILED ($url -> ${code:-no response}) — self-update will break. Is $tag the latest release?" >&2
  exit 1
fi

echo "Done. Remember to bump versionCode/versionName in version.json and commit it"
echo "(devices only see the new build once the manifest advertises it)."
