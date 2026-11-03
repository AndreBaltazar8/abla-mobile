#!/usr/bin/env bash
set -euo pipefail

project_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
source "$project_root/tools/native-build-roots.sh"
compiler=${ABLAC:-$ABLA_COMPILER_SOURCE_ROOT/build/ablac}
example_directory="$project_root/examples/fullstack"

"$compiler" build "$example_directory/android_build.ab" \
    -o "$project_root/build/fullstack-android-driver" \
    --fast --no-cache

nix-shell "${ABLA_ANDROID_SHELL:-$ABLA_COMPILER_SOURCE_ROOT/examples/android/shell.nix}" --run \
    "android-gradle -p '$example_directory' :android-app:assembleDebug -PablaMobileConfiguration=debug -PablaCompilerRoot='$ABLA_COMPILER_ROOT' --console=plain"

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
