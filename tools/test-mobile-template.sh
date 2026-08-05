#!/usr/bin/env bash
set -euo pipefail

project_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
compiler=${ABLAC:-$project_root/../ablac/build/ablac}
native_compiler=${CC:-cc}
output_directory="$project_root/build/mobile-template"

mkdir -p "$output_directory"

"$compiler" build \
    "$project_root/tests/native/mobile_template.ab" \
    --emit shared-library \
    -o "$output_directory/libabla_mobile_template_test.so" \
    --fast --no-cache

"$native_compiler" \
    -Wall -Wextra -Werror \
    "$project_root/tests/native/mobile_template.c" \
    "$project_root/runtime/native/abla_mobile_app_host.c" \
    -L"$output_directory" \
    -Wl,-rpath,"$output_directory" \
    -Wl,--export-dynamic \
    -labla_mobile_template_test \
    -o "$output_directory/mobile-template-test"

"$output_directory/mobile-template-test"

if "$compiler" build \
    "$project_root/tests/native/invalid_mobile_template.ab" \
    -o "$output_directory/invalid-mobile-template" \
    --fast --no-cache \
    >"$output_directory/invalid-mobile-template.stdout" \
    2>"$output_directory/invalid-mobile-template.stderr"; then
    echo "invalid mobile template unexpectedly compiled" >&2
    exit 1
fi
grep -q 'error\[E_SUBPARSER_INVOCATION\]' \
    "$output_directory/invalid-mobile-template.stderr"

echo "mobile template diagnostics rejected a missing action"
