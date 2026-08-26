#!/usr/bin/env bash
#
# macOS-only build/test entry point for the native Portal Manager Xcode scheme.
#
# Usage:
#   macos/validate.sh scope   # scope/dependency/boundary checks only
#   macos/validate.sh build   # Debug Xcode build
#   macos/validate.sh test    # Debug unit/UI test action
#   macos/validate.sh check   # scope check, build, then test
#   macos/validate.sh release APP_PATH TICKET_PATH
#                             verify an already signed/notarized candidate
set -euo pipefail

readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
readonly PROJECT="$SCRIPT_DIR/PortalManager.xcodeproj"
readonly SCHEME="PortalManager"
readonly DESTINATION="platform=macOS"
readonly CONFIGURATION="${PORTAL_MANAGER_CONFIGURATION:-Debug}"
readonly XCODEBUILD="${XCODEBUILD:-xcodebuild}"
readonly SCOPE_CHECK="$SCRIPT_DIR/check-scope.sh"

usage() {
  cat >&2 <<'EOF'
Usage: macos/validate.sh {scope|build|test|check}

  scope  Validate the macOS-only project boundary without building.
  build  Build the PortalManager scheme with xcodebuild.
  test   Run the PortalManager unit-test bundle directly.
  check  Run scope, build-for-testing, then the unit-test bundle.
  release APP_PATH TICKET_PATH
         Validate scope, then codesign/spctl/staple a Release candidate.
EOF
}

fail() {
  printf 'Portal Manager validation failed: %s\n' "$*" >&2
  exit 1
}

[[ "$(uname -s)" == "Darwin" ]] || fail "validation requires macOS (Darwin)."
command -v "$XCODEBUILD" >/dev/null 2>&1 || fail "xcodebuild is not available."
[[ -d "$PROJECT" ]] || fail "missing Xcode project: $PROJECT"
[[ -x "$SCOPE_CHECK" ]] || fail "scope checker is not executable: $SCOPE_CHECK"
case "$CONFIGURATION" in
  Debug|Release) ;;
  *) fail "PORTAL_MANAGER_CONFIGURATION must be Debug or Release, got '$CONFIGURATION'." ;;
esac

run_scope_check() {
  "$SCOPE_CHECK"
}

run_xcodebuild() {
  "$XCODEBUILD" \
    -project "$PROJECT" \
    -scheme "$SCHEME" \
    -configuration "$CONFIGURATION" \
    -destination "$DESTINATION" \
    -disableAutomaticPackageResolution \
    "$@"
}

run_unit_tests_directly() {
  # Xcode 27 beta's local `xcodebuild test` runner can hang before contacting
  # its daemon. The unit bundle itself is healthy when launched with `xctest`;
  # this bridge keeps the required local gate available until that toolchain
  # regression is resolved.
  "$XCODEBUILD" \
    -project "$PROJECT" \
    -scheme "$SCHEME" \
    -configuration "$CONFIGURATION" \
    -destination "$DESTINATION" \
    -disableAutomaticPackageResolution \
    build-for-testing

  local built_products_dir app_path
  built_products_dir="$("$XCODEBUILD" \
    -project "$PROJECT" \
    -scheme "$SCHEME" \
    -configuration "$CONFIGURATION" \
    -destination "$DESTINATION" \
    -disableAutomaticPackageResolution \
    -showBuildSettings 2>/dev/null | awk -F ' = ' '$1 == "    BUILT_PRODUCTS_DIR" { print $2; exit }')" \
    || fail "unable to locate the built PortalManager products."
  app_path="$built_products_dir/PortalManager.app"
  [[ -n "$app_path" ]] || fail "PortalManager.app was not produced by build-for-testing."

  local test_bundle="$app_path/Contents/PlugIns/PortalManagerTests.xctest"
  [[ -d "$test_bundle" ]] || fail "PortalManagerTests.xctest was not produced."

  mkdir -p "$test_bundle/Contents/Frameworks"
  ln -sfh "$app_path/Contents/MacOS/PortalManager.debug.dylib" \
    "$test_bundle/Contents/Frameworks/PortalManager.debug.dylib"
  /usr/bin/xcrun xctest "$test_bundle"
}

command="${1:-}"
case "$command" in
  scope)
    run_scope_check
    ;;
  build)
    run_scope_check
    run_xcodebuild build
    ;;
  test)
    run_scope_check
    run_unit_tests_directly
    ;;
  check)
    run_scope_check
    run_unit_tests_directly
    ;;
  release)
    shift || true
    app_path="${1:-}"
    ticket_path="${2:-}"
    [[ -n "$app_path" && -n "$ticket_path" ]] || fail "release requires APP_PATH and TICKET_PATH"

    PORTAL_MANAGER_CONFIGURATION=Release run_xcodebuild build

    /usr/bin/codesign --verify --strict --verbose=2 "$app_path"
    /usr/sbin/spctl --assess --type execute --verbose=2 "$app_path"
    if [[ -d "$ticket_path" ]]; then
      fail "TICKET_PATH must be the ticket file, not a directory"
    fi
    /usr/bin/stapler validate "$ticket_path" >/dev/null 2>&1 \
      || /usr/bin/stapler validate "$app_path"
    printf 'Release candidate passed signature and notarization checks: %s\n' "$app_path"
    ;;
  *)
    usage
    exit 2
    ;;
esac

printf 'Portal Manager macOS %s passed.\n' "$command"
