#!/usr/bin/env python3
# Copyright 2026, OpenView contributors. SPDX-License-Identifier: BSL-1.0
"""Run an observable OpenView experience smoke test on an authorized Android device.

Requires both APKs installed and overlay permission granted to the runtime.
Camera permission is deliberately not changed: grant it in the app or with adb
before testing camera mode. Screenshots stay in the chosen output directory.
This tests frame delivery, not pose accuracy, camera calibration or comfort.
"""
import argparse
import json
import re
import subprocess
import time
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True, help="explicit device avoids testing the wrong phone")
    parser.add_argument("--mode", choices=["space", "camera"], default="space")
    parser.add_argument("--seconds", type=int, default=25)
    parser.add_argument("--output", type=Path, default=Path("build/device-smoke"))
    args = parser.parse_args()
    if not 10 <= args.seconds <= 60:
        parser.error("--seconds must be between 10 and 60")
    args.output.mkdir(parents=True, exist_ok=True)
    base = [args.adb, "-s", args.serial]

    def adb(*command, binary=False):
        result = subprocess.run(base + list(command), capture_output=True, timeout=30, check=True)
        return result.stdout if binary else result.stdout.decode("utf-8", errors="replace")

    package = "dev.openview.experience"
    adb("shell", "am", "force-stop", package)
    # Restrict logs by fresh process PID; do not clear the user's system log buffer.
    launched = adb("shell", "am", "start", "-W", "-n", package + "/.MainActivity",
                   "--ez", "camera", str(args.mode == "camera").lower())
    (args.output / "launch.txt").write_text(launched, encoding="utf-8")
    time.sleep(2)
    pid = adb("shell", "pidof", package).strip()
    if not pid.isdigit():
        raise RuntimeError("Experience has no single live process after launch")
    log_lines = {}
    remaining = args.seconds
    while remaining > 0:
        interval = min(5, remaining)
        time.sleep(interval)
        log_lines.update(dict.fromkeys(adb("logcat", "-d", "--pid=" + pid, "-v", "threadtime").splitlines()))
        remaining -= interval
    logs = "\n".join(log_lines)
    (args.output / "logcat.txt").write_text(logs, encoding="utf-8")
    (args.output / "screen.png").write_bytes(adb("exec-out", "screencap", "-p", binary=True))
    samples = [(int(f), int(c), m) for f, c, m in
               re.findall(r"frames=(\d+) cameraFrames=(\d+) mode=(space|camera)", logs)]
    matching = [s for s in samples if s[2] == args.mode]
    sustained = len(matching) >= 5 and matching[-1][0] - matching[0][0] >= 100
    camera_live = args.mode != "camera" or (
        len(matching) >= 5 and matching[-1][1] - matching[0][1] >= 30)
    errors = [line for line in logs.splitlines()
              if "FATAL EXCEPTION" in line or "Fatal signal" in line or re.search(r" E OpenViewXR\s*:", line)]
    result = {
        "mode": args.mode, "seconds": args.seconds, "pid": pid,
        "session_ready": "session READY" in logs, "sustained_rendering": sustained,
        "live_camera": camera_live if args.mode == "camera" else None,
        "first_sample": matching[0] if matching else None,
        "last_sample": matching[-1] if matching else None,
        "errors": errors, "screenshot": "screen.png",
        "limits": "Does not establish visual correctness, pose accuracy, 6DoF, calibration or comfort",
    }
    result["passed"] = result["session_ready"] and sustained and camera_live and not errors
    (args.output / "result.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps(result, indent=2))
    raise SystemExit(0 if result["passed"] else 1)


if __name__ == "__main__":
    main()
