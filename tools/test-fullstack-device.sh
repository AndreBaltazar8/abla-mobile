#!/usr/bin/env bash
set -euo pipefail

project_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
serial=${ADB_SERIAL:-127.0.0.1:47102}
package=org.abla.mobile.fullstack
activity=org.abla.mobile.host.AblaMobileActivity
apk="$project_root/examples/fullstack/android-app/build/outputs/apk/debug/android-app-debug.apk"

test -f "$apk"
adb -s "$serial" get-state | grep -Fqx device

original_timeout=$(adb -s "$serial" shell settings get system screen_off_timeout | tr -d '\r')
original_stay_awake=$(adb -s "$serial" shell settings get global stay_on_while_plugged_in | tr -d '\r')
cleanup() {
    if [[ $original_timeout == null ]]; then
        adb -s "$serial" shell settings delete system screen_off_timeout \
            >/dev/null 2>&1 || true
    else
        adb -s "$serial" shell settings put system screen_off_timeout \
            "$original_timeout" >/dev/null 2>&1 || true
    fi
    if [[ $original_stay_awake == null ]]; then
        adb -s "$serial" shell settings delete global stay_on_while_plugged_in \
            >/dev/null 2>&1 || true
    else
        adb -s "$serial" shell settings put global stay_on_while_plugged_in \
            "$original_stay_awake" >/dev/null 2>&1 || true
    fi
}
trap cleanup EXIT

adb -s "$serial" shell settings put system screen_off_timeout 1800000
adb -s "$serial" shell svc power stayon true
adb -s "$serial" shell input keyevent KEYCODE_WAKEUP
adb -s "$serial" install -r "$apk" >/dev/null
adb -s "$serial" logcat -c
adb -s "$serial" shell am force-stop "$package"
adb -s "$serial" shell am start -W -n "$package/$activity" >/dev/null
sleep 2

adb -s "$serial" shell uiautomator dump /sdcard/abla-fullstack.xml >/dev/null
initial=$(adb -s "$serial" exec-out cat /sdcard/abla-fullstack.xml)
grep -Fq 'text="Call Abla backend"' <<<"$initial"
grep -Fq 'text="Ready"' <<<"$initial"

button=$(grep -o 'text="Call Abla backend"[^>]*bounds="\[[0-9,]*\]\[[0-9,]*\]"' \
    <<<"$initial" | head -1)
bounds=$(sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]"/\1 \2 \3 \4/' \
    <<<"$button")
read -r left top right bottom <<<"$bounds"
adb -s "$serial" shell input tap $(((left + right) / 2)) $(((top + bottom) / 2))
sleep 4

adb -s "$serial" shell uiautomator dump /sdcard/abla-fullstack-after.xml >/dev/null
completed=$(adb -s "$serial" exec-out cat /sdcard/abla-fullstack-after.xml)
grep -Fq 'text="Live RPC completed"' <<<"$completed"
grep -Fq 'Hello, Abla' <<<"$completed"
grep -Fq 'text="Successful calls this session: 1"' <<<"$completed"

if adb -s "$serial" logcat -d -v brief | \
    grep -E 'FATAL EXCEPTION.*org\.abla\.mobile\.fullstack|Fatal signal.*AblaMobile'; then
    echo "fatal full-stack application log detected" >&2
    exit 1
fi

echo "physical Android HTTPS RPC and Abla completion-state test passed"
