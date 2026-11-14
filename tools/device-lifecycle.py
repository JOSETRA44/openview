#!/usr/bin/env python3
# Copyright 2026, OpenView contributors. SPDX-License-Identifier: BSL-1.0
"""Regression: OpenView must survive Home/resume/Back without a native thread-exit crash.

Requires installed runtime + experience, overlay permission, unlocked authorized device.
No camera permission changes; starts the space. Records only this process's log.
"""
import argparse
import json
import subprocess
import time
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", type=Path, default=Path("build/device-lifecycle"))
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    base = [args.adb, "-s", args.serial]
    package = "dev.openview.experience"

    def adb(*command, check=True):
        return subprocess.run(base + list(command), capture_output=True, timeout=30,
                              check=check).stdout.decode("utf-8", errors="replace").strip()

    adb("shell", "am", "force-stop", package)
    adb("shell", "am", "start", "-W", "-n", package + "/.MainActivity", "--ez", "camera", "false")
    time.sleep(7)
    pid = adb("shell", "pidof", package, check=False)
    if not pid.isdigit():
        raise RuntimeError("No running experience process")
    initial_log = adb("logcat", "-d", "--pid=" + pid, "-v", "threadtime")
    adb("shell", "input", "keyevent", "KEYCODE_VOLUME_UP")
    time.sleep(1)
    adb("shell", "input", "keyevent", "KEYCODE_HOME")
    time.sleep(4)
    paused_pid = adb("shell", "pidof", package, check=False)
    paused_log = adb("logcat", "-d", "--pid=" + pid, "-v", "threadtime")
    adb("shell", "am", "start", "--activity-reorder-to-front", "-n", package + "/.MainActivity")
    time.sleep(7)
    resumed_pid = adb("shell", "pidof", package, check=False)
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(4)
    final_pid = adb("shell", "pidof", package, check=False)
    final_log = adb("logcat", "-d", "--pid=" + pid, "-v", "threadtime")
    # Busy vendor logs can evict session start before the final read. Preserve
    # each checkpoint, deduplicating complete timestamped lines.
    logs = "\n".join(dict.fromkeys((initial_log + "\n" + paused_log + "\n" + final_log).splitlines()))
    (args.output / "logcat.txt").write_text(logs, encoding="utf-8")
    crashes = [line for line in logs.splitlines() if "Fatal signal" in line or "FATAL EXCEPTION" in line]
    result = {
        "pid": pid, "paused_pid": paused_pid, "resumed_pid": resumed_pid, "final_pid": final_pid,
        "same_process": pid == paused_pid == resumed_pid == final_pid,
        "closed_on_pause": "session closed frames=" in paused_log,
        "session_starts": logs.count("session READY"),
        "session_closes": logs.count("session closed frames="),
        "recentered": "view recentered" in logs, "crashes": crashes,
    }
    result["passed"] = (result["same_process"] and result["closed_on_pause"] and
                        result["session_starts"] >= 2 and result["session_closes"] >= 2 and
                        result["recentered"] and not crashes)
    (args.output / "result.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps(result, indent=2))
    raise SystemExit(0 if result["passed"] else 1)


if __name__ == "__main__":
    main()
