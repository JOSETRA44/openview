#!/usr/bin/env python3
# Copyright 2026, OpenView contributors.
# SPDX-License-Identifier: BSL-1.0
"""Characterise a sensor-recorder capture and optionally export it as EuRoC.

    analyze.py <recording_dir> [--euroc <out_dir>] [--json]

Answers, for one device, the questions a visual-inertial tracker depends on:
which clock the camera stamps with, how regular the IMU is, how long the
rolling shutter takes, and how far apart camera and IMU time really are.
"""

import argparse
import csv
import json
import shutil
import sys
from pathlib import Path

import numpy as np


# Below this the gyro and image motion do not share a shape worth aligning.
MIN_OFFSET_CORRELATION = 0.5


def load_csv(path):
    """Returns the file as a float array, one row per line, header skipped."""
    with open(path, newline="") as f:
        rows = [[float(v) if v else np.nan for v in row] for row in list(csv.reader(f))[1:]]
    return np.array(rows, dtype=np.float64).reshape(len(rows), -1)


def read_pgm(path):
    with open(path, "rb") as f:
        assert f.readline().strip() == b"P5", f"{path} is not a binary PGM"
        width, height = map(int, f.readline().split())
        f.readline()  # maxval
        return np.frombuffer(f.read(width * height), dtype=np.uint8).reshape(height, width)


def interval_stats(timestamps_ns):
    """Rate and regularity of a timestamp series. Intervals in milliseconds."""
    dt = np.diff(timestamps_ns) / 1e6
    if len(dt) == 0:
        return None
    median = float(np.median(dt))
    return {
        "count": int(len(timestamps_ns)),
        "rate_hz": 1000.0 / median,
        "interval_median_ms": median,
        "interval_p99_ms": float(np.percentile(dt, 99)),
        "interval_max_ms": float(dt.max()),
        # Samples that arrived more than half an interval late: dropped or batched.
        "gaps": int(np.sum(dt > 1.5 * median)),
    }


def image_shift(a, b):
    """Translation in pixels taking image a to image b, by phase correlation."""
    fa = np.fft.rfft2(a.astype(np.float32))
    fb = np.fft.rfft2(b.astype(np.float32))
    cross = fa.conj() * fb
    cross /= np.abs(cross) + 1e-9
    corr = np.fft.irfft2(cross, s=a.shape)
    y, x = np.unravel_index(np.argmax(corr), corr.shape)
    # The correlation is periodic: peaks past the middle are negative shifts.
    if y > a.shape[0] // 2:
        y -= a.shape[0]
    if x > a.shape[1] // 2:
        x -= a.shape[1]
    return float(x), float(y)


def estimate_time_offset(t_a_ns, a, t_b_ns, b, max_offset_ms=100.0, step_ms=1.0):
    """Offset to add to series b's timestamps to line it up with series a.

    Both are resampled onto a common grid and compared at every candidate
    offset; the best normalised correlation wins. Returns (offset_ms, score),
    score in [-1, 1]. A low score means the motion was too weak to tell.
    """
    step_ns = step_ms * 1e6
    start = max(t_a_ns[0], t_b_ns[0]) + max_offset_ms * 1e6
    end = min(t_a_ns[-1], t_b_ns[-1]) - max_offset_ms * 1e6
    if end - start < 1e9:
        return None, 0.0

    grid = np.arange(start, end, step_ns)
    ref = np.interp(grid, t_a_ns, a)
    ref = (ref - ref.mean()) / (ref.std() + 1e-12)

    best_offset, best_score = 0.0, -2.0
    for offset_ms in np.arange(-max_offset_ms, max_offset_ms + step_ms, step_ms):
        shifted = np.interp(grid, t_b_ns + offset_ms * 1e6, b)
        shifted = (shifted - shifted.mean()) / (shifted.std() + 1e-12)
        score = float(np.mean(ref * shifted))
        if score > best_score:
            best_offset, best_score = float(offset_ms), score
    return best_offset, best_score


def camera_imu_offset(rec, frames, gyro, max_frames=600):
    """Time offset between camera and gyro, from how fast the image moves.

    Rotating the phone slides the image at a speed proportional to the angular
    rate, so the two signals share a shape and only differ by a delay.
    """
    ts = frames[:max_frames, 0]
    if len(ts) < 30:
        return None
    images = [read_pgm(rec / "frames" / f"{int(t)}.pgm")[::2, ::2] for t in ts]
    speed = []
    for prev, cur, t0, t1 in zip(images, images[1:], ts, ts[1:]):
        dx, dy = image_shift(prev, cur)
        speed.append(np.hypot(dx, dy) / ((t1 - t0) / 1e9))
    # A frame-to-frame shift describes the motion halfway between the two frames.
    mid_ts = (ts[:-1] + ts[1:]) / 2
    # Rotation about the optical axis barely translates the image, so only the
    # two axes in the image plane are compared.
    gyro_rate = np.hypot(gyro[:, 1], gyro[:, 2])
    offset_ms, score = estimate_time_offset(gyro[:, 0], gyro_rate, mid_ts, np.array(speed))
    if score < MIN_OFFSET_CORRELATION:
        # Without rotation the two signals are noise and the best match is arbitrary.
        return {"gyro_minus_camera_ms": None, "correlation": score,
                "note": "not enough rotation in the capture to estimate the offset"}
    return {"gyro_minus_camera_ms": offset_ms, "correlation": score}


def analyze(rec, with_offset=True):
    device = json.loads((rec / "device.json").read_text())
    frames = load_csv(rec / "frames.csv")
    gyro = load_csv(rec / "gyro.csv")
    accel = load_csv(rec / "accel.csv")
    frames = frames[np.argsort(frames[:, 0])]

    # The recorder leaves out whatever the device does not report.
    camera = device["camera"]
    report = {
        "device": f"{device['manufacturer']} {device['model']} ({device.get('soc')})",
        "camera_timestamp_source": camera.get("timestamp_source"),
        "camera_hardware_level": camera.get("hardware_level"),
        "camera_reports_intrinsics": "intrinsic_calibration" in camera,
        "gyro": interval_stats(gyro[:, 0]),
        "accel": interval_stats(accel[:, 0]),
        "camera": interval_stats(frames[:, 0]),
    }

    if len(frames):
        skew = frames[:, 2]
        report["rolling_shutter_skew_ms"] = None if np.all(np.isnan(skew)) else float(np.nanmedian(skew) / 1e6)
        report["exposure_ms"] = {
            "median": float(np.nanmedian(frames[:, 1]) / 1e6),
            "max": float(np.nanmax(frames[:, 1]) / 1e6),
        }
        # Arrival is stamped with CLOCK_MONOTONIC. If the sensor timestamp is on
        # the boot clock, as REALTIME promises, subtracting the clock offset
        # leaves the capture latency: tens of milliseconds, always positive.
        latency = (frames[:, 6] - (frames[:, 0] - device["boottime_minus_monotonic_ns"])) / 1e6
        report["frame_latency_assuming_boottime_ms"] = {
            "median": float(np.median(latency)),
            "min": float(latency.min()),
        }

    if with_offset and len(frames) and len(gyro):
        offset = camera_imu_offset(rec, frames, gyro)
        if offset and offset["gyro_minus_camera_ms"] is not None:
            # A frame is stamped when its first row starts exposing, but what it
            # shows was gathered on average half an exposure and half a readout
            # later. Whatever is left after that is a real clock disagreement.
            expected = float(np.nanmedian(frames[:, 1]) / 2e6 + np.nanmedian(frames[:, 2]) / 2e6)
            offset["expected_from_exposure_and_readout_ms"] = expected
            offset["residual_clock_offset_ms"] = offset["gyro_minus_camera_ms"] - expected
        report["camera_imu_offset"] = offset

    return report


def export_euroc(rec, out):
    """Writes the EuRoC ASL layout that Monado's euroc driver reads."""
    frames = load_csv(rec / "frames.csv")
    frames = frames[np.argsort(frames[:, 0])]
    gyro = load_csv(rec / "gyro.csv")
    accel = load_csv(rec / "accel.csv")

    cam = out / "mav0" / "cam0"
    (cam / "data").mkdir(parents=True, exist_ok=True)
    with open(cam / "data.csv", "w", newline="") as f:
        f.write("#timestamp [ns],filename\n")
        for t in frames[:, 0]:
            name = f"{int(t)}.pgm"
            shutil.copyfile(rec / "frames" / name, cam / "data" / name)
            f.write(f"{int(t)},{name}\n")

    # EuRoC wants one row per IMU sample. The two sensors tick independently,
    # so the accelerometer is interpolated at the gyroscope's timestamps.
    imu = out / "mav0" / "imu0"
    imu.mkdir(parents=True, exist_ok=True)
    inside = (gyro[:, 0] >= accel[0, 0]) & (gyro[:, 0] <= accel[-1, 0])
    g = gyro[inside]
    a = np.stack([np.interp(g[:, 0], accel[:, 0], accel[:, i]) for i in (1, 2, 3)], axis=1)
    with open(imu / "data.csv", "w", newline="") as f:
        f.write("#timestamp [ns],w_RS_S_x [rad s^-1],w_RS_S_y [rad s^-1],w_RS_S_z [rad s^-1],"
                "a_RS_S_x [m s^-2],a_RS_S_y [m s^-2],a_RS_S_z [m s^-2]\n")
        for row, acc in zip(g, a):
            f.write(f"{int(row[0])},{row[1]:.9g},{row[2]:.9g},{row[3]:.9g},"
                    f"{acc[0]:.9g},{acc[1]:.9g},{acc[2]:.9g}\n")
    return len(frames), len(g)


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("recording", type=Path)
    parser.add_argument("--euroc", type=Path, help="also export in EuRoC layout to this directory")
    parser.add_argument("--json", action="store_true", help="print the report as JSON")
    parser.add_argument("--no-offset", action="store_true", help="skip the camera-IMU offset estimate")
    args = parser.parse_args()

    report = analyze(args.recording, with_offset=not args.no_offset)
    if args.json:
        json.dump(report, sys.stdout, indent=2)
        print()
    else:
        for key, value in report.items():
            print(f"{key}: {value}")

    if args.euroc:
        frame_count, imu_count = export_euroc(args.recording, args.euroc)
        print(f"EuRoC: {frame_count} frames, {imu_count} IMU samples -> {args.euroc}", file=sys.stderr)


if __name__ == "__main__":
    main()
