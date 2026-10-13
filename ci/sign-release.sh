#!/usr/bin/env bash
# Signs an APK with the release key and its lineage, the way every release from 6.0.1 on is
# signed: APK Signature Scheme v3 only (every Android from 9 on reads it, and minSdk is 28),
# with the rotation applied from API 28 so every supported Android version sees the new key and
# its lineage. Then it checks the signature, and that the lineage starts with
# ORIGINAL_CERT_SHA256 when that is set: the key every copy installed so far trusts.
#
#   RELEASE_KEYSTORE_PASSWORD=... bash ci/sign-release.sh app.apk release.p12 lineage.bin
#
# The keystore holds its key under the alias "klangbild". The APK is signed in place.
set -euo pipefail
APK="$1"
KEYSTORE="$2"
LINEAGE="$3"
: "${RELEASE_KEYSTORE_PASSWORD:?RELEASE_KEYSTORE_PASSWORD must hold the password of the keystore}"
HERE="$(cd "$(dirname "$0")" && pwd)"
APKSIGNER="$("$HERE/apksigner-path.sh")"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
SIGNED="$WORK/signed.apk"

"$APKSIGNER" sign \
  --ks "$KEYSTORE" --ks-key-alias klangbild --ks-pass env:RELEASE_KEYSTORE_PASSWORD \
  --lineage "$LINEAGE" --rotation-min-sdk-version 28 \
  --v1-signing-enabled false --v2-signing-enabled false --v3-signing-enabled true --v4-signing-enabled false \
  --out "$SIGNED" "$APK"
"$APKSIGNER" verify --verbose --print-certs "$SIGNED"
"$APKSIGNER" lineage --in "$SIGNED" --print-certs --verbose

if [ -n "${ORIGINAL_CERT_SHA256:-}" ]; then
  first="$("$APKSIGNER" lineage --in "$SIGNED" --print-certs | awk -F': ' '/^Signer .1 in lineage certificate SHA-256 digest/ { print $2; exit }')"
  if [ "$first" != "$ORIGINAL_CERT_SHA256" ]; then
    echo "The lineage starts with $first, not with the original key $ORIGINAL_CERT_SHA256." >&2
    exit 1
  fi
fi
mv "$SIGNED" "$APK"
