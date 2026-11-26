#!/usr/bin/env bash
# See docs/setup-phone.md. All Android changes are scoped to the named device.
set -euo pipefail
umask 077
die() { printf 'setup-phone: %s\n' "$*" >&2; exit 1; }
usage='usage: setup-phone.sh --serial SERIAL APK | setup-phone.sh --serial SERIAL --undo'
[[ $# == 3 && $1 == --serial && $2 =~ ^[a-zA-Z0-9][a-zA-Z0-9_.:-]*$ ]] || die "$usage"
serial=$2; arg=$3; pkg=org.honeycomb.launcher; role=android.app.role.HOME
listener=$pkg/$pkg.MediaListener
overlay=com.android.internal.systemui.navbar.gestural
adbdev() { adb -s "$serial" "$@"; }
shell() { adbdev shell "$@" | tr -d '\r'; }
holder() { shell cmd role get-role-holders --user 0 "$role"; }
set_home() {
  shell cmd role add-role-holder --user 0 "$role" "$1" || :
  if [[ $(holder) != "$1" ]]; then shell cmd package set-home-activity --user 0 "$1"; fi
  [[ $(holder) == "$1" ]] || die "Could not verify Home holder $1"
}
mode() { shell settings --user 0 get secure navigation_mode; }
wait_mode() {
  local i; for i in 1 2 3 4 5; do [[ $(mode) == "$1" ]] && return; sleep 1; done
  die "Expected navigation_mode=$1; found $(mode)"
}
[[ $(adbdev get-state) == device ]] || die 'Device offline or not authorized'
api=$(shell getprop ro.build.version.sdk); [[ $api =~ ^[0-9]+$ && $api -ge 34 ]] || die 'Requires Android 14 or newer'
[[ $(shell am get-current-user) == 0 ]] || die 'Switch to user 0 first'
id=$(shell settings --user 0 get secure android_id); [[ $id =~ ^[a-fA-F0-9]+$ ]] || die 'Missing device identity'
dir=${XDG_STATE_HOME:-$HOME/.local/state}/honeycomb; state=$dir/$serial-$id
if [[ $arg == --undo ]]; then
  [[ -f $state/before ]] || die 'No setup record for this phone on this computer'
  { IFS= read -r old; IFS= read -r nav; IFS= read -r enabled; } < "$state/before"
  if [[ -n $old ]]; then
    [[ $old =~ ^[A-Za-z0-9_.]+$ ]] || die 'Invalid saved holder'; set_home "$old"
  elif [[ $(holder) == "$pkg" ]]; then shell cmd role remove-role-holder --user 0 "$role" "$pkg"; fi
  if [[ $enabled == no && -f $state/overlay ]]; then shell cmd overlay disable --user 0 "$overlay"; fi
  wait_mode "$nav"
  # Remove only the installation introduced by this transaction (including its grants).
  if [[ -f $state/install && $(shell pm list packages --user 0 "$pkg") == "package:$pkg" ]]; then
    shell cmd notification disallow_listener "$listener" 0
    shell pm uninstall --user 0 "$pkg" | grep -Fx Success
  fi
  mv "$state" "$state.undone.$(date +%s).$$"; echo "Restored Home: $(holder)"; exit
fi
[[ -f $arg ]] || die "$usage"
# Validate locally before changing any package. Never guess the package from its filename.
aapt=${AAPT:-aapt}; badging=$("$aapt" dump badging "$arg") || die 'Cannot inspect APK (set AAPT to Android Build Tools aapt)'
[[ $(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<< "$badging") == "$pkg" ]] || die 'Not a Honeycomb launcher APK'
users=$(shell pm list users); user_ids=$(sed -n 's/.*UserInfo{\([0-9]*\):.*/\1/p' <<< "$users"); [[ -n $user_ids ]] || die 'Cannot inspect installed users'
for u in $user_ids; do
  packages=$(shell pm list packages -u --user "$u" "$pkg"); [[ $packages != *"package:$pkg"* ]] || die 'Honeycomb already exists; refusing to overwrite its data'
done
old=$(holder); [[ -z $old || $old =~ ^[A-Za-z0-9_.]+$ ]] || die 'Could not read existing Home holder'
nav=$(mode); [[ $nav =~ ^[012]$ ]] || die 'Cannot record navigation mode'
list=$(shell cmd overlay list --user 0); enabled=no
if grep -Fxq "[x] $overlay" <<< "$list"; then enabled=yes; elif ! grep -Fxq "[ ] $overlay" <<< "$list" && [[ $nav != 2 ]]; then die 'Gesture overlay unavailable'; fi
mkdir -p "$dir"; mkdir "$state" || die 'Setup already recorded; use --undo first'
printf '%s\n' "$old" "$nav" "$enabled" > "$state/before"
trap 'echo "Setup interrupted; run --undo with the same serial and computer" >&2' ERR
: > "$state/install"; adbdev install --user 0 "$arg"
set_home "$pkg"
optional() { "$@" || printf 'Optional feature unavailable: %s\n' "$*" >&2; }
# AppOps normally delays its disk write. A reboot immediately after setup can lose the grant.
optional shell appops set --user 0 "$pkg" GET_USAGE_STATS allow
if shell appops set --user 0 --uid "$pkg" GET_USAGE_STATS allow; then
  optional shell cmd appops write-settings
  # Android 16 also persists permission state asynchronously; allow that write before returning.
  sleep 6
else
  echo 'Usage access was not granted' >&2
fi
# NotificationManager returns before the secure setting reflects its grant.
wait_listener() {
  local attempt listeners
  for attempt in 1 2 3 4 5 6; do
    listeners=$(shell settings --user 0 get secure enabled_notification_listeners) || return 1
    case ":$listeners:" in *":$listener:"*) return 0;; esac
    [[ $attempt == 6 ]] || sleep 1
  done
  return 1
}
if shell cmd notification allow_listener "$listener" 0 && wait_listener; then
  echo 'Notification access confirmed'
else
  echo 'Notification access was not confirmed; Now Playing needs it.' >&2
fi
shell appops get --user 0 --uid "$pkg" GET_USAGE_STATS | grep -Eq 'GET_USAGE_STATS: allow' || echo 'Usage access was not confirmed' >&2
if [[ $nav != 2 ]]; then : > "$state/overlay"; shell cmd overlay enable --user 0 "$overlay"; fi
wait_mode 2
printf 'Home: %s\nnavigation_mode: %s\nUndo record: %s\n' "$(holder)" "$(mode)" "$state"
