#!/usr/bin/env bash
set -euo pipefail

project_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
native_compiler=${CC:-cc}
output_directory="$project_root/build/platform-requests"

mkdir -p "$output_directory"
"$native_compiler" -std=c11 -D_DEFAULT_SOURCE -Wall -Wextra -Werror \
    "$project_root/tests/native/platform_requests.c" \
    "$project_root/runtime/native/abla_mobile_platform_requests.c" \
    -o "$output_directory/platform-requests-test"
"$output_directory/platform-requests-test"
