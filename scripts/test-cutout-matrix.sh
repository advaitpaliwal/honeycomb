#!/usr/bin/env bash
# CutoutClearanceTest over every phone size and every display-cutout overlay, on the disposable honeycomb-a16 emulator.
set -euo pipefail
[[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" == "1" ]] || { echo "Refusing a physical device" >&2; exit 1; }
[[ "$(adb emu avd name | head -n 1 | tr -d '\r')" == "honeycomb-a16" ]] || { echo "Use the disposable honeycomb-a16 AVD" >&2; exit 1; }
mkdir -p proof
adb install -r launcher/build/outputs/apk/debug/launcher-debug.apk
adb install -r launcher/build/outputs/apk/androidTest/debug/launcher-debug-androidTest.apk
adb shell pm clear org.honeycomb.launcher
adb shell cmd package set-home-activity org.honeycomb.launcher/.LauncherActivity
adb shell am instrument -w -e cutoutMatrix full -e class org.honeycomb.launcher.CutoutClearanceTest \
  org.honeycomb.launcher.test/androidx.test.runner.AndroidJUnitRunner | tee proof/cutout-matrix.txt
adb pull /sdcard/Android/data/org.honeycomb.launcher/files/evidence/cutout proof/ || true
grep -q '^OK (' proof/cutout-matrix.txt
! grep -qE 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' proof/cutout-matrix.txt
