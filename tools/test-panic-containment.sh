#!/usr/bin/env bash
set -euo pipefail

project_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
native_compiler=${CC:-cc}
output_directory="$project_root/build/panic-containment"

mkdir -p "$output_directory"

"$native_compiler" \
    -std=c11 -Wall -Wextra -Werror \
    "$project_root/tests/native/panic_containment.c" \
    "$project_root/runtime/native/abla_mobile_panic.c" \
    -o "$output_directory/panic-containment-test"

"$output_directory/panic-containment-test"
