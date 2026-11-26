#!/usr/bin/env bash
# Run the complete home instrumentation suite on an explicitly selected, disposable emulator.
set -euo pipefail
[[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" == "1" ]] || { echo "Refusing a physical device" >&2; exit 1; }
[[ "$(adb emu avd name | head -n 1 | tr -d '\r')" == "honeycomb-a16" ]] || { echo "Use the disposable honeycomb-a16 AVD" >&2; exit 1; }
mkdir -p proof
# A hung test must fail within 5 minutes with its name and a thread dump, never hold the runner for the job timeout.
# AndroidJUnitRunner's timeout_msec fails a test body after 4 minutes with the stuck thread's stack (the slowest,
# CutoutClearanceTest, takes about 2). A watchdog covers what that cannot: a rule or @After that never returns (no
# `finished:` line follows `started:`), and an emulator that dies mid-test (run 45201956037: the emulator crashed at
# 15:14:53 as ManyAppsGridTest started, and the EXIT trap's `adb logcat -d` then waited for the vanished device until the
# step's 25-minute limit). Every adb call after the run is bounded by `timeout`, since adb waits forever for a missing device.
hang_seconds=${HANG_SECONDS:-280}
last_started() { grep 'TestRunner: started:' proof/device-live-logcat.txt 2>/dev/null | tail -n 1 | sed 's/.*started: //'; }
dump_threads() {
  # debuggerd needs root; the test AVD is a userdebug image, so adb root works there.
  timeout 30s adb root >/dev/null 2>&1 && timeout 30s adb wait-for-device || true
  local pid; pid=$(timeout 10s adb shell pidof org.honeycomb.launcher | tr -d '\r') || true
  if [[ -n $pid ]]; then timeout 60s adb shell debuggerd -j "$pid" > proof/hung-threads.txt 2>&1 || true; fi
  grep -A 40 '^"Instr\|^"Time-limited test\|^"main"' proof/hung-threads.txt >&2 || true
}
instrument() {
  local out=$1; shift
  adb shell am instrument -w -e timeout_msec 240000 "$@" \
    org.honeycomb.launcher.test/androidx.test.runner.AndroidJUnitRunner > "$out" 2>&1 &
  local run=$! seen="" since=$SECONDS printed=0
  while kill -0 "$run" 2>/dev/null; do
    sleep 5
    # stream the runner's output to the job log as it arrives
    local size; size=$(wc -c < "$out"); (( size > printed )) && { tail -c +$((printed + 1)) "$out" | head -c $((size - printed)); printed=$size; }
    if [[ $(timeout 10s adb get-state 2>/dev/null | tr -d '\r') != device ]]; then
      echo "::error::The emulator went away during $(last_started)" >&2
      kill "$run" 2>/dev/null || true; return 1
    fi
    local now; now=$(grep -c 'TestRunner: \(started\|finished\):' proof/device-live-logcat.txt 2>/dev/null || true)
    [[ $now != "$seen" ]] && { seen=$now; since=$SECONDS; }
    if (( SECONDS - since >= hang_seconds )); then
      echo "::error::$(last_started) has not finished after ${hang_seconds} s; app threads are in proof/hung-threads.txt" >&2
      dump_threads
      kill "$run" 2>/dev/null || true; return 1
    fi
  done
  tail -c +$((printed + 1)) "$out"
  wait "$run" || true
}
# Stream only test/runtime diagnostics so an emulator exit cannot erase the last test's evidence.
adb shell logcat -s AndroidRuntime TestRunner > proof/device-live-logcat.txt 2>&1 &
logcat_pid=$!
adb logcat -G 16M || true
# Per-thread CPU every 10 s, so a slow first test shows what the device was busy with.
adb shell top -H -b -d 10 -n 30 -m 25 > proof/device-top.txt 2>&1 &
top_pid=$!
trap 'kill "$logcat_pid" "$top_pid" 2>/dev/null || true; timeout 10s adb get-state >/dev/null 2>&1 || exit 1; timeout 60s adb logcat -d -v threadtime > proof/device-full-logcat.txt 2>&1 || true; timeout 60s adb shell logcat -d -s AndroidRuntime TestRunner > proof/device-logcat.txt 2>&1 || true; timeout 120s adb pull /sdcard/Android/data/org.honeycomb.launcher/files/evidence proof/ >/dev/null 2>&1 || true' EXIT
adb install -r launcher/build/outputs/apk/debug/launcher-debug.apk
adb install -r launcher/build/outputs/apk/androidTest/debug/launcher-debug-androidTest.apk
# This dedicated test AVD is reset so repeated runs contain no keys, accounts or leftover fixtures.
adb shell pm clear org.honeycomb.launcher
adb shell wm size 480x854
adb shell wm density 220
adb shell cmd package set-home-activity org.honeycomb.launcher/.LauncherActivity
# LiveStackShots exercises the real notification listener and active media sessions.
adb shell cmd notification allow_listener org.honeycomb.launcher/.MediaListener
# A freshly booted emulator spends minutes on first-boot work with both cores busy (frames of up to 16 s), so wait,
# up to 5 minutes, until the CPU has been at least half idle over two 10-second windows in a row.
cpu() { adb shell head -n 1 /proc/stat | awk '{print $5+$6, $2+$3+$4+$5+$6+$7+$8}'; }
calm=0
for _ in $(seq 30); do
  read -r i0 t0 < <(cpu); sleep 10; read -r i1 t1 < <(cpu)
  idle=$(( 100 * (i1 - i0) / (t1 - t0 + 1) ))
  echo "cpu idle ${idle}%" | tee -a proof/settle.txt
  if (( idle >= 50 )); then calm=$((calm + 1)); else calm=0; fi
  (( calm >= 2 )) && break
done
instrument proof/instrumentation.txt
timeout 120s adb pull /sdcard/Android/data/org.honeycomb.launcher/files/evidence proof/ || true
# am instrument can exit 0 even when JUnit failed.
grep -q '^OK (' proof/instrumentation.txt
! grep -qE 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' proof/instrumentation.txt

# Also exercise the non-debuggable, R8-shrunk App APK with the same package/certificate, with the test APK built for that
# build type (-PtestBuildType=app).
adb install -r launcher/build/outputs/apk/app/launcher-app.apk
adb install -r launcher/build/outputs/apk/androidTest/app/launcher-app-androidTest.apk
adb shell pm clear org.honeycomb.launcher
instrument proof/app-instrumentation.txt -e class org.honeycomb.launcher.HomeFeaturesTest
timeout 120s adb pull /sdcard/Android/data/org.honeycomb.launcher/files/evidence proof/ || true
grep -q '^OK (' proof/app-instrumentation.txt
! grep -qE 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' proof/app-instrumentation.txt
