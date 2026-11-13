#!/usr/bin/env python3
# Copyright 2026, OpenView contributors.
# SPDX-License-Identifier: BSL-1.0
"""Minimal build-only adaptation of pinned upstream; does not alter tracker logic."""
import argparse
from pathlib import Path
import subprocess

REVISION = "4beb1a942f33da9afbfae2d70e2c641cfc2bb675"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    args = parser.parse_args()
    source = args.source.resolve()
    actual = subprocess.check_output(["git", "-C", str(source), "rev-parse", "HEAD"], text=True).strip()
    if actual != REVISION:
        raise ValueError(f"Expected pinned revision {REVISION}, got {actual}")
    cmake = source / "CMakeLists.txt"
    text = cmake.read_text()
    # The current upstream GUI player ignores PC_HEADLESS_ONLY. Omit its target
    # and the separate global-localization executable; retain the core unchanged.
    for line in ["add_subdirectory(xrslam-localization)", "  add_subdirectory(xrslam-pc)"]:
        if text.count(line) != 1:
            raise ValueError(f"Unexpected upstream CMake: {line}")
        text = text.replace(line, "# omitted by OpenView's headless experiment")
    text += '\nadd_executable(openview-xrslam-headless "${OPENVIEW_HEADLESS_SOURCE}")\n'
    text += 'target_link_libraries(openview-xrslam-headless PRIVATE xrslam depends::opencv)\n'
    text += 'target_compile_features(openview-xrslam-headless PRIVATE cxx_std_17)\n'
    cmake.write_text(text)
    # Resolve the upstream release tags to immutable commits, obtained via the
    # official GitHub repositories. Ceres already uses a full commit upstream.
    pins = {
        "eigen": ("3.3.7", "cf794d3b741a6278df169e58461f8529f43bce5d"),
        "spdlog": ("v1.3.1", "a7148b718ea2fabb8387cb90aee9bf448da63e65"),
        "yaml-cpp": ("yaml-cpp-0.7.0", "0579ae3d976091d7d664aa9d2527e0d0cff25763"),
    }
    for name, (tag, revision) in pins.items():
        dependency = source / "cmake/depends" / (name + ".cmake")
        contents = dependency.read_text()
        expected = "GIT_TAG        " + tag
        if contents.count(expected) != 1:
            raise ValueError(f"Unexpected dependency tag for {name}")
        dependency.write_text(contents.replace(expected, "GIT_TAG        " + revision))


if __name__ == "__main__":
    main()
