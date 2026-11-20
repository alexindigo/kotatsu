#!/usr/bin/env bash
# Shared API 31 emulator preparation for CI and the VM gate. Run from the repository root.
#   configure-avd <avd-dir>        apply avd.conf to a freshly created AVD
#   device <evidence-dir>          booted device -> device.conf state (settings, HOME, rotation owner)
#   requirements <suite>           APKs a suite needs installed besides its own
#   install <suite> <evidence-dir> install those APKs; Kotatsu also completes onboarding
#   module <suite> | apk <module> <debug|androidTest>   suite module and built APK paths
set -Eeuo pipefail
CI_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ADB=${ADB:-adb}
export ANDROID_SERIAL=${ANDROID_SERIAL:-emulator-5554}
declare -A MODULE=([kotatsu]=app [weather]=kotatsu-weather [notes]=kotatsu-notes [mudita]=weather-mudita)
# Single dependency owner: consumer -> provider:APK-kind. A missing Notes module is optional.
declare -A REQUIRED=(
    [kotatsu]='weather:debug weather:androidTest notes:debug notes:androidTest'
    [weather]=''
    [notes]=''
    [mudita]=''
)

log() { printf '%s %s\n' "$(date -Is)" "$*"; }
step() {
    local name=$1 budget=$2 rc=0; shift 2
    log "start $name: $*"
    timeout --signal=TERM --kill-after=30 "$budget" "$@" >"$EVIDENCE/$name.log" 2>&1 || rc=$?
    log "end $name rc=$rc"
    return "$rc"
}
suite_module() {
    [[ -v MODULE[$1] ]] || { echo "unknown suite: $1" >&2; exit 2; }
    printf '%s\n' "${MODULE[$1]}"
}
apk() {
    local module=$1 kind=$2 apks
    if [[ $kind == debug ]]; then apks=("$module"/build/outputs/apk/debug/*.apk)
    else apks=("$module"/build/outputs/apk/androidTest/debug/*.apk); fi
    [[ ${#apks[@]} == 1 && -f ${apks[0]} ]] || { echo "expected exactly one $kind APK in $module" >&2; return 1; }
    printf '%s\n' "${apks[0]}"
}

configure_avd() {
    local config=$1/config.ini
    [[ -f $config ]] || { echo "missing $config" >&2; exit 2; }
    python3 - "$config" "$CI_DIR/avd.conf" <<'PY'
import pathlib, sys
config, wanted = map(pathlib.Path, sys.argv[1:])
values = dict(l.split('=', 1) for l in wanted.read_text().splitlines() if l and not l.startswith('#'))
kept = [l for l in config.read_text().splitlines() if l.split('=', 1)[0].strip() not in values]
config.write_text('\n'.join(kept + [f'{k}={v}' for k, v in values.items()]) + '\n')
print(config.read_text(), end='')
PY
}

device() {
    "$ADB" wait-for-device
    local booted=0
    for ((i=0; i<150; i++)); do
        if [[ $(timeout 10 "$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r') == 1 ]]; then booted=1; break; fi
        sleep 2
    done
    [[ $booted == 1 ]] || { log 'Android boot did not complete'; return 1; }
    # After boot completion the emulator asynchronously FIFO-runs its own ADB bootstrap
    # (screen timeout, logcat size, accelerometer_rotation=1), then broadcasts the
    # MultiDisplay start. Once Android has delivered that successor, the writer has
    # retired and cannot overwrite the restored rotation. This boot's history only.
    step emulator-bootstrap 120 python3 - "$ADB" <<'PY'
import subprocess, sys, time
deadline = time.monotonic() + 110
while time.monotonic() < deadline:
    history = subprocess.run([sys.argv[1], 'shell', 'dumpsys activity broadcasts history'],
                             capture_output=True, text=True, timeout=30).stdout
    lines = [l.strip() for l in history.splitlines() if 'com.android.emulator.multidisplay.START' in l]
    if lines:
        print(lines[0], flush=True)
        print('Emulator post-boot settings writer retired', flush=True)
        break
    time.sleep(.2)
else:
    raise RuntimeError('Emulator post-boot ADB bootstrap did not complete; refusing to race device setup')
PY
    step wake 30 "$ADB" shell input keyevent KEYCODE_WAKEUP
    step unlock 30 "$ADB" shell wm dismiss-keyguard
    # boot_completed can precede user unlock and UsageStats initialization.
    local usage_ready=0
    for ((i=0; i<90; i++)); do
        timeout 15 "$ADB" shell dumpsys usagestats >"$EVIDENCE/usage.log"
        if grep -q 'In-memory daily stats' "$EVIDENCE/usage.log"; then usage_ready=1; break; fi
        sleep 1
    done
    [[ $usage_ready == 1 ]] || { log 'UsageStats did not initialize after user unlock'; return 1; }
    # First-boot SDK setup owns HOME until it is retired (a no-op on a provisioned device).
    local home
    home=$(sed -n 's/^home=//p' "$CI_DIR/device.conf")
    if [[ -n $(timeout 15 "$ADB" shell pm list packages -e com.android.sdksetup | tr -d '\r') ]]; then
        step setup-package-off 30 "$ADB" shell pm disable-user --user 0 com.android.sdksetup
    fi
    if [[ $(resolved_home) != "$home" ]]; then
        step home 30 "$ADB" shell cmd package set-home-activity --user 0 "$home"
    fi
    step settings-before 30 "$ADB" shell 'su 0 cat /data/system/users/0/settings_system.xml' || true
    step settings-restore 90 python3 - "$ADB" "$CI_DIR/device.conf" <<'PY'
import pathlib, re, shlex, subprocess, sys, time
adb, record = sys.argv[1:]
expected = {}
for line in pathlib.Path(record).read_text().splitlines():
    match = re.fullmatch(r'(secure|global|system)\.([^=]+)=(.*)', line)
    if match is None:
        continue
    namespace, key, value = match.groups()
    expected[namespace, key] = value
    command = ['settings', 'delete', namespace, key] if value == 'null' else ['settings', 'put', namespace, key, value]
    subprocess.run([adb, 'shell', shlex.join(command)], check=True, timeout=15)
    print(f'Restored {namespace}.{key}={value}', flush=True)
# The emulator's bootstrap writer has retired. Restore Android's rotation owner
# next, then the exact settings, then require them to hold.
rotation = expected['system', 'user_rotation']
accelerometer = expected['system', 'accelerometer_rotation']
def restore_rotation():
    if accelerometer in ('0', '1'):
        command = ['cmd', 'window', 'user-rotation', 'lock', rotation] if accelerometer == '0' else ['cmd', 'window', 'user-rotation', 'free']
        subprocess.run([adb, 'shell', shlex.join(command)], check=True, timeout=15)
        for key in ('user_rotation', 'accelerometer_rotation'):
            value = expected['system', key]
            command = ['settings', 'delete', 'system', key] if value == 'null' else ['settings', 'put', 'system', key, value]
            subprocess.run([adb, 'shell', shlex.join(command)], check=True, timeout=15)
        print(f'Restored WindowManager rotation={rotation}, accelerometer={accelerometer}', flush=True)
restore_rotation()
command = '; '.join('settings get ' + shlex.join(key) for key in expected)
deadline = time.monotonic() + 60
stable = 0
while time.monotonic() < deadline:
    result = subprocess.run([adb, 'shell', command], check=True, capture_output=True, text=True, timeout=15)
    actual = result.stdout.replace('\r', '').splitlines()
    matches = len(actual) == len(expected) and actual == list(expected.values())
    stable = stable + 1 if matches else 0
    print(f'Settings settle sample: consecutive_matches={stable}; values={actual}', flush=True)
    if stable == 3:
        print('All device settings match across three samples over at least two seconds', flush=True)
        break
    if len(actual) == len(expected):
        observed = dict(zip(expected, actual))
        if any(observed['system', key] != expected['system', key] for key in ('user_rotation', 'accelerometer_rotation')):
            # Reconcile owner/observer drift through the same owner, then restart the window.
            print('Rotation drifted; restoring the recorded owner state', flush=True)
            restore_rotation()
    time.sleep(1)
else:
    raise RuntimeError('Device settings did not settle to device.conf')
PY
    local build
    build=$(timeout 15 "$ADB" shell getprop ro.build.fingerprint | tr -d '\r')
    [[ $build == "$(sed -n 's/^build=//p' "$CI_DIR/device.conf")" ]] || { log "unexpected system image: $build"; return 1; }
    [[ $(resolved_home) == "$home" ]] || { log "HOME is not $home"; return 1; }
    log "device matches device.conf (build $build)"
}
resolved_home() {
    timeout 15 "$ADB" shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tr -d '\r' | tail -n1
}

install() {
    local suite=$1 requirement provider kind module
    suite_module "$suite" >/dev/null
    for requirement in ${REQUIRED[$suite]}; do
        provider=${requirement%:*}; kind=${requirement#*:}; module=${MODULE[$provider]}
        [[ -f $module/build.gradle.kts ]] || { [[ $provider == notes ]] && continue; log "missing dependency: $provider"; return 1; }
        step "install-$provider-$kind" 120 "$ADB" install -r "$(apk "$module" "$kind")"
    done
    [[ $suite == kotatsu ]] || return 0
    # Public-picker cases require completed onboarding. Let the build initialize its
    # own fresh-install defaults, then finish through the public UI.
    step install-kotatsu-debug 120 "$ADB" install -r "$(apk app debug)"
    step onboarding 180 python3 - "$ADB" "$EVIDENCE" <<'PY'
import pathlib, re, subprocess, sys, time, xml.etree.ElementTree as ET
adb, evidence = sys.argv[1], pathlib.Path(sys.argv[2])
package = 'com.alexindigo.kotatsu.debug'
def run(*args):
    result = subprocess.run([adb, *args], stdin=subprocess.DEVNULL,
                            capture_output=True, text=True, timeout=30, check=True)
    return result.stdout
def preferences():
    return run('shell', 'run-as', package, 'cat', 'shared_prefs/katapult_prefs.xml')
# Reinstall preserves data; reset before first launch so reruns use fresh defaults too.
print(run('shell', 'pm', 'clear', package), flush=True)
print(run('shell', 'am', 'start', '-W', '-n', package + '/com.gezimos.katapult.MainActivity'), flush=True)
deadline = time.monotonic() + 90
clicked = False
while time.monotonic() < deadline:
    run('shell', 'uiautomator', 'dump', '--compressed', '/sdcard/gate-onboarding.xml')
    xml = run('shell', 'cat', '/sdcard/gate-onboarding.xml')
    (evidence / 'onboarding-ui.xml').write_text(xml)
    root = ET.fromstring(xml)
    finish = next((n for n in root.iter('node')
                   if n.get('text') == 'Finish' or n.get('content-desc') == 'Finish'), None)
    if finish is not None:
        bounds = list(map(int, re.findall(r'\d+', finish.get('bounds', ''))))
        if len(bounds) == 4 and bounds[2] > bounds[0] and bounds[3] > bounds[1]:
            (evidence / 'onboarding-fresh-defaults.xml').write_text(preferences())
            run('shell', 'input', 'tap', str((bounds[0] + bounds[2]) // 2), str((bounds[1] + bounds[3]) // 2))
            clicked = True
            break
    # Scroll the known 480x800 onboarding viewport; never guess where Finish is.
    run('shell', 'input', 'swipe', '240', '680', '240', '200', '400')
if not clicked:
    raise RuntimeError('Fresh onboarding Finish action did not become visible; see onboarding-ui.xml')
while time.monotonic() < deadline:
    raw = preferences()
    prefs = ET.fromstring(raw)
    (evidence / 'onboarding-readback.xml').write_text(raw)
    complete = any(n.get('name') == 'onboarding_complete' and n.get('value') == 'true' for n in prefs)
    if complete:
        assert not any(n.get('name') == 'lockscreen_widgets' for n in prefs), 'Bootstrap created widget state'
        print(raw, flush=True)
        break
    time.sleep(.25)
else:
    raise RuntimeError('Public Finish action did not persist onboarding_complete=true')
run('shell', 'am', 'force-stop', package)
print('Public first launch/Finish completed; production fresh-install defaults preserved', flush=True)
PY
}

case ${1:-} in
    configure-avd) [[ $# == 2 ]] || exit 2; configure_avd "$2";;
    device) [[ $# == 2 ]] || exit 2; EVIDENCE=$2; mkdir -p "$EVIDENCE"; device;;
    requirements) [[ $# == 2 ]] || exit 2; suite_module "$2" >/dev/null; printf '%s\n' "${REQUIRED[$2]}";;
    module) [[ $# == 2 ]] || exit 2; suite_module "$2";;
    apk) [[ $# == 3 && $3 =~ ^(debug|androidTest)$ ]] || exit 2; apk "$2" "$3";;
    install) [[ $# == 3 ]] || exit 2; EVIDENCE=$3; mkdir -p "$EVIDENCE"; install "$2";;
    *) sed -n '2,7p' "$0" >&2; exit 2;;
esac
