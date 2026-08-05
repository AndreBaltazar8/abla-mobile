#!/usr/bin/env bash
set -euo pipefail

project_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
compiler=${ABLAC:-$project_root/../ablac/build/ablac}

"$compiler" build \
    "$project_root/examples/multiscreen/android_build.ab" \
    -o "$project_root/build/multiscreen-android-driver" \
    --fast --no-cache

nix-shell "$project_root/../ablac/examples/android/shell.nix" --run \
    "android-gradle -p '$project_root/examples/multiscreen' :android-app:assembleDebug -PablaMobileConfiguration=debug --console=plain"

app_source="$project_root/examples/multiscreen/android-app/src"
if find "$app_source" -type f -name '*.kt' -print -quit | grep -q .; then
    echo "multiscreen application unexpectedly contains Kotlin source" >&2
    exit 1
fi

generated="$project_root/examples/multiscreen/android-app/build/abla-mobile/debug"
grep -Fq 'applicationId=org.abla.mobile.multiscreen' \
    "$generated/application.properties"
grep -Fq 'android:scheme="abla-journeys"' "$generated/AndroidManifest.xml"
grep -Fq '<string name="abla_mobile_app_name">Abla Journeys</string>' \
    "$generated/res/values/abla_mobile.xml"

apk="$project_root/examples/multiscreen/android-app/build/outputs/apk/debug/android-app-debug.apk"
test -f "$apk"
echo "Multi-screen Android example built without application Kotlin: $apk"
