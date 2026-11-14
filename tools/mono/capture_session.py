#!/usr/bin/env python3
# Copyright 2026, OpenView contributors. SPDX-License-Identifier: BSL-1.0
"""Record/pull a bounded fixed-control session without deleting phone data.

Requires recorder installed and camera permission granted. Use --seconds 6 for
control verification, or 45 for a guided checkerboard capture. Private images
remain local. Calibration is a separate explicit step, never inferred here.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time
from capture_quality import audit


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--seconds", type=int, default=6)
    parser.add_argument("--exposure-ms", type=float, default=5)
    parser.add_argument("--iso", type=int, default=0, help="0 derives fixed gain from initial auto exposure")
    parser.add_argument("--focus-diopters", type=float, default=0)
    parser.add_argument("--output", type=Path, default=Path("build/calibration-captures"))
    args = parser.parse_args()
    if not 3 <= args.seconds <= 90 or not .1 <= args.exposure_ms <= 30 or not 0 <= args.focus_diopters <= 20 or not 0 <= args.iso <= 25600:
        parser.error("seconds 3..90, exposure .1..30 ms, focus 0..20 D and ISO 0..25600 required")
    base = [args.adb, "-s", args.serial]

    def adb(*command, timeout=30):
        return subprocess.run(base + list(command), check=True, capture_output=True, timeout=timeout).stdout.decode("utf-8", errors="replace").strip()

    remote = "/sdcard/Android/data/dev.openview.recorder/files"

    def directories():
        # Only fixed-format recorder folder names may be interpolated into adb shell.
        result = subprocess.run(base + ["shell", "ls", remote], capture_output=True, timeout=30)
        if result.returncode:
            if b"No such file or directory" in result.stderr + result.stdout:
                return set()
            raise RuntimeError("Cannot list recorder captures: " + result.stderr.decode(errors="replace"))
        return {line for line in result.stdout.decode().splitlines() if re.fullmatch(r"\d{8}-\d{6}", line)}

    before = directories()
    df = adb("shell", "df", "-k", "/sdcard").splitlines()[-1].split()
    # Worst-case 30fps raw luma + margin; refuse before consuming the reserved free space.
    required_kib = args.seconds * 9500 + 100000
    if int(df[3]) < required_kib:
        raise RuntimeError(f"Not enough free space: need {required_kib // 1024} MiB including reserve; no data deleted")
    args.output.mkdir(parents=True, exist_ok=True)
    adb("shell", "am", "start", "-W", "-S", "-n", "dev.openview.recorder/.MainActivity",
        "--ei", "seconds", str(args.seconds), "--ef", "exposure_ms", str(args.exposure_ms),
        "--ei", "iso", str(args.iso), "--ef", "focus_diopters", str(args.focus_diopters))
    print("Capture running. Keep the app visible; for calibration hold each board pose still.", flush=True)
    deadline = time.monotonic() + args.seconds + 30
    folder = None
    while time.monotonic() < deadline:
        time.sleep(2)
        new = directories() - before
        if len(new) > 1:
            raise RuntimeError("Multiple new captures; choose the intended folder manually")
        if new:
            folder = next(iter(new))
            done = subprocess.run(base + ["shell", "test", "-f", remote + "/" + folder + "/DONE"], timeout=15)
            if done.returncode == 0:
                break
    else:
        raise RuntimeError("Recorder did not finish; inspect permission/device. Existing captures preserved")
    local = args.output / folder
    if local.exists():
        raise RuntimeError("Local destination exists; refusing to mix captures")
    adb("pull", remote + "/" + folder, str(local), timeout=180)
    report = audit(local)
    report["serial"] = args.serial
    report["source"] = str(local.resolve())
    (local / "quality.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))
    return 0 if report["status"] == "accepted_fixed_controls" else 2


if __name__ == "__main__":
    raise SystemExit(main())
