#!/usr/bin/env bash
# Sync the vendored AirPlay layer from jqssun/android-airplay-server.
#
# Copies only the paths listed in VENDORED below — upstream's UI shell is deliberately not tracked
# (see UPSTREAM.md, "The vendor line"). Prints the resulting diff for review; it does not commit,
# and it does not touch the submodule pins.
#
# Usage:
#   airplay/sync-upstream.sh            # sync to upstream main
#   airplay/sync-upstream.sh <ref>      # sync to a tag/branch/commit
#   airplay/sync-upstream.sh --check    # report the delta without writing anything

set -euo pipefail

UPSTREAM_URL="https://github.com/jqssun/android-airplay-server.git"
REF="main"
CHECK_ONLY=0

for arg in "$@"; do
    case "$arg" in
        --check) CHECK_ONLY=1 ;;
        -*) echo "unknown option: $arg" >&2; exit 2 ;;
        *) REF="$arg" ;;
    esac
done

# Repo-relative paths, identical on both sides apart from the module prefix.
#   <upstream>/app/src/main/<path>   ->   <here>/airplay/src/main/<path>
VENDORED=(
    "cpp"
    "kotlin/io/github/jqssun/airplay/bridge"
    "kotlin/io/github/jqssun/airplay/renderer"
    "kotlin/io/github/jqssun/airplay/audio"
    "kotlin/io/github/jqssun/airplay/discovery"
    "kotlin/io/github/jqssun/airplay/download"
    "kotlin/io/github/jqssun/airplay/service"
    "kotlin/io/github/jqssun/airplay/Prefs.kt"
    "kotlin/io/github/jqssun/airplay/Display.kt"
)

cd "$(dirname "$0")/.."
REPO_ROOT="$PWD"
MODULE="$REPO_ROOT/airplay/src/main"

# --ignore-submodules=dirty is required, not tidiness: applyUxplayPatches git-applies five patch
# files inside the UxPlay submodule at CMake-configure time, so on any machine that has ever built
# the project the submodule working tree is permanently dirty. Without this the guard trips every
# time, with nothing to commit or stash at the superproject level. Submodule *pin* changes still
# show up, which is what we actually care about here.
if [[ -n "$(git status --porcelain --ignore-submodules=dirty -- airplay)" ]]; then
    echo "airplay/ has uncommitted changes — commit or stash first, so the sync diff is readable." >&2
    exit 1
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "==> fetching $UPSTREAM_URL at $REF"
git clone --quiet --filter=blob:none --no-checkout "$UPSTREAM_URL" "$WORK/up"
git -C "$WORK/up" checkout --quiet "$REF"
UP_SHA="$(git -C "$WORK/up" rev-parse HEAD)"
echo "    upstream is at $UP_SHA"

echo "==> upstream submodule pins (update UPSTREAM.md if these moved)"
git -C "$WORK/up" ls-tree HEAD app/src/main/cpp/third_party/ | awk '{printf "    %-12s %s\n", $4, substr($3,1,7)}'

if [[ "$CHECK_ONLY" == 1 ]]; then
    echo "==> --check: comparing without writing"
fi

for path in "${VENDORED[@]}"; do
    src="$WORK/up/app/src/main/$path"
    dst="$MODULE/$path"
    if [[ ! -e "$src" ]]; then
        echo "    !! upstream no longer has $path — the vendor line needs revisiting" >&2
        continue
    fi
    # Submodule working trees live under cpp/third_party and are tracked by pin, not by content:
    # upstream's clone is blobless and never checks them out, so they must be excluded from BOTH
    # the copy and the comparison. Comparing them would report "differs" on every run for the one
    # path that matters most.
    if [[ "$CHECK_ONLY" == 1 ]]; then
        if [[ -d "$src" ]]; then
            diff -qr --exclude 'third_party' "$src" "$dst" >/dev/null 2>&1 || echo "    differs: $path"
        else
            diff -q "$src" "$dst" >/dev/null 2>&1 || echo "    differs: $path"
        fi
        continue
    fi
    if [[ -d "$src" ]]; then
        rsync -a --delete --exclude 'third_party/' "$src/" "$dst/"
    else
        cp "$src" "$dst"
    fi
done

if [[ "$CHECK_ONLY" == 1 ]]; then
    exit 0
fi

echo
echo "==> diff (review before committing)"
git -C "$REPO_ROOT" --no-pager diff --stat -- airplay/src/main

cat <<EOF

DERIVED FROM UPSTREAM — NOT COPIED BY THIS SCRIPT, review by hand:
  * viewmodel/DebugModels.kt   AudioDebug/DebugInfo, split out of upstream's MainViewModel.kt
                               (which we do not vendor). AudioDebug's field list IS the decode
                               layout for the packed buffer nativeServerAudioDebug fills and
                               renderer/AudioRenderer unpacks — if upstream reorders or adds a
                               field, the synced reader moves and this record does not. Diff
                               upstream's MainViewModel.kt head against it.
  * res/values/strings.xml     the seven strings the module resolves through R; upstream keeps
                               them in the app's full strings.xml. Check the notification_* and
                               download_* entries still exist and still have the same format args.

Next:
  1. Re-apply the LOCAL PATCH sites this sync clobbered (grep for 'LOCAL PATCH'): two in
     service/AirPlayService.kt routing MainActivity through AirPlayHost, and the JNI thread-detach
     hook in cpp/android_raop_callbacks.c (_get_env). See airplay/UPSTREAM.md.
  2. Update the pins and the submodule table in airplay/UPSTREAM.md ($UP_SHA).
  3. If a submodule pin moved above, bump it:
       git -C airplay/src/main/cpp/third_party/<name> fetch --depth 1 origin <sha> && \\
       git -C airplay/src/main/cpp/third_party/<name> checkout FETCH_HEAD
  4. ./gradlew :app:assembleDebug, then verify on a gen-1 AND a gen-2 Portal.
EOF
