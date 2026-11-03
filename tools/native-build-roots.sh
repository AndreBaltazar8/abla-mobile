#!/usr/bin/env bash
# This repository is the selected mobile SDK, including when it is an archive.
export ABLA_MOBILE_ROOT="$project_root"
mobile_siblings_root=$(dirname "$project_root")
export ABLA_COMPILER_SOURCE_ROOT=${ABLA_COMPILER_SOURCE_ROOT:-$mobile_siblings_root/ablac}
export ABLA_COMPILER_ROOT=${ABLA_COMPILER_ROOT:-$ABLA_COMPILER_SOURCE_ROOT}

python3 "$project_root/tools/check-native-build-roots.py"
