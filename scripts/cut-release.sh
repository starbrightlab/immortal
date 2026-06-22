#!/usr/bin/env bash
#
# Cut a signed Immortal release in one safe command.
#
#   scripts/cut-release.sh <versionName> "<release notes>"
#   scripts/cut-release.sh 1.44 "Adds the home-header remote button and a tidier remote layout."
#
# It does the whole release end to end and fails fast at every gate, so the two things we
# keep getting wrong — forgetting the version.json bump, and misnaming the APK — become
# structurally impossible:
#
#   1. Preflight — gh authenticated; clean tree on an up-to-date main; signing key present;
#      Android build-tools (aapt + apksigner) found; the tag is still free.
#   2. Bump versionCode (auto, current + 1) / versionName / notes in BOTH
#      app/build.gradle.kts and version.json together, then re-check they agree.
#   3. Build the signed release APK, then VERIFY it: aapt confirms its versionCode/versionName
#      match what we just wrote (no stale build), and apksigner confirms it's signed with the
#      SAME key as the currently-published immortal.apk (a different key silently breaks every
#      device's in-place self-update).
#   4. Build portal-kit.zip from the COMMITTED provisioning/ tree (git archive — only tracked
#      files, never local junk, downloaded APKs, or secrets).
#   5. Commit the bump, tag it, push both.
#   6. Create the GitHub Release as a DRAFT, upload exactly immortal.apk + portal-kit.zip, then
#      publish. Drafts aren't "latest", so latest/download keeps resolving to the previous
#      release until the new assets are in place.
#   7. Verify both latest/download/ URLs resolve AND the published immortal.apk reports the
#      versionCode we shipped.
#
# Requires: gh (authenticated, write access to the repo), the Android SDK build-tools, and
# keystore.properties (repo root or ~/.immortal-signing/). See docs/releasing.md.
set -euo pipefail
cd "$(dirname "$0")/.."

repo="${RELEASE_REPO:-starbrightlab/immortal}"
stable_apk_url="https://github.com/$repo/releases/latest/download/immortal.apk"
stable_kit_url="https://github.com/$repo/releases/latest/download/portal-kit.zip"

die(){ echo "✗ $*" >&2; exit 1; }
step(){ echo; echo "▸ $*"; }

version_name="${1:-}"
notes="${2:-}"
[ -n "$version_name" ] || die "usage: scripts/cut-release.sh <versionName> \"<release notes>\""
[ -n "$notes" ] || die "release notes are required (second argument)"
echo "$version_name" | grep -Eq '^[0-9]+(\.[0-9]+){1,2}$' \
  || die "versionName must look like 1.44 or 1.44.1 (got: $version_name)"
tag="v$version_name"

# ---- locate Android build-tools (aapt + apksigner) ----
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
bt="$(ls -d "$sdk"/build-tools/* 2>/dev/null | sort -V | tail -1 || true)"
[ -n "$bt" ] && [ -x "$bt/aapt" ] && [ -x "$bt/apksigner" ] \
  || die "Android build-tools (aapt + apksigner) not found under $sdk/build-tools — set ANDROID_HOME"

# ---- locate signing key ----
keyprops="keystore.properties"
[ -f "$keyprops" ] || keyprops="$HOME/.immortal-signing/keystore.properties"
[ -f "$keyprops" ] || die "no keystore.properties (repo root or ~/.immortal-signing/) — the release would be unsigned"

tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT

step "Preflight"
command -v gh >/dev/null || die "gh CLI not found"
gh auth status >/dev/null 2>&1 || die "gh is not authenticated (run: gh auth login)"
[ "$(git rev-parse --abbrev-ref HEAD)" = "main" ] || die "not on main (on $(git rev-parse --abbrev-ref HEAD))"
git diff --quiet && git diff --cached --quiet || die "working tree has uncommitted changes — commit or stash first"
git fetch --quiet origin main --tags
[ "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" ] || die "local main is not in sync with origin/main — pull/push first"
git rev-parse "$tag" >/dev/null 2>&1 && die "tag $tag already exists locally"
git ls-remote --exit-code --tags origin "refs/tags/$tag" >/dev/null 2>&1 && die "tag $tag already exists on origin"
echo "  ok: gh authed · clean main synced with origin · $tag is free · signing key + build-tools present"

# ---- compute versions ----
cur_code="$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' app/build.gradle.kts | head -1)"
cur_name="$(sed -n 's/.*versionName = "\([^"]*\)".*/\1/p' app/build.gradle.kts | head -1)"
[ -n "$cur_code" ] || die "could not read versionCode from app/build.gradle.kts"
new_code=$((cur_code + 1))
[ "$version_name" != "$cur_name" ] || die "versionName $version_name is unchanged from current — bump it"
echo "  versionCode $cur_code → $new_code   ·   versionName $cur_name → $version_name"

step "Bump gradle + version.json in lockstep"
sed -i.bak "s/versionCode = $cur_code/versionCode = $new_code/" app/build.gradle.kts
sed -i.bak "s/versionName = \"$cur_name\"/versionName = \"$version_name\"/" app/build.gradle.kts
rm -f app/build.gradle.kts.bak
VN="$version_name" VC="$new_code" NOTES="$notes" APKURL="$stable_apk_url" python3 - <<'PY'
import json, os
p = "version.json"
d = json.load(open(p))
d["versionCode"] = int(os.environ["VC"])
d["versionName"] = os.environ["VN"]
d["apkUrl"] = os.environ["APKURL"]
d["notes"] = os.environ["NOTES"]
with open(p, "w") as f:
    json.dump(d, f, indent=2)
    f.write("\n")
PY
scripts/check-version-sync.sh >/dev/null || die "version sync check failed right after bumping (this is a bug in cut-release.sh)"
echo "  gradle + version.json bumped and consistent"

step "Build signed release APK"
./gradlew :app:assembleRelease -q
apk="app/build/outputs/apk/release/app-release.apk"
[ -f "$apk" ] || die "release APK not produced at $apk"

step "Verify the built APK"
badging="$("$bt/aapt" dump badging "$apk")"
got_code="$(echo "$badging" | sed -n "s/^package:.*versionCode='\([0-9]*\)'.*/\1/p")"
got_name="$(echo "$badging" | sed -n "s/^package:.*versionName='\([^']*\)'.*/\1/p")"
[ "$got_code" = "$new_code" ] || die "built APK is versionCode $got_code, expected $new_code (stale build?)"
[ "$got_name" = "$version_name" ] || die "built APK is versionName $got_name, expected $version_name (stale build?)"
echo "  APK reports versionCode $got_code / versionName $got_name ✓"
new_fp="$("$bt/apksigner" verify --print-certs "$apk" 2>/dev/null | sed -n 's/.*SHA-256 digest: //p' | head -1)"
[ -n "$new_fp" ] || die "could not read the new APK's signing certificate (is it signed?)"
if curl -fsSL -o "$tmp/cur.apk" "$stable_apk_url" 2>/dev/null; then
  cur_fp="$("$bt/apksigner" verify --print-certs "$tmp/cur.apk" 2>/dev/null | sed -n 's/.*SHA-256 digest: //p' | head -1)"
  [ "$new_fp" = "$cur_fp" ] \
    || die "signing key changed! new=$new_fp vs published=$cur_fp — installing this would BREAK self-update on every device"
  echo "  signing key matches the currently-published release ✓"
else
  echo "  ⚠ no published immortal.apk to compare against (first release?) — skipping signing-key match"
fi

step "Build portal-kit.zip from the committed provisioning/ tree"
kit="$tmp/portal-kit.zip"
git archive --format=zip --prefix=portal-kit/ HEAD:provisioning -o "$kit"
echo "  $(unzip -l "$kit" | tail -1 | awk '{print $2}') files · $(du -h "$kit" | cut -f1)"

step "Commit, tag, push"
git add app/build.gradle.kts version.json
git commit -q -m "Release $version_name (versionCode $new_code)"
git tag -a "$tag" -m "Immortal $version_name"
git push --quiet origin main
git push --quiet origin "$tag"
echo "  committed bump + pushed $tag"

step "Create draft release, upload assets, publish"
gh release create "$tag" --repo "$repo" --draft --title "Immortal $version_name" --notes "$notes" >/dev/null
gh release upload "$tag" "$apk#immortal.apk" "$kit" --repo "$repo" --clobber
gh release edit "$tag" --repo "$repo" --draft=false >/dev/null
echo "  published $tag with immortal.apk + portal-kit.zip"

step "Verify the published release"
for url in "$stable_apk_url" "$stable_kit_url"; do
  code="$(curl -fsSL -o /dev/null -w '%{http_code}' "$url" || true)"
  [ "$code" = "200" ] || die "$url did not resolve (got ${code:-no response}) — self-update or kit download would fail"
  echo "  ok: $url → 200"
done
curl -fsSL -o "$tmp/pub.apk" "$stable_apk_url"
pub_code="$("$bt/aapt" dump badging "$tmp/pub.apk" | sed -n "s/^package:.*versionCode='\([0-9]*\)'.*/\1/p")"
[ "$pub_code" = "$new_code" ] || die "published immortal.apk is versionCode $pub_code, expected $new_code"
echo "  published immortal.apk is versionCode $pub_code ✓"

echo
echo "✓ Immortal $version_name (versionCode $new_code) released."
echo "  Assets: immortal.apk + portal-kit.zip"
echo "  Devices self-update on their next poll of version.json."
