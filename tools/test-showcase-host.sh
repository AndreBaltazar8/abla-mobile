#!/usr/bin/env bash
set -euo pipefail

project_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
compiler=${ABLAC:-$project_root/../ablac/build/ablac}
native_compiler=${CC:-cc}
output_directory="$project_root/build/showcase-host"

mkdir -p "$output_directory"

"$compiler" build \
    "$project_root/examples/showcase/mobile/app.ab" \
    --emit shared-library \
    -o "$output_directory/libabla_mobile_showcase.so" \
    --fast --no-cache

"$native_compiler" \
    "$project_root/tests/native/showcase.c" \
    -L"$output_directory" \
    -Wl,-rpath,"$output_directory" \
    -labla_mobile_showcase \
    -o "$output_directory/showcase-test"

"$output_directory/showcase-test"
