#!/usr/bin/env bash
set -euo pipefail

# The shared library leaves the host's entry points undefined for the process
# that loads it to provide, which a Linux .so may do and a macOS dylib link
# rejects.
if [[ $(uname -s) == Darwin ]]; then
    echo "test-showcase-host skipped: its shared library needs Linux-style undefined host symbols" >&2
    exit 0
fi

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
    "$project_root/runtime/native/abla_mobile_app_host.c" \
    "$project_root/runtime/native/abla_mobile_platform_effects.c" \
    "$project_root/runtime/native/abla_mobile_platform_requests.c" \
    -L"$output_directory" \
    -Wl,-rpath,"$output_directory" \
    -Wl,--export-dynamic \
    -labla_mobile_showcase \
    -o "$output_directory/showcase-test"

"$output_directory/showcase-test"
