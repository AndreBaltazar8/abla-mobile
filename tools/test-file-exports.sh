#!/usr/bin/env bash
set -euo pipefail
project_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
mkdir -p "$project_root/build/file-exports"
"${CC:-cc}" -std=c11 -D_DEFAULT_SOURCE -Wall -Wextra -Werror \
 "$project_root/tests/native/file_exports.c" "$project_root/runtime/native/abla_mobile_platform_effects.c" \
 -o "$project_root/build/file-exports/test"
"$project_root/build/file-exports/test"
