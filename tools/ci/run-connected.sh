#!/usr/bin/env bash
# Runs one connected suite from prebuilt debug/androidTest APKs on a booted API 31 emulator.
# Usage, from the repository root: [SHARD=<index>/<count>] [CLASS=<a,b>] tools/ci/run-connected.sh <kotatsu|weather|notes|mudita> <results-dir>
# Results: <results-dir>/TEST-*.xml (JUnit), instrument.txt, prep logs and failure-dumps/.
set -Eeuo pipefail
[[ $# == 2 ]] || { sed -n '3p' "$0" >&2; exit 2; }
CI_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ADB=${ADB:-adb}
export ANDROID_SERIAL=${ANDROID_SERIAL:-emulator-5554}
suite=$1 results=$2
declare -A TARGET=(
    [kotatsu]=com.alexindigo.kotatsu.debug
    [weather]=com.alexindigo.kotatsu.weather.debug
    [notes]=com.alexindigo.kotatsu.notes.debug
    [mudita]=com.gezimos.katapult.weather.mudita.test
)
declare -A TEST_PACKAGE=(
    [kotatsu]=com.alexindigo.kotatsu.debug.test
    [weather]=com.alexindigo.kotatsu.weather.debug.test
    [notes]=com.alexindigo.kotatsu.notes.debug.test
    [mudita]=com.gezimos.katapult.weather.mudita.test
)
[[ -v TARGET[$suite] ]] || { echo "unknown suite: $suite" >&2; exit 2; }
prep() { bash "$CI_DIR/prepare-emulator.sh" "$@"; }
module=$(prep module "$suite")
if [[ ! -f $module/build.gradle.kts ]]; then
    echo "$suite: module absent" >&2
    [[ $suite == notes ]] && exit 0
    exit 1
fi
mkdir -p "$results"
args=()
name=$suite
if [[ -n ${SHARD:-} ]]; then
    [[ $SHARD =~ ^([0-9]+)/([1-9][0-9]*)$ && ${BASH_REMATCH[1]} -lt ${BASH_REMATCH[2]} ]] || { echo "bad SHARD=$SHARD" >&2; exit 2; }
    args+=(-e numShards "${BASH_REMATCH[2]}" -e shardIndex "${BASH_REMATCH[1]}")
    name=$suite-shard${BASH_REMATCH[1]}of${BASH_REMATCH[2]}
fi
[[ -z ${CLASS:-} ]] || args+=(-e class "$CLASS")
[[ $suite != kotatsu ]] || args+=(-e expectedApplicationId com.alexindigo.kotatsu.debug)
started=$SECONDS
prep install "$suite" "$results/prep"
# Kotatsu's own APK was installed and onboarded by prepare; libraries have only a test APK.
if [[ $suite == weather || $suite == notes ]]; then timeout 120 "$ADB" install -r "$(prep apk "$module" debug)"; fi
timeout 120 "$ADB" install -r -t "$(prep apk "$module" androidTest)"
runner=$(timeout 30 "$ADB" shell pm list instrumentation | tr -d '\r' |
    sed -n "s|^instrumentation:${TEST_PACKAGE[$suite]}/\([^ ]*\) (target=${TARGET[$suite]})$|\1|p")
[[ -n $runner ]] || { echo "no instrumentation for ${TEST_PACKAGE[$suite]}" >&2; exit 1; }
rc=0
echo "am instrument ${args[*]} ${TEST_PACKAGE[$suite]}/$runner"
timeout --signal=TERM --kill-after=60 5400 "$ADB" shell am instrument -r -w "${args[@]}" \
    "${TEST_PACKAGE[$suite]}/$runner" >"$results/instrument.txt" 2>&1 || rc=$?
dumps=/sdcard/Android/data/${TARGET[$suite]}/files/failure-dumps
if [[ -n $(timeout 30 "$ADB" shell ls -d "$dumps" 2>/dev/null | tr -d '\r') ]]; then
    timeout 300 "$ADB" pull "$dumps" "$results/" || rc=1
fi
python3 "$CI_DIR/junit.py" convert "$results/instrument.txt" "$results/TEST-$name.xml" --suite "$name" || rc=1
echo "$name seconds=$((SECONDS - started)) rc=$rc"
exit "$rc"
