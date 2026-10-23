#!/usr/bin/env bash
# Runs the emulator UI tests (called by .github/workflows/android.yml inside the emulator runner).
# When the emulator itself vanishes (an emulator crash, not a test failure), the file `emulator_vanished` is
# written so the workflow can run everything once more on a fresh emulator.
rm -f emulator_vanished
adb shell settings put system font_scale 1.3
(adb logcat -v time > logcat.txt 2>/dev/null &)
if ! ./gradlew :app:connectedDebugAndroidTest --console=plain -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
    -Pandroid.testInstrumentationRunnerArguments.notClass=de.pianotiles.app.HandsShotTest; then
  timeout 60 adb pull /sdcard/Android/data/de.pianotiles.app/files/screenshots screenshots
  timeout 60 adb logcat -d | grep -E "AndroidRuntime|FATAL|ANR|Fatal signal" | tail -60
  echo "==== LOGCAT TAIL ===="
  grep -iE "HandRender|pianotiles|AndroidRuntime|FATAL|lowmemorykiller|lmkd|Kill|EGL|gralloc|vulkan|OpenGL|ANR|watchdog|audio" logcat.txt | tail -120
  echo "==== HOST ===="
  free -m; uptime
  sudo dmesg 2>/dev/null | grep -iE "oom|killed process|segfault|qemu|emulator|general protection" | tail -20
  find /tmp/android-runner -type f 2>/dev/null | head -20
  echo "==== FAILED TESTS ===="
  find app/build/outputs/androidTest-results -name "*.xml" -exec grep -h -A14 "<failure" {} + | head -80
  if ! timeout 10 adb get-state >/dev/null 2>&1; then echo "==== EMULATOR VANISHED ===="; touch emulator_vanished; fi
  exit 1
fi
timeout 60 adb pull /sdcard/Android/data/de.pianotiles.app/files/screenshots screenshots || true
# the 3D hands separately: the emulator's renderer has crashed on them before, which must not hide the results above
timeout 600 ./gradlew :app:connectedDebugAndroidTest --console=plain -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  -Pandroid.testInstrumentationRunnerArguments.class=de.pianotiles.app.HandsShotTest || echo "==== HANDS TEST FAILED (not blocking) ===="
# into the folder itself, not a nested one
(mkdir -p screenshots && cd screenshots && timeout 60 adb pull /sdcard/Android/data/de.pianotiles.app/files/screenshots/. .) || true
# render time of the hands on the emulator
grep "HandRender" logcat.txt | tail -5 || true
