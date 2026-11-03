#!/usr/bin/env python3
"""Reject native build dependencies that escape to unselected sibling checkouts."""
from pathlib import Path
import re
import os
import subprocess
import tempfile
import sys

ROOT = Path(__file__).resolve().parent.parent
SIBLING = re.compile(r"(?:\.\./)+(?:abla-[A-Za-z0-9_-]+|ablac)(?=[/\"'])")


def forbidden(text):
    return bool(SIBLING.search(text))


def native_file(name):
    path = Path(name)
    # PaperMono is parked by the owner; its paths are recorded in the audit.
    if "papermono" in path.parts:
        return False
    return (path.name in {"CMakeLists.txt", "build.gradle.kts", "settings.gradle.kts", "shell-desktop.nix"}
            or path.suffix in {".cmake", ".pbxproj"}
            or (path.parent == Path("tools") and path.name.startswith("build-") and path.suffix == ".sh"))


if "--self-test" in sys.argv:
    assert forbidden('include("../../abla-mobile/runtime/native")')
    assert forbidden('dir("../../../ablac")')
    assert not forbidden('"${ABLA_MOBILE_ROOT}/runtime/native"')
    assert not forbidden('"${ABLA_COMPILER_ROOT}/runtime"')
    assert native_file("examples/demo/android-app/src/main/cpp/CMakeLists.txt")
    assert native_file("tools/build-desktop.sh")
    assert not native_file("src/backends/android.ab")

repository = subprocess.run(["git", "rev-parse", "--show-toplevel"], cwd=ROOT, capture_output=True, text=True)
if repository.returncode == 0 and Path(repository.stdout.strip()).resolve() == ROOT:
    names = subprocess.check_output(["git", "ls-files", "-z"], cwd=ROOT).decode().split("\0")
else:
    # Published SDK archives have no Git metadata; never accidentally scan the
    # consuming application's repository instead of the selected SDK archive.
    names = []
    for directory, dirs, files in os.walk(ROOT):
        dirs[:] = [d for d in dirs if d not in {"build", "vendor", "node_modules", ".abla"} and not d.startswith(".")]
        names.extend(str((Path(directory) / name).relative_to(ROOT)) for name in files)
failures = []
for name in names:
    if name and native_file(name):
        for number, line in enumerate((ROOT / name).read_text().splitlines(), 1):
            if forbidden(line):
                failures.append(f"{name}:{number}: select an SDK root instead of a sibling path")
if failures:
    print("\n".join(failures), file=sys.stderr)
    sys.exit(1)
print("native build roots: selected SDK paths only")

if "--configure-test" in sys.argv:
    with tempfile.TemporaryDirectory(prefix="native-roots-") as folder:
        temporary = Path(folder)
        sdk = temporary / "selected mobile SDK" / "runtime" / "native"
        sdk.mkdir(parents=True)
        sdk.joinpath("CMakeLists.txt").write_text(
            'function(abla_mobile_add_android_application target)\n'
            'file(WRITE "${CMAKE_BINARY_DIR}/selected-sdk" "${CMAKE_CURRENT_FUNCTION_LIST_DIR}")\n'
            'endfunction()\n')
        checked = 0
        for name in names:
            if name and native_file(name) and Path(name).name == "CMakeLists.txt" and "ABLA_MOBILE_ROOT" in (ROOT / name).read_text():
                output = temporary / f"configure-{checked}"
                subprocess.run(["cmake", "-S", str((ROOT / name).parent), "-B", str(output),
                                f"-DABLA_MOBILE_ROOT={sdk.parent.parent}"], check=True, stdout=subprocess.DEVNULL)
                assert output.joinpath("selected-sdk").read_text() == str(sdk)
                checked += 1
        assert checked > 0, "no selected-root native consumer was configured"
        print(f"native configure: {checked} consumers used the selected SDK, including spaces")
