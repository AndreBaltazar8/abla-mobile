#!/usr/bin/env bash
set -euo pipefail

project_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
compiler=${ABLAC:-$project_root/../ablac/build/ablac}
output_directory="$project_root/build/fullstack-test"
example_directory="$project_root/examples/fullstack"

mkdir -p "$output_directory"

"$compiler" build "$project_root/tests/native/rpc_contract.ab" \
    --emit object -o "$output_directory/rpc-contract.o" \
    --fast --no-cache

"$compiler" build "$project_root/tests/native/nested_rpc_mobile.ab" \
    --emit object -o "$output_directory/nested-rpc-mobile.o" \
    --fast --no-cache

"$compiler" build "$example_directory/server_build.ab" \
    -o "$output_directory/server-build-driver" --fast --no-cache
"$compiler" build "$example_directory/build/server.ab" \
    -o "$output_directory/server" --fast --no-cache

"$output_directory/server" &
server_pid=$!
cleanup() {
    kill "$server_pid" 2>/dev/null || true
    wait "$server_pid" 2>/dev/null || true
}
trap cleanup EXIT

for attempt in 1 2 3 4 5; do
    if curl --fail --silent http://127.0.0.1:8080/health >/dev/null; then
        break
    fi
    sleep 1
done

test "$(curl --fail --silent http://127.0.0.1:8080/health)" = "ok"
greeting=$(curl --fail --silent -X POST --data-binary "Native test" \
    http://127.0.0.1:8080/rpc/greet)
test "$greeting" = \
    "Hello, Native test — this response was computed by Abla on the VPS."

icy validate --path "$example_directory"
printf '%s\n' \
    "full-stack contract generation, local RPC, and icy validation passed"
