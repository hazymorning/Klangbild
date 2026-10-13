#!/usr/bin/env bash
# Runs the landscape measurements chosen in the workflow (see ci/landscape-measure.sh).
#   bash ci/landscape-measure-all.sh klangbild.apk <short|15min>
set -u
APK="$1"
MODE="$2"
HERE="$(cd "$(dirname "$0")" && pwd)"
case "$MODE" in
  short)
    bash "$HERE/landscape-measure.sh" "$APK" 120 landscape --es landscape on
    bash "$HERE/landscape-measure.sh" "$APK" 120 timelapse --es landscape timelapse
    ;;
  15min)
    bash "$HERE/landscape-measure.sh" "$APK" 900 landscape-15min --es landscape on
    bash "$HERE/landscape-measure.sh" "$APK" 120 timelapse --es landscape timelapse
    ;;
  *)
    echo "Unknown measurement: $MODE" >&2
    exit 1
    ;;
esac
cat measure/*/summary.txt
