#!/usr/bin/env bash
set -euo pipefail

project_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
compiler=${ABLAC:-$project_root/../ablac/build/ablac}
native_compiler=${CC:-cc}
output_directory="$project_root/build/native-loop"

mkdir -p "$output_directory"

"$compiler" build \
    "$project_root/tests/native/mobile_loop.ab" \
    --emit shared-library \
    -o "$output_directory/libabla_mobile_test.so" \
    --fast --no-cache

"$native_compiler" \
    "$project_root/tests/native/mobile_loop.c" \
    -L"$output_directory" \
    -Wl,-rpath,"$output_directory" \
    -labla_mobile_test \
    -o "$output_directory/mobile-loop-test"

"$output_directory/mobile-loop-test"

grep -q '"symbol":"abla_mobile_run"' \
    "$output_directory/libabla_mobile_test.so.abi.json"
grep -q '"abi":"callback"' \
    "$output_directory/libabla_mobile_test.so.abi.json"

if grep -q 'handle' "$output_directory/libabla_mobile_test.so.abi.json"; then
    echo "unexpected handle ABI in mobile loop proof" >&2
    exit 1
fi

echo "native mobile loop ABI proof passed"
