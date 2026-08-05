#!/usr/bin/env bash
set -euo pipefail

project_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
compiler=${ABLAC:-$project_root/../ablac/build/ablac}

"$compiler" build \
    "$project_root/examples/showcase/android_build.ab" \
    -o "$project_root/build/showcase-android-driver" \
    --fast --no-cache

nix-shell "$project_root/../ablac/examples/android/shell.nix" --run \
    "android-gradle -p '$project_root' :examples:showcase:android-app:assembleDebug -PablaMobileConfiguration=debug --console=plain"

generated="$project_root/examples/showcase/android-app/build/abla-mobile/debug"
grep -Fq 'applicationId=org.abla.mobile.showcase' \
    "$generated/application.properties"
grep -Fq 'android.permission.INTERNET' "$generated/AndroidManifest.xml"
grep -Fq 'android:host="mobile.abla.dev"' "$generated/AndroidManifest.xml"
grep -Fq 'android:pathPrefix="/showcase"' "$generated/AndroidManifest.xml"
grep -Fq 'android:windowSplashScreenBackground' \
    "$generated/res/values-v31/abla_mobile.xml"

app_source="$project_root/examples/showcase/android-app/src"
if find "$app_source" -type f -name '*.kt' -print -quit | grep -q .; then
    echo "showcase application unexpectedly contains Kotlin source" >&2
    exit 1
fi

apk="$project_root/examples/showcase/android-app/build/outputs/apk/debug/android-app-debug.apk"
test -f "$apk"

app_library=$(find \
    "$project_root/examples/showcase/android-app/build/intermediates/merged_native_libs" \
    -path '*arm64-v8a/libabla_app.so' -print -quit)
test -n "$app_library"
actual_exports=$(readelf -Ws "$app_library" | awk \
    '$7 != "UND" && $5 == "GLOBAL" && $8 ~ /^abla_/ { sub(/@@.*/, "", $8); print $8 }' | \
    sort -u)
expected_exports=$(printf '%s\n' \
    abla_mobile_failure_byte \
    abla_mobile_failure_size \
    abla_mobile_platform_attach \
    abla_mobile_platform_effect_next_byte \
    abla_mobile_platform_effect_next_kind \
    abla_mobile_platform_effect_next_size \
    abla_mobile_platform_effect_pop \
    abla_mobile_platform_request_body_byte \
    abla_mobile_platform_request_body_size \
    abla_mobile_platform_request_method_byte \
    abla_mobile_platform_request_method_size \
    abla_mobile_platform_request_next_id \
    abla_mobile_platform_request_pop \
    abla_mobile_platform_request_url_byte \
    abla_mobile_platform_request_url_size \
    abla_mobile_run \
    abla_mobile_run_checked | sort)
if [[ "$actual_exports" != "$expected_exports" ]]; then
    echo "unexpected public Abla application ABI:" >&2
    echo "$actual_exports" >&2
    exit 1
fi

echo "Android showcase built without application Kotlin: $apk"
echo "Public native ABI contains only the fixed mobile entry/transport/failure surface"
