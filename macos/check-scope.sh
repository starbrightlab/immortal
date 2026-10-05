#!/usr/bin/env bash
#
# Scope guard for the native Portal Manager validation boundary.
#
# This check is intentionally macOS-only. It verifies that the Xcode project remains
# independent from Android/Gradle and that no cloud, package-manager, or downloader
# dependency has been introduced into the native project.
set -euo pipefail

readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
readonly MACOS_DIR="$REPO_ROOT/macos"
readonly PROJECT="$MACOS_DIR/PortalManager.xcodeproj"
readonly SCHEME="$PROJECT/xcshareddata/xcschemes/PortalManager.xcscheme"
readonly XCODEBUILD="${XCODEBUILD:-xcodebuild}"

fail() {
  printf 'Portal Manager scope check failed: %b\n' "$*" >&2
  exit 1
}

[[ "$(uname -s)" == "Darwin" ]] || fail "validation requires macOS (Darwin)."
command -v "$XCODEBUILD" >/dev/null 2>&1 || fail "xcodebuild is not available."
[[ -d "$PROJECT" ]] || fail "missing Xcode project: $PROJECT"
[[ -f "$SCHEME" ]] || fail "missing shared PortalManager scheme: $SCHEME"
git -C "$REPO_ROOT" rev-parse --show-toplevel >/dev/null 2>&1 || fail "repository root is not a Git worktree."

# Confirm that the project itself exposes the intended scheme and macOS platform before
# any build/test command is allowed to run.
project_list="$($XCODEBUILD -list -project "$PROJECT" 2>/dev/null)" || fail "unable to inspect the PortalManager Xcode project."
grep -Eq '^[[:space:]]+PortalManager$' <<<"$project_list" || fail "PortalManager scheme is not listed by xcodebuild."

build_settings="$($XCODEBUILD -showBuildSettings -project "$PROJECT" -scheme PortalManager -configuration Debug 2>/dev/null)" || fail "unable to inspect PortalManager build settings."
if ! grep -Eq '^[[:space:]]+PLATFORM_NAME = macosx$' <<<"$build_settings"; then
  fail "PortalManager is not configured for the macOS platform."
fi

# The macOS project is the primary tree for this task. Android runtime and
# provisioning changes remain limited to the explicit cross-platform contracts below;
# all other protected paths are rejected even when they are untracked.
changed_paths() {
  git -C "$REPO_ROOT" diff --name-only --diff-filter=ACDMRTUXB HEAD
  git -C "$REPO_ROOT" ls-files --others --exclude-standard
}

while IFS= read -r path; do
  [[ -n "$path" ]] || continue
  case "$path" in
    app/src/main/AndroidManifest.xml|\
    app/src/main/java/com/immortal/launcher/FleetRoutes.kt|\
    app/src/main/java/com/immortal/launcher/ImmortalSettings.kt|\
    app/src/main/java/com/immortal/launcher/ImmortalSettingsActivity.kt|\
    app/src/main/java/com/immortal/launcher/LanAudio.kt|\
    app/src/main/java/com/immortal/launcher/IntercomPolicy.kt|\
    app/src/main/java/com/immortal/launcher/IntercomService.kt|\
    app/src/main/java/com/immortal/launcher/RoomLinkProtocol.kt|\
    app/src/main/java/com/immortal/launcher/FleetAppProfiles.kt|\
    app/src/main/java/com/immortal/launcher/settings/SettingsDomains.kt|\
    provisioning/fleet.rs|\
    app/src/test/java/com/immortal/launcher/IntercomPolicyTest.kt|\
    app/src/test/java/com/immortal/launcher/RoomLinkProtocolTest.kt|\
    app/src/test/java/com/immortal/launcher/FleetAppProfilesTest.kt|\
    app/src/test/java/com/immortal/launcher/settings/SettingsDomainTest.kt|\
    .github/workflows/tests.yml)
      # Native Portal Manager features are deliberately cross-platform: the macOS
      # manager consumes the same dependency-free Portal contracts it configures.
      continue
      ;;
  esac
  case "$path" in
    app|app/*|provisioning|gradle|gradle/*|gradlew|gradlew.bat|gradle.properties|settings.gradle.kts|build.gradle.kts|.github/workflows|.github/workflows/*)
      fail "protected Android/Gradle/provisioning/CI path changed: $path"
      ;;
  esac
done < <(changed_paths | LC_ALL=C sort -u)

# Ignore rules protect credential-like artifacts, but they can accidentally hide a
# required source file from a fresh checkout. Every native Swift source must be
# tracked before the project is allowed to build.
while IFS= read -r -d '' source_file; do
  relative_path="${source_file#"$REPO_ROOT/"}"
  if git -C "$REPO_ROOT" check-ignore -q "$relative_path"; then
    fail "required Swift source is ignored: $relative_path"
  fi
  if ! git -C "$REPO_ROOT" ls-files --error-unmatch "$relative_path" >/dev/null 2>&1; then
    fail "required Swift source is not tracked: $relative_path"
  fi
done < <(
  find "$MACOS_DIR" \
    \( -name DerivedData -o -name build -o -name target -o -name .git \) -prune -o \
    -type f -name '*.swift' -print0
)

# No external package manager or remote Swift package is part of the native target. A
# future local adapter may use Foundation/Network APIs, but it must not add a downloader,
# cloud SDK, relay, or hard-coded external dependency.
while IFS= read -r -d '' dependency_manifest; do
  fail "external dependency manifest is not allowed: ${dependency_manifest#"$REPO_ROOT/"}"
done < <(
  find "$MACOS_DIR" \
    \( -name DerivedData -o -name build -o -name .git \) -prune -o \
    -type f \( -name Package.swift -o -name Package.resolved -o -name Podfile -o -name Podfile.lock -o -name Cartfile -o -name Cartfile.resolved -o -name Mintfile \) \
    -print0
)

project_roots=(
  "$PROJECT"
  "$MACOS_DIR/PortalManager"
  "$MACOS_DIR/PortalManagerTests"
  "$MACOS_DIR/PortalManagerUITests"
)

assert_absent() {
  local label="$1"
  local pattern="$2"
  shift 2
  local matches
  matches="$(grep -RInE --binary-files=without-match "$pattern" "$@" 2>/dev/null || true)"
  [[ -z "$matches" ]] || fail "$label:\n$matches"
}

assert_absent \
  "remote package or external dependency reference" \
  'XCRemoteSwiftPackageReference|XCSwiftPackageProductDependency|packageReferences|packageProductDependencies|github\.com|gitlab\.com|bitbucket\.org|raw\.githubusercontent\.com|swiftpackageindex\.com|cocoapods\.org' \
  "${project_roots[@]}"

assert_absent \
  "cloud, relay, or downloader integration" \
  'cloud|relay|URLSessionDownloadTask|downloadTask|(^|[[:space:]])(curl|wget|git[[:space:]]+clone|swift[[:space:]]+package)([[:space:]]|$)' \
  "$MACOS_DIR/PortalManager" \
  "$MACOS_DIR/PortalManagerTests" \
  "$MACOS_DIR/PortalManagerUITests"

# Project metadata must not pull Android, Gradle, provisioning, or external URL content
# into the native target. The source tree is intentionally not checked for the word
# "Android" because adapters document the existing Android contract they consume.
assert_absent \
  "Android/Gradle/provisioning reference in Xcode project metadata" \
  '(^|[[:space:]=])(app|provisioning)([/;]|$)|gradle|Android|Kotlin|https?://' \
  "$PROJECT"

printf 'Portal Manager macOS scope check passed.\n'
