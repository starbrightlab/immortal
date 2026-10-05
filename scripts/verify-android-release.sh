#!/usr/bin/env bash
#
# Verify one signed Android Release APK before an operator-approved deployment.
#
#   scripts/verify-android-release.sh APK VERSION_CODE VERSION_NAME CERT_SHA256 [APK_SHA256]
#
# The certificate digest is the deployment trust anchor. The optional APK digest
# must match release evidence exactly; omit it only to print a candidate digest.

set -euo pipefail
cd "$(dirname "$0")/.."

die() {
  echo "Android Release verification failed: $*" >&2
  exit 1
}

lowercase() {
  printf '%s' "$1" | tr '[:upper:]' '[:lower:]'
}

[[ $# -ge 4 && $# -le 5 ]] || die "usage: $0 APK VERSION_CODE VERSION_NAME CERT_SHA256 [APK_SHA256]"

apk=$1
expected_code=$2
expected_name=$3
expected_cert=$(lowercase "$4")
expected_apk=$(lowercase "${5:-}")

[[ -f "$apk" ]] || die "missing APK: $apk"
[[ "$expected_cert" =~ ^[0-9a-f]{64}$ ]] || die "certificate digest must be 64 hexadecimal characters"
if [[ -n "$expected_apk" ]]; then
  [[ "$expected_apk" =~ ^[0-9a-f]{64}$ ]] || die "APK digest must be 64 hexadecimal characters"
fi

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
build_tools="$(ls -d "$sdk"/build-tools/* 2>/dev/null | sort -V | tail -1)"
[[ -n "$build_tools" ]] || die "no Android build-tools found under $sdk"
aapt2="$build_tools/aapt2"
apksigner="$build_tools/apksigner"
[[ -x "$aapt2" ]] || die "missing aapt2: $aapt2"
[[ -x "$apksigner" ]] || die "missing apksigner: $apksigner"

case "$(uname -s)" in
  Darwin) actual_apk="$(shasum -a 256 "$apk" | awk '{print $1}')" ;;
  Linux) actual_apk="$(sha256sum "$apk" | awk '{print $1}')" ;;
  *) die "unsupported host: $(uname -s)" ;;
esac

badging="$("$aapt2" dump badging "$apk")"
actual_package="$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<<"$badging" | head -1)"
actual_code="$(sed -n "s/^package:.*versionCode='\([0-9]*\)'.*/\1/p" <<<"$badging" | head -1)"
actual_name="$(sed -n "s/^package:.*versionName='\([^']*\)'.*/\1/p" <<<"$badging" | head -1)"
[[ -n "$actual_package" && -n "$actual_code" && -n "$actual_name" ]] ||
  die "could not read package identity from badging output"

signing="$("$apksigner" verify --verbose --print-certs "$apk" 2>/dev/null)"
grep -Eq '^Verified using v2 scheme \(APK Signature Scheme v2\): true$' <<<"$signing" ||
  die "APK Signature Scheme v2 is not verified"
actual_cert="$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' <<<"$signing" | head -1)"
[[ "$actual_cert" =~ ^[0-9a-f]{64}$ ]] || die "could not read signer certificate digest"

if [[ -n "$expected_apk" && "$actual_apk" != "$expected_apk" ]]; then
  die "APK digest mismatch: expected $expected_apk, actual $actual_apk"
fi
[[ "$actual_cert" == "$expected_cert" ]] ||
  die "signer mismatch: expected $expected_cert, actual $actual_cert"
[[ "$actual_code" == "$expected_code" ]] ||
  die "versionCode mismatch: expected $expected_code, actual $actual_code"
[[ "$actual_name" == "$expected_name" ]] ||
  die "versionName mismatch: expected $expected_name, actual $actual_name"

cat <<EOF
{
  "package": "$actual_package",
  "versionCode": $actual_code,
  "versionName": "$actual_name",
  "signatureSchemeV2": true,
  "certificateSha256": "$actual_cert",
  "artifactSha256": "$actual_apk"
}
EOF
