#!/usr/bin/env bash
set -euo pipefail
framework=$1
archives=$2
evidence=$3
mkdir -p "$evidence"
plutil -convert json -o "$evidence/xcframework.json" "$framework/Info.plist"
node - "$evidence/xcframework.json" > "$evidence/xcframework-slices.tsv" <<'JS'
const fs = require('fs');
const info = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
const libraries = info.AvailableLibraries;
if (!Array.isArray(libraries) || libraries.length !== 2) throw new Error('expected exactly two slices');
const variants = new Set();
for (const library of libraries) {
  const variant = library.SupportedPlatformVariant || 'device';
  if (library.SupportedPlatform !== 'ios' || !['device', 'simulator'].includes(variant) ||
      JSON.stringify(library.SupportedArchitectures) !== '["arm64"]' || variants.has(variant)) {
    throw new Error('unexpected slice platform or architectures');
  }
  variants.add(variant);
  const paths = [library.LibraryIdentifier, library.LibraryPath, library.HeadersPath];
  if (paths.some(x => typeof x !== 'string' || !/^[A-Za-z0-9_.-]+$/.test(x) || x === '..')) {
    throw new Error('unexpected slice path');
  }
  console.log([...paths, variant].join('\t'));
}
JS
while IFS=$'\t' read -r identifier library headers variant; do
    target=ios-arm64
    test "$variant" != simulator || target=ios-arm64-simulator
    cmp "$archives/$target/libabla_app.a" "$framework/$identifier/$library"
    for header in abla_ios_application.h module.modulemap; do
        cmp ../abla-mobile/runtime/ios-host/AppHeaders/"$header" "$framework/$identifier/$headers/$header"
    done
    if test "$variant" = simulator; then
        cat > "$evidence/import-app.swift" <<'SWIFT'
import AblaApp
// Typecheck only: both exports must be available, but neither is invoked on
// Swift's main thread. Runtime entry/query belong to the checked C boundary.
let entry = abla_mobile_run
let query = abla_mobile_application_protocol_version
let version = Int(ABLA_IOS_APPLICATION_PROTOCOL_VERSION)
SWIFT
        xcrun --sdk iphonesimulator swiftc -typecheck -target arm64-apple-ios17.0-simulator \
            -sdk "$(xcrun --sdk iphonesimulator --show-sdk-path)" \
            -I "$framework/$identifier/$headers" "$evidence/import-app.swift"
    fi
done < "$evidence/xcframework-slices.tsv"
printf 'xcframework: two exact archives, headers/module maps and Swift imports verified\n'
