#!/usr/bin/env bash
# EXISTS: https://developer.android.com/training/testing/instrumented-tests ; scripts/test-launcher-device.sh
set -euo pipefail
if [[ ${1:-} == --suite-then-walk ]]; then
  shift
  (($# == 8)) || { echo 'usage: --suite-then-walk HOST REMOTE_RUNNER SERIAL NEW_REMOTE_OUT BOUND_AGENT_DEVICE WIDTH HEIGHT NEW_LOCAL_OUT' >&2; exit 2; }
  [[ $1 =~ ^[A-Za-z0-9][A-Za-z0-9._@-]*$ && $2 == /* && $4 == /* && ! -e $8 ]] || exit 2
  printf -v fresh 'test ! -e %q' "$4"; ssh "$1" "$fresh" || exit 2
  printf -v suite '%q %q %q' "$2" "$3" "$4"
  rc=0; ssh "$1" "$suite" || rc=$?
  # Never attach the interactive driver while the test process is active or if preflight refused it.
  printf -v finished 'grep -q %q %q' '^INSTRUMENTATION_CODE:' "$4/instrumentation.txt"
  ssh "$1" "$finished" || { echo 'No completed instrumentation run; not opening the UI driver' >&2; exit 2; }
  "$0" --walk "$5" "$6" "$7" "$8" || rc=1
  exit "$rc"
fi
if [[ ${1:-} == --firebase ]]; then
  shift
  project=${1:?missing Spark project}; kind=${2:?instrumentation or robo}; app=${3:?missing APK}; shift 3
  args=(--type "$kind" --app "$app" --project="$project" --quiet)
  case "$kind" in
    instrumentation) args+=(--test "${1:?missing test APK}" --directories-to-pull /sdcard/Android/data/org.honeycomb.launcher/files/evidence); shift;;
    robo) ;;
    *) exit 2;;
  esac
  (($# > 0 && $# <= 5)) || { echo 'Supply 1–5 physical MODEL:API pairs' >&2; exit 2; }
  [[ $(gcloud billing projects describe "$project" --format='value(billingEnabled)') == False ]] || { echo 'Billing is enabled or unknown; refusing' >&2; exit 2; }
  for pair do
    [[ $pair =~ ^([A-Za-z0-9_.-]+):([0-9]+)$ ]] || exit 2
    model=${BASH_REMATCH[1]}; api=${BASH_REMATCH[2]}; ((api >= 34)) || exit 2
    gcloud firebase test android models describe "$model" --project="$project" --format=json |
      python3 -I -c 'import json,sys; m=json.load(sys.stdin);sys.exit(0 if m.get("form")=="PHYSICAL" and sys.argv[1] in map(str,m.get("supportedVersionIds", [])) else 1)' "$api"
    args+=(--device "model=$model,version=$api")
  done
  # No flaky retries. With billing disabled, Test Lab enforces the daily project quota.
  exec gcloud firebase test android run "${args[@]}" --num-flaky-test-attempts=0
fi
if [[ ${1:-} == --walk ]]; then
  # BOUND_AGENT_DEVICE is an agent-device executable already bound to this emulator (open, press, swipe, snapshot, screenshot).
  shift
  python3 -I - "$@" <<'PY'
import json, pathlib, re, subprocess, sys, tempfile, time
from PIL import Image
if len(sys.argv) != 5: sys.exit('usage: --walk BOUND_AGENT_DEVICE WIDTH HEIGHT NEW_OUTPUT_DIR')
driver, w, h, dest = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), pathlib.Path(sys.argv[4]); dest.mkdir(parents=True)
checks = {}
def run(*args):
    proc = subprocess.run([driver,*map(str,args)], capture_output=True, text=True, timeout=90)
    if proc.returncode: raise RuntimeError((proc.stderr or proc.stdout).strip())
    return proc.stdout

def swipe(x,y,xx,yy): run('swipe',int(w*x),int(h*y),int(w*xx),int(h*yy))
def capture(name, pattern, required=True):
    tree = ''
    for _ in range(5):
        tree = run('snapshot','--force-full')
        if re.search(pattern, tree, re.I): break
        time.sleep(.4)
    if not re.search(pattern,tree,re.I):
        tree += '\n'+run('snapshot','--raw','--force-full')
    with tempfile.TemporaryDirectory() as tmp:
        shot = pathlib.Path(tmp)/'screen.png'; run('screenshot',shot)
        im=Image.open(shot).convert('RGB'); im.thumbnail((1600,1600)); im.save(dest/(name+'.jpg'),quality=85)
    checks[name] = 'pass' if re.search(pattern,tree,re.I) else 'fail'
    if required and checks[name] != 'pass': raise RuntimeError('Unexpected screen: '+name)
    return tree

def tap_label(tree, text):
    ref = re.search(r'(@e\d+) \[(?:button|group|image)\] "'+re.escape(text)+'"', tree)
    if ref: run('press',ref[1]); return
    for line in run('snapshot','--raw','--force-full').splitlines():
        if not line.startswith('{'): continue
        node=json.loads(line)
        if node.get('label') == text and node.get('visibleToUser') and node.get('ref'):
            run('press','@'+node['ref']); return
    raise RuntimeError('Missing button: '+text)
try:
    run('open','org.honeycomb.launcher','--activity','.LauncherActivity'); capture('face',r'"Stacked"')
    run('press',w//2,h//2); tree=capture('apps',r'"(All apps|List View|Grid View|Settings|Phone)"')
    # Opening a different package can produce an escape warning. Verify its UI independently.
    if '"Settings"' in tree:
        try: tap_label(tree,'Settings')
        except RuntimeError as exc:
            if 'foregrounded com.android.settings' not in str(exc): raise
    else: run('open','com.android.settings')
    capture('external-app',r'com.android.settings:id/|Search Settings')
    swipe(.5,.999,.5,.37); capture('line-swipe-to-face',r'"Stacked"',False)
    if checks['line-swipe-to-face'] != 'pass':
        run('back'); capture('face-recovered',r'"Stacked"')
    swipe(.5,.72,.5,.27); capture('smart-stack',r'\[button\] "Edit"'); swipe(.5,.27,.5,.72)
    swipe(.22,.003,.22,.48); capture('notification-centre',r'Clear\s+all|No\s+notifications',False); swipe(.5,.75,.5,.13)
    swipe(.82,.003,.82,.48); capture('control-centre',r'Airplane mode')
    run('open','org.honeycomb.launcher','--activity','.HoneycombSettingsActivity'); tree=run('snapshot'); tap_label(tree,'Run setup again')
    capture('setup',r'\[button\] "(Skip|Next|Done|Continue|Finish)"')
finally:
    (dest/'walk.json').write_text(json.dumps(checks,indent=2)+'\n')
    # End the bound walk, not the AVD. Android may immediately foreground its Home app;
    # the next suite independently releases the helper and verifies the service is free.
    closed = subprocess.run([driver,'close'], capture_output=True, text=True, timeout=30)
    if closed.returncode: print('Walk close: '+(closed.stderr or closed.stdout).strip(),file=sys.stderr)
sys.exit(0 if all(v=='pass' for v in checks.values()) else 1)
PY
  exit
fi
if [[ ${1:-} == --profiles ]]; then
  shift; port=${1:?missing free emulator port}; out=${2:?missing new output directory}; shift 2
  [[ $port =~ ^[0-9]{4}$ ]] && (( port >= 5554 && port <= 5682 && port % 2 == 0 )) || exit 2
  case "$port" in 5572|5590|5592) exit 2;; esac
  (($#)) || exit 2
  mkdir -p "$(dirname "$out")"; mkdir "$out"
  rc=0; serial=emulator-$port
  for profile do
    [[ $profile =~ ^honeycomb-matrix-[A-Za-z0-9_-]+$ ]] || exit 2
    emulator -list-avds | grep -Fx "$profile" >/dev/null || exit 2
    if ss -H -lnt "sport = :$port or sport = :$((port+1))" | grep -q .; then echo "Busy emulator port $port" >&2; exit 2; fi
    emulator -avd "$profile" -port "$port" -read-only -no-snapshot -no-window -no-audio -no-boot-anim \
      -gpu lavapipe -cores 2 -memory 2048 > "$out/$profile-boot.log" 2>&1 &
    child=$!; trap 'kill "$child" 2>/dev/null || true' EXIT
    ready=0
    for _ in $(seq 90); do
      kill -0 "$child" 2>/dev/null || break
      if [[ $(adb -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r') == 1 ]]; then ready=1; break; fi
      sleep 3
    done
    if ((ready)) && [[ $(adb -s "$serial" emu avd name | head -1 | tr -d '\r') == "$profile" ]]; then
      "$0" "$serial" "$out/$profile" || rc=1
      adb -s "$serial" emu kill >/dev/null || true
    else rc=1; kill "$child" 2>/dev/null || true; fi
    wait "$child" || true; trap - EXIT
  done
  python3 -I - "$out" "$@" <<'PY'
import json, pathlib, sys
out = pathlib.Path(sys.argv[1]); rows = []
for name in sys.argv[2:]:
    path = out/name/'result.json'
    rows.append(json.loads(path.read_text()) if path.exists() else dict(device=name,suite='incomplete'))
(out/'results.json').write_text(json.dumps({'rows':rows},indent=2)+'\n')
PY
  exit "$rc"
fi
if [[ ${1:-} == --batch ]]; then
  shift; out=${1:?usage: --batch NEW_OUTPUT_DIRECTORY emulator-PORT...}; shift
  (($# > 0)) || exit 2
  mkdir -p "$(dirname "$out")"; mkdir "$out"
  rc=0
  for serial do
    [[ $serial =~ ^emulator-[0-9]+$ ]] || exit 2
    "$0" "$serial" "$out/$serial" || rc=1
  done
  python3 -I - "$out" "$@" <<'PY'
import json, pathlib, sys
out = pathlib.Path(sys.argv[1])
rows = []
for serial in sys.argv[2:]:
    path = out/serial/'result.json'
    rows.append(dict(serial=serial, **json.loads(path.read_text())) if path.exists() else dict(serial=serial, suite='incomplete'))
(out/'results.json').write_text(json.dumps({'rows':rows}, indent=2)+'\n')
PY
  exit "$rc"
fi
serial=${1:?usage: test-matrix.sh emulator-PORT NEW_OUTPUT_DIRECTORY}
out=${2:?missing output directory}
case "$serial" in emulator-5572|emulator-5590|emulator-5592) exit 2;; esac
[[ $serial =~ ^emulator-[0-9]+$ ]] || exit 2
a() { adb -s "$serial" "$@"; }
name=$(a emu avd name | head -1 | tr -d '\r')
[[ $name == honeycomb-matrix-* && $(a shell getprop ro.kernel.qemu | tr -d '\r') == 1 ]] || { echo 'Refusing non-matrix device' >&2; exit 2; }
mkdir -p "$(dirname "$out")"; mkdir "$out"
root=$(cd "$(dirname "$0")/.." && pwd)
base=$root/launcher/build/outputs/apk
{
  echo "$name"; a shell getprop ro.build.fingerprint; a shell wm size; a shell wm density
  printf 'Android release: '; a shell getprop ro.build.version.release
  printf 'API level: '; a shell getprop ro.build.version.sdk
  printf 'Checkout: '; git -C "$root" rev-parse HEAD 2>/dev/null || echo unknown
  a shell cmd overlay list | grep 'systemui.navbar' || true
  sha256sum "$base/debug/launcher-debug.apk" "$base/androidTest/debug/launcher-debug-androidTest.apk"
} > "$out/environment.txt"
[[ $(a shell settings get secure navigation_mode | tr -d '\r') == 2 ]] || { echo 'Gesture navigation is not active' >&2; exit 2; }
# Espresso needs exclusive ownership of Android's UiAutomation connection. End any
# leftover snapshot instrumentation only on the disposable, name-checked AVD above.
a shell am force-stop com.callstack.agentdevice.snapshothelper
rc=0; a shell pkill -f '[u]iautomator' || rc=$?
((rc == 0 || rc == 1)) || { echo "pkill uiautomator failed: $rc" >&2; exit 2; }
for _ in $(seq 10); do
  access=$(a shell dumpsys accessibility) || exit 2
  grep -iq 'accessibility manager' <<<"$access" || { echo 'Cannot inspect accessibility service' >&2; exit 2; }
  attached=$(printf '%s\n' "$access" | awk 'tolower($0) ~ /ui[[:space:]_-]*automation/ && tolower($0) !~ /[=:][[:space:]]*(null|false|none)([[:space:],;}]|$)/ {print}')
  [[ -n $attached ]] || break
  sleep 1
done
if [[ -n $attached ]]; then
  printf '%s\n' "$attached" > "$out/automation-blocker.txt"
  echo 'UiAutomation still attached: suite not started (harness conflict)' >&2; exit 2
fi
printf 'snapshot helper stopped; uiautomator pkill exit %d; no active UiAutomation in dumpsys accessibility\n' "$rc" > "$out/automation-preflight.txt"
a install -r "$base/debug/launcher-debug.apk"
a install -r "$base/androidTest/debug/launcher-debug-androidTest.apk"
a shell pm clear org.honeycomb.launcher
a shell cmd package set-home-activity org.honeycomb.launcher/.LauncherActivity
pkg=$(a shell dumpsys package org.honeycomb.launcher)
if grep -Eq '^[[:space:]]+android\.permission\.READ_CALENDAR[[:space:]]*$' <<<"$pkg"; then
  a shell pm grant org.honeycomb.launcher android.permission.READ_CALENDAR
fi
rc=0
timeout 35m adb -s "$serial" shell am instrument -w -r org.honeycomb.launcher.test/androidx.test.runner.AndroidJUnitRunner > "$out/instrumentation.txt" 2>&1 || rc=$?
a pull /sdcard/Android/data/org.honeycomb.launcher/files/evidence "$out/" >/dev/null 2>&1 || true
if [[ -d $out/evidence ]]; then
  python3 -I - "$out/evidence" <<'PY'
from pathlib import Path
from PIL import Image
import sys
for path in Path(sys.argv[1]).rglob('*.png'):
    with Image.open(path) as src:
        image = src.convert('RGB')
        image.thumbnail((1600, 1600), Image.Resampling.LANCZOS)
        image.save(path.with_suffix('.jpg'), quality=85)
PY
fi
python3 -I - "$out" "$name" "$rc" <<'PY'
import json, pathlib, re, sys
out, name, exit_code = pathlib.Path(sys.argv[1]), sys.argv[2], int(sys.argv[3])
text = (out / 'instrumentation.txt').read_text(errors='replace')
observed, failures = {}, {}
case = reason = None
for line in text.splitlines():
    if line.startswith('INSTRUMENTATION_STATUS: stack='):
        reason = line.partition('stack=')[2]
    if line.startswith('INSTRUMENTATION_STATUS: test='):
        case = line.partition('test=')[2]
    match = re.fullmatch(r'INSTRUMENTATION_STATUS_CODE: (-?\d+)', line)
    if case and match and int(match[1]) <= 0:
        observed[case] = {0:'pass', -1:'fail', -2:'fail', -3:'skipped', -4:'skipped'}.get(int(match[1]), 'unknown')
        if observed[case] == 'fail' and reason: failures[case] = reason
        case = reason = None
methods = {'face_to_apps':'aTapOnTheFaceOpensFavourites', 'smart_stack':'swipeUpOnTheFaceOpensTheSmartStackAndSwipeDownReturns', 'notification_centre':'swipeDownFromTheTopLeftOrCentreOpensNotificationCentre', 'control_centre':'swipeDownFromTheTopRightThirdOpensControlCentreAndSwipeUpCloses', 'setup':'everyExplanationCanScrollClearOfItsAction', 'layout':'everyScreenFitsThisPhone'}
steps = {key: observed.get(method, 'not_run') for key, method in methods.items()}
steps['face'] = 'observed' if (out/'evidence/phone-sizes/face.jpg').is_file() else 'not_verified'
steps['line_swipe_from_app'] = 'not_verified'
passed = bool(re.search(r'^OK \([1-9]\d* tests?\)', text, re.M))
row = dict(device=name, environment=(out/'environment.txt').read_text().splitlines(), suite='pass' if passed and not exit_code else 'fail' if 'fail' in observed.values() else 'incomplete', steps=steps, methods=observed, failures=failures, screenshots=sorted(str(p.relative_to(out)) for p in out.rglob('*.jpg')))
(out/'result.json').write_text(json.dumps(row, indent=2)+'\n')
PY
((rc == 0)) && grep -Eq '^OK \([1-9][0-9]* tests?\)' "$out/instrumentation.txt" && ! grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' "$out/instrumentation.txt"
