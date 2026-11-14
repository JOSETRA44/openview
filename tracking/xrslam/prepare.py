#!/usr/bin/env python3
# Copyright 2026, OpenView contributors.
# SPDX-License-Identifier: BSL-1.0
"""Apply the pinned build-only experiment preparation and append the VIT target."""
from pathlib import Path
import subprocess
import sys

if len(sys.argv) != 2:
    raise SystemExit("Usage: prepare.py upstream-source")
root = Path(__file__).resolve().parents[2]
source = Path(sys.argv[1]).resolve()
subprocess.run([sys.executable, str(root / "tools/mono/xrslam_prepare.py"), str(source)], check=True)
with (source / "CMakeLists.txt").open("a") as cmake:
    cmake.write('\nadd_subdirectory("${OPENVIEW_VIT_SOURCE}" openview-vit)\n')
