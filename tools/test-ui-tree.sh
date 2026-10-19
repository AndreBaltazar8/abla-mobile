#!/usr/bin/env bash
set -euo pipefail

# The shared library leaves the host's entry points undefined for the process
# that loads it to provide, which a Linux .so may do and a macOS dylib link
# rejects.
if [[ $(uname -s) == Darwin ]]; then
    echo "test-ui-tree skipped: its shared library needs Linux-style undefined host symbols" >&2
    exit 0
fi

project_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
compiler=${ABLAC:-$project_root/../ablac/build/ablac}
native_compiler=${CC:-cc}
output_directory="$project_root/build/ui-tree"

mkdir -p "$output_directory"

"$compiler" build \
    "$project_root/tests/native/ui_tree.ab" \
    --emit shared-library \
    -o "$output_directory/libabla_mobile_ui_test.so" \
    --fast --no-cache

"$native_compiler" \
    "$project_root/tests/native/ui_tree.c" \
    -L"$output_directory" \
    -Wl,-rpath,"$output_directory" \
    -labla_mobile_ui_test \
    -o "$output_directory/ui-tree-test"

"$output_directory/ui-tree-test"

echo "semantic UI tree encoding proof passed"
