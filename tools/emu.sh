#!/bin/bash
# Drive the Android emulator from the command line (Git Bash on Windows or Linux/macOS).
#   tools/emu.sh start [AVD]      start the emulator (default: first AVD) and wait until booted
#   tools/emu.sh install          build the debug APK, install it and launch the app
#   tools/emu.sh shot NAME [WAIT] screenshot after WAIT seconds (default 1) -> $EMU_OUT/NAME.png
#   tools/emu.sh tap X Y          tap at screen pixels
#   tools/emu.sh tapt TEXT        tap the first element whose text/description contains TEXT (waits up to 15 s)
#   tools/emu.sh back             Android back button
#   tools/emu.sh <adb args...>    anything else goes straight to adb
export MSYS_NO_PATHCONV=1 # keep /sdcard paths intact in Git Bash
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
ADB="$SDK/platform-tools/adb"; [ -x "$ADB.exe" ] && ADB="$ADB.exe"
OUT="${EMU_OUT:-build/emu-shots}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
case "$1" in
  start)
    AVD="${2:-$("$SDK/emulator/emulator" -list-avds | head -1 | tr -d '\r')}"
    "$SDK/emulator/emulator" -avd "$AVD" -no-snapshot-save -no-boot-anim >/dev/null 2>&1 &
    "$ADB" wait-for-device
    until [ "$("$ADB" shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do sleep 2; done
    echo "booted: $AVD";;
  install)
    (cd "$ROOT" && ./gradlew :app:assembleDebug -q) || exit 1
    "$ADB" install -r "$ROOT/app/build/outputs/apk/debug/app-debug.apk" >/dev/null
    "$ADB" shell pm grant de.pianotiles.app android.permission.RECORD_AUDIO
    "$ADB" shell am start -S -n de.pianotiles.app/.MainActivity >/dev/null && echo installed;;
  shot) mkdir -p "$OUT"; sleep "${3:-1}"; "$ADB" exec-out screencap -p > "$OUT/$2.png"; echo "$OUT/$2.png";;
  tap) "$ADB" shell input tap "$2" "$3";;
  tapt)
    for _ in $(seq 1 15); do
      "$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
      XY=$("$ADB" shell cat /sdcard/ui.xml | python -c "
import sys, re
x = sys.stdin.buffer.read().decode('utf-8'); q = sys.argv[1]
for m in re.finditer(r'<node [^>]*>', x):
    n = m.group(0)
    t = re.search(r'text=\"([^\"]*)\"', n).group(1) + ' ' + re.search(r'content-desc=\"([^\"]*)\"', n).group(1)
    if q in t:
        a, b, c, d = map(int, re.search(r'bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]', n).groups())
        print((a + c) // 2, (b + d) // 2); break
" "$2")
      if [ -n "$XY" ]; then "$ADB" shell input tap $XY; echo "tapped '$2' at $XY"; exit 0; fi
      sleep 1
    done
    echo "NOT FOUND: $2"; exit 1;;
  back) "$ADB" shell input keyevent 4;;
  *) "$ADB" "$@";;
esac
