#!/usr/bin/env bash
# Measures the landscape on the running Android TV emulator: installs the release APK,
# starts the app with the given extras, lets it run, and records frames (gfxinfo), the app's
# CPU time and its memory (PSS, graphics) once a minute. Leaves everything in measure/ and
# prints a summary. The emulator renders with a software GPU (SwiftShader): its frame times
# say little about a real TV's GPU. CPU time per frame, frames per second and memory growth
# are what this run is for.
#
#   bash ci/landscape-measure.sh klangbild.apk <seconds> <label> [am start extras ...]
set -u
APK="$1"
SECONDS_TO_RUN="$2"
LABEL="$3"
shift 3
PKG=io.github.hazymorning.klangbild
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="measure/$LABEL"
mkdir -p "$OUT"

if ! pgrep -f fake_ha.py > /dev/null; then
  python3 "$HERE/fake_ha.py" 8123 > "$OUT/fake_ha.log" 2>&1 &
fi

# Every adb call has a deadline: a software-rendered emulator can stall, and the run must not.
a() { timeout 90 adb "$@"; }

timeout 600 adb wait-for-device
a shell 'while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 2; done'
a install -r "$APK" > /dev/null
a shell am force-stop "$PKG"
a shell wm size > "$OUT/display.txt"
a shell wm density >> "$OUT/display.txt"
a shell am start -n "$PKG/.MainActivity" \
  --es ha_url http://10.0.2.2:8123 --es token smoke --es entity media_player.living_room "$@" > /dev/null
sleep 20
PID="$(a shell pidof "$PKG" | tr -d '\r')"
a shell dumpsys gfxinfo "$PKG" reset > /dev/null
ticks() { a shell cat "/proc/$PID/stat" | awk '{print $14 + $15}'; }
HZ=100
start_ticks="$(ticks)"
start_s="$(date +%s)"
echo "minute,pss_kb,graphics_kb,native_kb" > "$OUT/memory.csv"
minute=0
while [ $(( $(date +%s) - start_s )) -lt "$SECONDS_TO_RUN" ]; do
  mem="$(a shell dumpsys meminfo "$PKG")"
  pss="$(echo "$mem" | awk '/TOTAL PSS:/ {print $3; exit} /TOTAL:/ {print $2; exit}')"
  gfx="$(echo "$mem" | awk '/Graphics:/ {print $2; exit}')"
  nat="$(echo "$mem" | awk '/Native Heap:/ {print $3; exit}')"
  echo "$minute,$pss,$gfx,$nat" >> "$OUT/memory.csv"
  minute=$((minute + 1))
  remaining=$(( SECONDS_TO_RUN - ($(date +%s) - start_s) ))
  [ "$remaining" -gt 60 ] && sleep 60 || sleep "$remaining"
done
end_ticks="$(ticks)"
elapsed=$(( $(date +%s) - start_s ))
a exec-out screencap -p > "$OUT/screen.png"
a shell dumpsys gfxinfo "$PKG" > "$OUT/gfxinfo.txt"
a shell dumpsys gfxinfo "$PKG" framestats > "$OUT/framestats.txt"
a shell dumpsys meminfo "$PKG" > "$OUT/meminfo-end.txt"
a logcat -d > "$OUT/logcat.txt"

frames="$(awk '/Total frames rendered:/ {print $4; exit}' "$OUT/gfxinfo.txt")"
cpu=$(( (end_ticks - start_ticks) * 100 / HZ ))
{
  echo "== $LABEL ($elapsed s) =="
  cat "$OUT/display.txt"
  echo "frames: $frames ($(( frames / (elapsed > 0 ? elapsed : 1) )) per second)"
  grep -E '^(Janky frames|50th percentile|90th percentile|99th percentile|Number Slow UI thread|Number Slow issue draw commands)' "$OUT/gfxinfo.txt"
  echo "app CPU: ${cpu} cs over ${elapsed} s = $(( cpu / (elapsed > 0 ? elapsed : 1) ))% of one core"
  echo "memory (minute, PSS kB, graphics kB, native kB):"
  cat "$OUT/memory.csv"
  echo "the app's log:"
  grep " Klangbild: " "$OUT/logcat.txt" | tail -10
  if grep -q "FATAL EXCEPTION" "$OUT/logcat.txt"; then echo "CRASH:"; grep -A 20 "FATAL EXCEPTION" "$OUT/logcat.txt" | head -40; fi
} | tee "$OUT/summary.txt"
