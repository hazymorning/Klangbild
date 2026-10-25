#!/usr/bin/env bash
# Starts a stand-in Home Assistant on the host (ci/fake_ha.py), installs the release APK on
# the running Android TV emulator, points it at that server with launch extras and walks
# through the main states: playing, the settings on up and on a long press, a track change,
# new extras while running and the quick settings. With SENDSPIN_PYTHON set (a Python with
# aiosendspin), the TV then plays as a speaker from a Sendspin server on the host. Checks that
# the app is still alive and never crashed. Leaves screenshots, logcat and gfxinfo in smoke/.
set -u
APK="$1"
PKG=io.github.hazymorning.klangbild
HERE="$(cd "$(dirname "$0")" && pwd)"
mkdir -p smoke

python3 "$HERE/fake_ha.py" 8123 > smoke/fake_ha.log 2>&1 &
FAKE=$!
trap 'kill $FAKE 2>/dev/null || true' EXIT

adb wait-for-device
adb shell 'while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 2; done'
adb logcat -c || true

adb install -r "$APK"
# The emulator reaches the host's loopback at 10.0.2.2 (a private address: cleartext allowed).
adb shell am start -n "$PKG/.MainActivity" \
  --es ha_url http://10.0.2.2:8123 --es token smoke --es entity media_player.living_room
sleep 25
adb exec-out screencap -p > smoke/1-playing.png
# The very first start takes the connection from its extras: it must not stay on the setup page.
if grep -q "subscribe_trigger" smoke/fake_ha.log; then
  echo "First start: connected."
else
  echo "Note: on the first start Klangbild did not connect (setup page):"
  adb logcat -d -s Klangbild:I | tail -20
fi
# Up opens the settings; Back closes them.
adb shell input keyevent KEYCODE_DPAD_UP
sleep 2
adb exec-out screencap -p > smoke/2-settings.png
adb shell input keyevent KEYCODE_BACK
sleep 2
# The settings menu: open with a long press, move down, close with Back.
adb shell input keyevent --longpress KEYCODE_DPAD_CENTER
sleep 2
adb shell input keyevent KEYCODE_DPAD_DOWN
adb shell input keyevent KEYCODE_DPAD_DOWN
sleep 2
adb exec-out screencap -p > smoke/3-menu.png
adb shell input keyevent KEYCODE_BACK
# The stand-in server starts the next track 40 s after the app subscribed.
sleep 22
adb exec-out screencap -p > smoke/4-next-track.png

# New extras while running must not restart the app.
adb shell am start -n "$PKG/.MainActivity" --es light subtle
sleep 4
adb exec-out screencap -p > smoke/5-light-subtle.png

# The quick settings rise from the bottom edge on down; up closes them.
adb shell input keyevent KEYCODE_DPAD_DOWN
sleep 2
adb exec-out screencap -p > smoke/6-quick-settings.png
adb shell input keyevent KEYCODE_DPAD_UP
sleep 1

# This TV as a speaker: the server Music Assistant 2.10.7 runs (aiosendspin, ci/sendspin_server.py)
# waits on the host. With the setting on, the speaker's service connects to it from the
# emulator and takes a stream (the emulator has no sound) while the app goes on.
SPEAKER_OK=skipped
if [ -n "${SENDSPIN_PYTHON:-}" ]; then
  "$SENDSPIN_PYTHON" -I "$HERE/sendspin_server.py" --port 8927 --timeout 90 --play-seconds 8 --end --linger 10 > smoke/sendspin.log 2>&1 &
  SPEAKER=$!
  adb shell am start -n "$PKG/.MainActivity" --es ma_url http://10.0.2.2:8095 --es ma_token smoke --es tv_speaker on
  wait "$SPEAKER"
  SPEAKER_OK=$?
  adb exec-out screencap -p > smoke/7-speaker.png
fi

PID="$(adb shell pidof "$PKG" | tr -d '\r')"
adb logcat -d > smoke/logcat.txt
adb shell dumpsys gfxinfo "$PKG" > smoke/gfxinfo.txt || true

# Only Klangbild's own crashes count: the Android TV 9 image's Live TV app crashes by itself.
if grep -q "AndroidRuntime: Process: $PKG, " smoke/logcat.txt; then
  echo "The app crashed:"
  grep -B 1 -A 30 "AndroidRuntime: Process: $PKG, " smoke/logcat.txt | head -60
  exit 1
fi
if [ -z "$PID" ]; then
  echo "The app is no longer running."
  exit 1
fi
if ! grep -q "subscribe_trigger" smoke/fake_ha.log || ! grep -q "rest /api/states/media_player.living_room" smoke/fake_ha.log; then
  echo "Klangbild did not connect to the stand-in Home Assistant:"
  cat smoke/fake_ha.log
  exit 1
fi
if [ "$SPEAKER_OK" != skipped ]; then
  # Ready means encrypted, let in and its clock in step; then the stream went to it and ended.
  if [ "$SPEAKER_OK" != 0 ] || ! grep -q "^PLAYER_READY" smoke/sendspin.log || ! grep -q "^STREAM_ENDED" smoke/sendspin.log; then
    echo "The speaker did not take its stream from the Sendspin server:"
    cat smoke/sendspin.log
    exit 1
  fi
  echo "The speaker took a stream from the Sendspin server:"
  grep -E "^(PLAYER_READY|STREAM_|SENT_CHUNKS|CLIENT_DISCONNECTED)" smoke/sendspin.log
fi
if ! grep -q "get_config" smoke/fake_ha.log; then
  echo "Note: Klangbild did not ask Home Assistant where it stands."
fi
echo "What the app logged:"
grep " Klangbild: " smoke/logcat.txt | tail -20 || true
echo "Smoke test passed (PID $PID)."
