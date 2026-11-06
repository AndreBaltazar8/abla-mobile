#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
output=${1:?output directory required}
mkdir -p "$output"
cc -std=c11 -D_POSIX_C_SOURCE=200809L -Wall -Wextra -Werror -pthread \
  -I"$root/Sources/CAblaMobile/include" "$root/Tests/NativeBridge/bridge.c" \
  "$root"/Sources/CAblaMobile/*.c -o "$output/native-bridge"
"$output/native-bridge"
"$output/native-bridge" --bad-frame
