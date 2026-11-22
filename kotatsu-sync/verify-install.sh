#!/usr/bin/env bash
# Guest-only connected check, before instrumentation starts the target process.
# Run on a disposable API31 emulator with fake-mudita-calendar installed.
set -Eeuo pipefail
[[ $(id -u) == 1000 && -d /srv/kotatsu/source ]] || exit 2
[[ $# == 1 ]] || exit 2
E=$1
mkdir -p "$E"
ADB=${ADB:-/srv/kotatsu/android-sdk/platform-tools/adb}
export ANDROID_SERIAL=emulator-5554
P=com.alexindigo.kotatsu.sync.debug
APK=kotatsu-sync/build/outputs/apk/debug/kotatsu-sync-debug.apk
poll_pass() {
    local label=$1
    for ((i=0; i<120; i++)); do
        "$ADB" shell content call --uri "content://$P.test-entry" --method probe >"$E/$label-probe.txt"
        if grep -q 'status=calendar pass complete' "$E/$label-probe.txt" &&
           grep -q '4936449,4936450,4936451' "$E/$label-probe.txt"; then return; fi
        sleep 0.25
    done
    cat "$E/$label-probe.txt"
    return 1
}
# A never-launched package is stopped and cannot receive boot. No reboot or UI.
"$ADB" shell pm path "$P" >"$E/before.txt" || true
if grep -q '^package:' "$E/before.txt"; then echo 'requires a fresh emulator without Sync installed' >&2; exit 1; fi
"$ADB" install -g "$APK"
"$ADB" shell dumpsys package "$P" >"$E/fresh-package.txt"
grep -q 'stopped=true' "$E/fresh-package.txt"
poll_pass fresh
"$ADB" shell dumpsys jobscheduler >"$E/fresh-jobs.txt"
# Remove all persisted app state and jobs, then replace the installed APK. The
# package stays launched: MY_PACKAGE_REPLACED itself must recreate the jobs/pass.
"$ADB" shell cmd jobscheduler cancel "$P"
"$ADB" shell "run-as $P rm -f databases/calendar-bridge.db databases/calendar-bridge.db-wal databases/calendar-bridge.db-shm files/startup-probe.txt"
"$ADB" install -r -g "$APK"
# Inspect jobs before sending another entry request, so the probe cannot hide a
# missing replacement broadcast registration.
for ((i=0; i<120; i++)); do
    "$ADB" shell dumpsys jobscheduler >"$E/replaced-jobs.txt"
    if grep -q "$P/com.alexindigo.kotatsu.sync.PeriodicPassJob" "$E/replaced-jobs.txt"; then break; fi
    sleep 0.25
done
grep -q "$P/com.alexindigo.kotatsu.sync.PeriodicPassJob" "$E/replaced-jobs.txt"
poll_pass replaced
echo 'fresh install and install -r: no reboot, no UI; jobs armed and calendar pass completed'
