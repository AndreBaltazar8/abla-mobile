#!/usr/bin/env bash
set -euo pipefail

project_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
source "$project_root/tools/native-build-roots.sh"
compiler=${ABLAC:-$ABLA_COMPILER_SOURCE_ROOT/build/ablac}
output="$project_root/build/android-config"
generated="$project_root/build/android-config-module/build/abla-mobile/preview_1"

"$compiler" build "$project_root/tests/native_build_roots.ab" -o "$output/native-roots-test" --fast
(cd "$project_root" && "$output/native-roots-test")

"$compiler" build \
    "$project_root/tests/android_build_config.ab" \
    -o "$output/configuration-test" \
    --fast --no-cache
"$output/configuration-test"

grep -Fq 'versionCode=42' "$generated/application.properties"
grep -Fq 'applicationObjectRoot=obj' "$generated/application.properties"
grep -Fq 'versionName=2.5\:preview\=1\ \#candidate' \
    "$generated/application.properties"
grep -Fq 'A &amp; B &lt;Preview&gt;' \
    "$generated/res/values/abla_mobile.xml"
grep -Fq 'android:icon="@mipmap/app_icon"' "$generated/AndroidManifest.xml"
grep -Fq 'android.permission.CAMERA' "$generated/AndroidManifest.xml"
grep -Fq 'android.intent.action.SEND' "$generated/AndroidManifest.xml"
grep -Fq 'android:mimeType="image/*"' "$generated/AndroidManifest.xml"
grep -Fq 'org.abla.mobile.host.AblaMobileAssistantActivity' \
    "$generated/AndroidManifest.xml"
grep -Fq 'android:theme="@style/AblaMobileAssistantTheme"' \
    "$generated/AndroidManifest.xml"
grep -Fq 'org.abla.mobile.host.AblaVoiceInteractionService' \
    "$generated/AndroidManifest.xml"
grep -Fq 'android.service.voice.VoiceInteractionService' \
    "$generated/AndroidManifest.xml"
grep -Fq 'android:resource="@xml/abla_mobile_voice_interaction"' \
    "$generated/AndroidManifest.xml"
grep -Fq 'android:windowSplashScreenAnimatedIcon' \
    "$generated/res/values-v31/abla_mobile.xml"

echo "typed Android package configuration proof passed"

"$compiler" build \
    "$project_root/tests/android_build_variants.ab" \
    -o "$output/variant-test" \
    --fast --no-cache
"$output/variant-test"

variants="$project_root/build/android-variants-module/build/abla-mobile"
test -f "$variants/debug/obj/arm64-v8a/abla_app.o"
test -f "$variants/debug/obj/x86_64/abla_app.o"
test -f "$variants/release/obj/arm64-v8a/abla_app.o"
test -f "$variants/release/obj/x86_64/abla_app.o"
grep -Fq 'applicationId=org.abla.mobile.variants.debug' \
    "$variants/debug/application.properties"
grep -Fq 'applicationId=org.abla.mobile.variants' \
    "$variants/release/application.properties"
grep -Fq '>Variant Debug</string>' \
    "$variants/debug/res/values/abla_mobile.xml"
grep -Fq '>Variant Release</string>' \
    "$variants/release/res/values/abla_mobile.xml"

echo "multi-configuration Android build proof passed"
