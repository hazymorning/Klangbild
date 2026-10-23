#!/usr/bin/env bash
# Installs an emulator system image before the emulator action starts, and tries again when the
# download breaks off. Now and then the download server hands out a truncated archive
# (sdkmanager: "Error on ZipFile unknown archive"), and the action, which tries only once, would
# fail the whole run. With the image in place the action skips its own download.
#
#   bash ci/install-system-image.sh 34 google_apis x86_64
set -euo pipefail
API="$1"
TARGET="$2"
ARCH="$3"
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
PACKAGE="system-images;android-$API;$TARGET;$ARCH"
yes | "$SDKMANAGER" --licenses > /dev/null || true
for attempt in 1 2 3; do
  if "$SDKMANAGER" --install "$PACKAGE" --channel=0 > /dev/null &&
    [ -f "$ANDROID_HOME/system-images/android-$API/$TARGET/$ARCH/source.properties" ]; then
    exit 0
  fi
  echo "Installing $PACKAGE failed on attempt $attempt." >&2
  [ "$attempt" -lt 3 ] && sleep 20
done
echo "Could not install $PACKAGE." >&2
exit 1
