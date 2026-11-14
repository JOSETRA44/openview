#!/usr/bin/env python3
# Copyright 2026, OpenView contributors.
# SPDX-License-Identifier: BSL-1.0
"""Measures how OpenView paces frames on a phone, at each refresh rate it offers.

    measure.py --serial <device> --rates 0 60 90 120 --output <dir>

For every rate it asks the phone for that mode (0: whatever the phone picks),
checks which rate the session really got, runs an OpenXR app on the
runtime, and reads two independent sources: SurfaceFlinger's own record of when
the runtime's frames reached the screen, and the per-second timing summary of
the Android head tracking driver (patch 0001).

The numbers answer whether the display latency model (patch 0002, "two refresh
periods") and the vsync lock (patch 0003) hold beyond the 60 Hz they were
tuned at. The phone's refresh settings are put back afterwards.
"""

import argparse
import json
import re
import subprocess
import sys
import time
from pathlib import Path

# Seconds a session runs before measuring starts.
WARMUP_SECONDS = 6

# Settings that pin the refresh rate. The last one is Transsion's own switch.
REFRESH_SETTINGS = ["min_refresh_rate", "peak_refresh_rate", "tran_refresh_mode"]

DRIVER_STATS = re.compile(
    r"gyro (\d+) Hz, sample age avg (-?\d+) us max (-?\d+) us; poses (\d+), "
    r"horizon min (-?\d+) avg (-?\d+) max (-?\d+) us, limited (\d+)"
)


def percentile(histogram, fraction):
    """Value below which `fraction` of the samples fall, for a [(value, count)] histogram."""
    total = sum(count for _, count in histogram)
    seen = 0
    for value, count in histogram:
        seen += count
        if seen >= total * fraction:
            return value
    return None


def parse_histogram(block, name):
    match = re.search(name + r" histogram is as below:\n([^\n]+)", block)
    if not match:
        return []
    pairs = re.findall(r"(\d+)ms=(\d+)", match.group(1))
    return [(int(ms), int(count)) for ms, count in pairs if int(count) > 0]


def parse_timestats(text, layer="SurfaceView[Monado]"):
    """Frame delivery of the runtime's output layer, from `dumpsys SurfaceFlinger --timestats -dump`.

    A session can leave several layers with that name behind; the one with the
    most frames is the session itself. Returns None when the layer never drew.
    """
    best = None
    for block in text.replace("\r", "").split("layerName = ")[1:]:
        if layer not in block.split("\n", 1)[0]:
            continue
        frames = int(re.search(r"\btotalFrames\s*=\s*(\d+)", block).group(1))
        if best is None or frames > best[0]:
            best = (frames, block)
    if best is None or best[0] == 0:
        return None

    frames, block = best
    intervals = parse_histogram(block, "present2present")
    latency = parse_histogram(block, "post2present")
    return {
        "frames": frames,
        "dropped": int(re.search(r"droppedFrames\s*=\s*(\d+)", block).group(1)),
        "average_fps": float(re.search(r"averageFPS\s*=\s*([\d.]+)", block).group(1)),
        "present_interval_ms": intervals,
        # From the runtime queueing a buffer to that buffer being on screen.
        "queue_to_display_ms": {
            "p5": percentile(latency, 0.05),
            "p50": percentile(latency, 0.50),
            "p95": percentile(latency, 0.95),
        },
    }


def late_frame_fraction(intervals, period_ms):
    """Share of frames shown at least one refresh later than the one before them should allow."""
    total = sum(count for _, count in intervals)
    late = sum(count for ms, count in intervals if ms > 1.5 * period_ms)
    return late / total if total else None


def parse_driver_stats(log):
    """Per-second summaries logged by the Android sensors driver; the first one covers startup."""
    windows = [tuple(map(int, m.groups())) for m in DRIVER_STATS.finditer(log)][1:]
    if not windows:
        return None
    poses = sum(w[3] for w in windows)
    return {
        "seconds": len(windows),
        "gyro_hz": round(sum(w[0] for w in windows) / len(windows)),
        "sample_age_avg_us": round(sum(w[1] for w in windows) / len(windows)),
        "poses_per_second": round(poses / len(windows)),
        # How far ahead of "now" poses were asked for: the latency the runtime believes in.
        "horizon_min_ms": min(w[4] for w in windows) / 1000,
        "horizon_avg_ms": sum(w[5] * w[3] for w in windows) / poses / 1000,
        "horizon_max_ms": max(w[6] for w in windows) / 1000,
        "limited_fraction": sum(w[7] for w in windows) / poses,
    }


def summarize(rate_hz, timestats, driver):
    period_ms = 1000.0 / rate_hz
    result = {"refresh_hz": rate_hz, "period_ms": period_ms, "display": timestats, "driver": driver}
    if timestats:
        median = timestats["queue_to_display_ms"]["p50"]
        result["late_frames"] = late_frame_fraction(timestats["present_interval_ms"], period_ms)
        result["dropped_fraction"] = timestats["dropped"] / timestats["frames"]
        # The quantity patch 0002 models as exactly 2.
        result["latency_in_periods"] = median / period_ms
    return result


class Device:
    def __init__(self, adb, serial):
        self.base = [adb, "-s", serial]

    def shell(self, command, timeout=60):
        done = subprocess.run(self.base + ["shell", command], capture_output=True, timeout=timeout)
        return done.stdout.decode("utf-8", "replace").replace("\r", "")

    def active_refresh_hz(self):
        display = self.shell("dumpsys display")
        modes = dict(re.findall(r"mMode=\{id=(\d+), width=\d+, height=\d+, fps=([\d.]+)", display))
        active = re.search(r"mActiveModeId=(\d+)", display)
        return round(float(modes[active.group(1)])) if active and active.group(1) in modes else None

    def read_refresh_settings(self):
        return {name: self.shell(f"settings get system {name}").strip() for name in REFRESH_SETTINGS}

    def write_refresh_settings(self, values):
        for name, value in values.items():
            if value and value != "null":
                self.shell(f"settings put system {name} {value}")
            else:
                self.shell(f"settings delete system {name}")

    def request_refresh(self, rate_hz):
        self.write_refresh_settings({name: str(rate_hz) for name in REFRESH_SETTINGS})


def run_rate(device, args, requested_hz, out_dir):
    """One session. `requested_hz` of 0 leaves the choice of refresh rate to the phone."""
    out_dir.mkdir(parents=True, exist_ok=True)
    app_package = args.app.split("/")[0]

    device.shell(f"am force-stop {app_package}; am force-stop {args.runtime}; input keyevent KEYCODE_WAKEUP")
    if requested_hz:
        device.request_refresh(requested_hz)
    device.shell(f"am start -n {args.app}")

    # Android settles the refresh rate per window, so it can only be read once
    # the session is on screen. The warm-up also keeps startup out of the numbers.
    time.sleep(WARMUP_SECONDS)
    device.shell("logcat -c; dumpsys SurfaceFlinger --timestats -disable -clear -enable")
    rates_seen = []
    for _ in range(max(1, args.seconds // 5)):
        time.sleep(5)
        rates_seen.append(device.active_refresh_hz())

    timestats_text = device.shell("dumpsys SurfaceFlinger --timestats -dump")
    device.shell("dumpsys SurfaceFlinger --timestats -disable")
    log = device.shell("logcat -d -v threadtime -s monado.android_stats_report_locked:I", timeout=120)
    temperature = re.search(r"temperature: (\d+)", device.shell("dumpsys battery"))
    device.shell(f"am force-stop {app_package}; am force-stop {args.runtime}")

    (out_dir / "timestats.txt").write_text(timestats_text, encoding="utf-8")
    (out_dir / "driver.log").write_text(log, encoding="utf-8")

    observed_hz = rates_seen[0]
    result = {"requested_hz": requested_hz, "refresh_hz": observed_hz}
    if observed_hz:
        result = dict(result, **summarize(observed_hz, parse_timestats(timestats_text), parse_driver_stats(log)))
    result["battery_temperature_c"] = int(temperature.group(1)) / 10 if temperature else None

    # The system may change rate on its own (thermal, idle); such a run mixes two regimes.
    if len(set(rates_seen)) > 1:
        result["error"] = f"panel changed rate during the run: {rates_seen}"
    elif requested_hz and observed_hz != requested_hz:
        result["error"] = f"asked for {requested_hz} Hz, the session ran at {observed_hz} Hz"
    elif result.get("display") is None:
        result["error"] = "the runtime's layer never drew a frame"
    return result


def to_markdown(results):
    lines = [
        "| Asked | Ran at | Frames | Dropped | Late | Queue to display p5/p50/p95 | In periods "
        "| Horizon min/avg/max | Limited | Note |",
        "|---|---|---|---|---|---|---|---|---|---|",
    ]
    for r in results:
        asked = f"{r['requested_hz']} Hz" if r["requested_hz"] else "phone's choice"
        ran_at = f"{r['refresh_hz']} Hz" if r.get("refresh_hz") else "?"
        note = r.get("error", "")
        if not r.get("display"):
            lines.append(f"| {asked} | {ran_at} | | | | | | | | {note} |")
            continue
        latency, driver = r["display"]["queue_to_display_ms"], r["driver"]
        horizon, limited = "no driver stats", ""
        if driver:
            horizon = f"{driver['horizon_min_ms']:.0f}/{driver['horizon_avg_ms']:.0f}/{driver['horizon_max_ms']:.0f} ms"
            limited = f"{driver['limited_fraction'] * 100:.1f} %"
        lines.append(
            f"| {asked} | {ran_at} | {r['display']['frames']} | {r['dropped_fraction'] * 100:.1f} % "
            f"| {r['late_frames'] * 100:.1f} % | {latency['p5']}/{latency['p50']}/{latency['p95']} ms "
            f"| {r['latency_in_periods']:.2f} | {horizon} | {limited} | {note} |"
        )
    return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True, help="explicit device avoids measuring the wrong phone")
    parser.add_argument("--rates", type=int, nargs="+", default=[0, 60, 90, 120],
                        help="refresh rates to ask for; 0 lets the phone choose")
    parser.add_argument("--seconds", type=int, default=45)
    parser.add_argument("--runtime", default="dev.openview.runtime")
    parser.add_argument("--app", default="org.khronos.openxr.hello_xr.vulkan/android.app.NativeActivity")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    device = Device(args.adb, args.serial)
    original = device.read_refresh_settings()
    results = []
    try:
        device.shell("setprop debug.xrt.ANDROID_SENSORS_LOG debug; svc power stayon usb")
        for rate_hz in args.rates:
            result = run_rate(device, args, rate_hz, args.output / (f"{rate_hz}hz" if rate_hz else "auto"))
            # Each run starts from the phone's own settings, so "0" really is its choice.
            device.write_refresh_settings(original)
            results.append(result)
            print(json.dumps({k: v for k, v in result.items() if k != "display"}), flush=True)
    finally:
        device.write_refresh_settings(original)
        device.shell("setprop debug.xrt.ANDROID_SENSORS_LOG ''; svc power stayon false")

    (args.output / "results.json").write_text(json.dumps(results, indent=2), encoding="utf-8")
    (args.output / "results.md").write_text(to_markdown(results), encoding="utf-8")
    print(to_markdown(results))
    sys.exit(1 if any("error" in r for r in results) else 0)


if __name__ == "__main__":
    main()
