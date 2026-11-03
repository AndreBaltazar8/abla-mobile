#!/usr/bin/env bash
# This repository is the selected mobile SDK, including when it is an archive.
export ABLA_MOBILE_ROOT="$project_root"
mobile_siblings_root=$(dirname "$project_root")
export ABLA_COMPILER_SOURCE_ROOT=${ABLA_COMPILER_SOURCE_ROOT:-$mobile_siblings_root/ablac}
export ABLA_COMPILER_ROOT=${ABLA_COMPILER_ROOT:-$ABLA_COMPILER_SOURCE_ROOT}
mobile_native_compiler=${ABLAC:-$ABLA_COMPILER_SOURCE_ROOT/build/ablac}
mkdir -p "$project_root/build"
"$mobile_native_compiler" build "$project_root/tools/check-native-build-roots.ab" -o "$project_root/build/check-native-build-roots" --fast
"$project_root/build/check-native-build-roots" "$project_root" --self-test
