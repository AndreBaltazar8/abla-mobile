#!/usr/bin/env bash
set -euo pipefail

project_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
compiler=${ABLAC:-$project_root/../ablac/build/ablac}
serial=${ABLA_ADB_SERIAL:-127.0.0.1:47102}
package=org.abla.mobile.panicproof
activity=org.abla.mobile.host.AblaMobileActivity
dump_file=$(mktemp)
previous_timeout=""

restore_device() {
    rm -f "$dump_file"
    adb -s "$serial" shell am force-stop "$package" >/dev/null 2>&1 || true
    adb -s "$serial" uninstall "$package" >/dev/null 2>&1 || true
    adb -s "$serial" shell rm -f /sdcard/abla-mobile-panic.xml \
        >/dev/null 2>&1 || true
    if [[ -n "$previous_timeout" && "$previous_timeout" != "null" ]]; then
        adb -s "$serial" shell settings put system screen_off_timeout \
            "$previous_timeout" >/dev/null 2>&1 || true
    fi
}
trap restore_device EXIT

adb -s "$serial" get-state | grep -qx device
previous_timeout=$(adb -s "$serial" shell settings get system screen_off_timeout | tr -d '\r')
adb -s "$serial" shell settings put system screen_off_timeout 2147483647
adb -s "$serial" shell input keyevent KEYCODE_WAKEUP
adb -s "$serial" shell wm dismiss-keyguard

"$compiler" build \
    "$project_root/tests/android_panic_build.ab" \
    -o "$project_root/build/android-panic-driver" \
    --fast --no-cache

nix-shell "$project_root/../ablac/examples/android/shell.nix" --run \
    "android-gradle -p '$project_root' :examples:showcase:android-app:assembleDebug -PablaMobileConfiguration=panic --console=plain"

apk="$project_root/examples/showcase/android-app/build/outputs/apk/debug/android-app-debug.apk"
test -f "$apk"
adb -s "$serial" logcat -c
adb -s "$serial" install -r "$apk" >/dev/null
adb -s "$serial" shell am force-stop "$package"
adb -s "$serial" shell am start -W -n "$package/$activity" >/dev/null

found=false
for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do
    adb -s "$serial" shell uiautomator dump /sdcard/abla-mobile-panic.xml \
        >/dev/null
    adb -s "$serial" shell cat /sdcard/abla-mobile-panic.xml > "$dump_file"
    if grep -Fq 'text="Abla app stopped"' "$dump_file" &&
        grep -Fq 'text="invalid memory limit"' "$dump_file"; then
        found=true
        break
    fi
    sleep 0.25
done

if [[ "$found" != true ]]; then
    echo "contained panic failure screen was not visible" >&2
    adb -s "$serial" logcat -d -v brief -s AblaMobile >&2
    exit 1
fi

adb -s "$serial" shell pidof "$package" >/dev/null
log=$(adb -s "$serial" logcat -d -v brief -s AblaMobile)
grep -Fq 'Abla panic: invalid memory limit' <<<"$log"
if grep -Fq 'Fatal signal' <<<"$log"; then
    echo "$log" >&2
    echo "contained panic terminated the Android process" >&2
    exit 1
fi

echo "ADB panic containment proof passed on $serial"
