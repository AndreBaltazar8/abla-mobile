#!/usr/bin/env bash
set -euo pipefail

project_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
compiler=${ABLAC:-$project_root/../ablac/build/ablac}
example_directory="$project_root/examples/fullstack"

"$compiler" build "$example_directory/android_build.ab" \
    -o "$project_root/build/fullstack-android-driver" \
    --fast --no-cache

nix-shell "$project_root/../ablac/examples/android/shell.nix" --run \
    "android-gradle -p '$example_directory' :android-app:assembleDebug -PablaMobileConfiguration=debug --console=plain"

app_source="$example_directory/android-app/src"
if find "$app_source" -type f -name '*.kt' -print -quit | grep -q .; then
    echo "full-stack application unexpectedly contains Kotlin source" >&2
    exit 1
fi

generated="$example_directory/android-app/build/abla-mobile/debug"
grep -Fq 'android.permission.INTERNET' "$generated/AndroidManifest.xml"
grep -Fq 'android.intent.category.LAUNCHER' "$generated/AndroidManifest.xml"

apk="$example_directory/android-app/build/outputs/apk/debug/android-app-debug.apk"
test -f "$apk"
echo "Full-stack Android example built without application Kotlin: $apk"
