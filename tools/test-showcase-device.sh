#!/usr/bin/env bash
set -euo pipefail

project_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
serial=${ABLA_ADB_SERIAL:-127.0.0.1:47102}
package=org.abla.mobile.showcase
activity=org.abla.mobile.host.AblaMobileActivity
apk=${ABLA_SHOWCASE_APK:-$project_root/examples/showcase/android-app/build/outputs/apk/debug/android-app-debug.apk}
stress_taps=${ABLA_STRESS_TAPS:-250}
dump_file=$(mktemp)
previous_timeout=""
previous_autofill=""

restore_device() {
    rm -f "$dump_file"
    if [[ -n "$previous_timeout" && "$previous_timeout" != "null" ]]; then
        adb -s "$serial" shell settings put system screen_off_timeout \
            "$previous_timeout" >/dev/null 2>&1 || true
    fi
    if [[ -n "$previous_autofill" && "$previous_autofill" != "null" ]]; then
        adb -s "$serial" shell settings put secure autofill_service \
            "$previous_autofill" >/dev/null 2>&1 || true
    elif [[ "$previous_autofill" == "null" ]]; then
        adb -s "$serial" shell settings delete secure autofill_service \
            >/dev/null 2>&1 || true
    fi
}
trap restore_device EXIT

adb -s "$serial" get-state | grep -qx device
test -f "$apk"
previous_timeout=$(adb -s "$serial" shell settings get system screen_off_timeout | tr -d '\r')
previous_autofill=$(adb -s "$serial" shell settings get secure autofill_service | tr -d '\r')
adb -s "$serial" shell settings put system screen_off_timeout 2147483647
adb -s "$serial" shell settings delete secure autofill_service
adb -s "$serial" shell input keyevent KEYCODE_WAKEUP
adb -s "$serial" shell wm dismiss-keyguard
adb -s "$serial" shell cmd statusbar collapse

dump_ui() {
    adb -s "$serial" shell uiautomator dump /sdcard/abla-mobile-test.xml \
        >/dev/null
    adb -s "$serial" shell cat /sdcard/abla-mobile-test.xml > "$dump_file"
}

has_text() {
    tr '<' '\n<' < "$dump_file" | grep -Fq "text=\"$1\""
}

wait_for_text() {
    local expected=$1
    local attempt
    for attempt in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do
        dump_ui
        if has_text "$expected"; then return 0; fi
        sleep 0.25
    done
    echo "UI did not contain expected text: $expected" >&2
    adb -s "$serial" shell dumpsys activity activities | \
        grep -m1 'topResumedActivity' >&2 || true
    return 1
}

wait_for_pattern() {
    local pattern=$1
    local attempt
    for attempt in 1 2 3 4 5 6 7 8 9 10; do
        dump_ui
        if tr '<' '\n<' < "$dump_file" | grep -Eq "$pattern"; then return 0; fi
        sleep 0.25
    done
    echo "UI did not match expected pattern: $pattern" >&2
    return 1
}

text_center() {
    local label=$1
    dump_ui
    local node bounds left top right bottom
    node=$(tr '<' '\n<' < "$dump_file" | grep -F "text=\"$label\"" | head -n 1)
    bounds=$(sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/p' <<<"$node")
    if [[ -z "$bounds" ]]; then
        echo "could not locate tappable text: $label" >&2
        return 1
    fi
    read -r left top right bottom <<<"$bounds"
    echo "$(((left + right) / 2)) $(((top + bottom) / 2))"
}

tap_text() {
    local x y
    read -r x y < <(text_center "$1")
    adb -s "$serial" shell input tap "$x" "$y"
}

scroll_down_until_text() {
    local expected=$1
    local attempt
    for attempt in 1 2 3 4 5 6; do
        dump_ui
        if has_text "$expected"; then return 0; fi
        adb -s "$serial" shell input swipe 540 1900 540 900 250
    done
    echo "could not scroll to text: $expected" >&2
    return 1
}

scroll_to_top() {
    local attempt
    for attempt in 1 2 3 4 5 6; do
        adb -s "$serial" shell cmd statusbar collapse
        dump_ui
        if has_text "Abla Mobile Showcase"; then return 0; fi
        adb -s "$serial" shell input swipe 540 1100 540 1900 200
    done
    echo "could not scroll back to the application header" >&2
    return 1
}

tap_slider_at_eighty_percent() {
    dump_ui
    local node bounds left top right bottom x y
    node=$(tr '<' '\n<' < "$dump_file" | \
        grep -F 'class="android.widget.SeekBar"' | head -n 1)
    bounds=$(sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/p' <<<"$node")
    test -n "$bounds"
    read -r left top right bottom <<<"$bounds"
    x=$((left + (right - left) * 4 / 5))
    y=$(((top + bottom) / 2))
    adb -s "$serial" shell input tap "$x" "$y"
}

adb -s "$serial" logcat -c
adb -s "$serial" install -r "$apk" >/dev/null
adb -s "$serial" shell am force-stop "$package"
adb -s "$serial" shell am start -W -n "$package/$activity" >/dev/null

wait_for_text "Abla Mobile Showcase"
wait_for_text "Running entirely from Abla"
tap_text "+1"
wait_for_text "1"
wait_for_text "Counter incremented in Abla"

if [[ "$stress_taps" -gt 0 ]]; then
    read -r increment_x increment_y < <(text_center "+1")
    adb -s "$serial" shell \
        "i=0; while [ \$i -lt $stress_taps ]; do input tap $increment_x $increment_y; i=\$((i+1)); done"
    wait_for_text "$((stress_taps + 1))"
fi

tap_text "Abla"
adb -s "$serial" shell input text Codex
wait_for_text "Hello, AblaCodex!"
adb -s "$serial" shell input keyevent KEYCODE_BACK
tap_text "Dark theme"
wait_for_text "Dark theme enabled"
tap_text "Enable notifications"
wait_for_text "Notifications disabled"

scroll_down_until_text "Volume: 65%"
adb -s "$serial" shell input swipe 540 1900 540 1300 200
tap_slider_at_eighty_percent
wait_for_pattern 'text="Volume: (7[0-9]|8[0-9])%"'
scroll_down_until_text "Advance"
tap_text "Advance"
scroll_to_top
wait_for_text "Progress advanced"

tap_text "Lists"
wait_for_text "4 stable keyed items"
tap_text "Add item"
wait_for_text "5 stable keyed items"

scroll_to_top
tap_text "About"
wait_for_text "Everything important is Abla"
tap_text "Toast"
wait_for_text "Abla requested an Android toast"

adb -s "$serial" shell pidof "$package" >/dev/null
log=$(adb -s "$serial" logcat -d -v brief -s AblaMobile)
grep -Fq "Abla effect 1 (15 bytes)" <<<"$log"
if [[ "$stress_taps" -gt 0 ]]; then
    grep -Fq "Abla GC freed" <<<"$log"
fi
if grep -Eqi 'panic|memory limit|fatal' <<<"$log"; then
    echo "$log" >&2
    echo "fatal Abla Mobile log found" >&2
    exit 1
fi

if [[ "$stress_taps" -gt 0 ]]; then
    echo "ADB showcase proof passed on $serial: render, $stress_taps stress taps, Abla state/GC, and Android effect"
else
    echo "ADB showcase proof passed on $serial: render, Abla state, and Android effect"
fi
