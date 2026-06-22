#!/usr/bin/env bash
#
# Assert the release metadata is internally consistent:
#   - app/build.gradle.kts and version.json agree on versionCode + versionName
#   - version.json.apkUrl is the stable self-update URL
#   - version.json.notes is non-empty
#
# Used by CI (.github/workflows/release-guard.yml) on every change to either file, and
# by scripts/cut-release.sh right after it bumps. The whole point: a hand-edit that bumps
# one file and forgets the other can no longer reach a release — it fails here first.
set -euo pipefail
cd "$(dirname "$0")/.."

repo="${RELEASE_REPO:-starbrightlab/immortal}"
stable_apk_url="https://github.com/$repo/releases/latest/download/immortal.apk"

g_code="$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' app/build.gradle.kts | head -1)"
g_name="$(sed -n 's/.*versionName = "\([^"]*\)".*/\1/p' app/build.gradle.kts | head -1)"
[ -n "$g_code" ] || { echo "✗ could not read versionCode from app/build.gradle.kts" >&2; exit 1; }
[ -n "$g_name" ] || { echo "✗ could not read versionName from app/build.gradle.kts" >&2; exit 1; }

# Parse version.json with python3 (reliable JSON; the file ships notes with arbitrary text).
read -r j_code j_name j_notes_len j_url < <(python3 - <<'PY'
import json
d = json.load(open("version.json"))
print(d.get("versionCode"), d.get("versionName"), len((d.get("notes") or "").strip()), d.get("apkUrl", ""))
PY
)

fail=0
note(){ echo "✗ $*" >&2; fail=1; }
[ "$g_code" = "$j_code" ] || note "versionCode mismatch — gradle=$g_code, version.json=$j_code"
[ "$g_name" = "$j_name" ] || note "versionName mismatch — gradle=$g_name, version.json=$j_name"
[ "$j_url" = "$stable_apk_url" ] || note "version.json apkUrl is '$j_url', expected '$stable_apk_url'"
[ "${j_notes_len:-0}" -gt 0 ] || note "version.json notes is empty"

if [ "$fail" -ne 0 ]; then
  echo "Release metadata is inconsistent. Bump both files together — scripts/cut-release.sh does this for you." >&2
  exit 1
fi
echo "✓ version sync OK — gradle and version.json agree (versionCode $g_code, versionName $g_name)"
