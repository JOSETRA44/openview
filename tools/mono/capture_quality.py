#!/usr/bin/env python3
# Copyright 2026, OpenView contributors. SPDX-License-Identifier: BSL-1.0
"""Check recorded evidence of fixed camera controls; never infer calibration from it."""
import argparse
import csv
import json
import math
from pathlib import Path


def verified_rows(source):
    with (source / "frames.csv").open(newline="", encoding="utf-8") as stream:
        rows = list(csv.DictReader(stream))
    selected = []
    for row in rows:
        if row.get("control_verified") != "1":
            continue
        timestamp = row.get("sensor_timestamp_ns", "")
        if not timestamp.isdecimal():
            raise ValueError("Invalid sensor timestamp")
        if not (source / "frames" / (timestamp + ".pgm")).is_file():
            raise ValueError("Verified metadata has no corresponding image: " + timestamp)
        selected.append(row)
    return rows, selected


def audit(source, min_frames=30):
    reasons = []
    if not (source / "DONE").is_file():
        reasons.append("Capture has no DONE marker; wait for recording to finish")
    rows, selected = verified_rows(source)
    if len(selected) < min_frames:
        reasons.append(f"Need {min_frames} frames with verified controls; found {len(selected)}")
    if any(row.get("ae_mode") != "0" or row.get("af_mode") != "0" for row in selected):
        reasons.append("Verified frames must report both AE and AF disabled")
    ranges = {}
    for key in ("exposure_ns", "iso", "focus_distance_diopters"):
        values = []
        for row in selected:
            value = float(row.get(key) or "nan")
            if not math.isfinite(value) or value < 0 or (key != "focus_distance_diopters" and value == 0):
                reasons.append("Missing or invalid verified metadata: " + key)
                break
            values.append(value)
        if values:
            low, high = min(values), max(values)
            ranges[key] = [low, high]
            tolerance = .02 if key == "focus_distance_diopters" else max(1, low * .02)
            if high - low > tolerance:
                reasons.append("Controls changed despite verification: " + key)
    stamps = [int(row["sensor_timestamp_ns"]) for row in selected]
    # Callback arrival order can differ; duplicates cannot represent independent images.
    if len(stamps) != len(set(stamps)):
        reasons.append("Duplicate frame timestamps")
    return {"schema": "openview.capture-quality.v1", "status": "rejected" if reasons else "accepted_fixed_controls",
            "frames": len(rows), "verified_frames": len(selected), "ranges": ranges,
            "reasons": reasons, "not_verified": ["board_detection", "intrinsics", "camera_imu_extrinsics", "physical_position"]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        report = audit(args.source)
    except (ValueError, OSError) as error:
        report = {"status": "rejected", "reasons": [str(error)]}
    text = json.dumps(report, indent=2, allow_nan=False) + "\n"
    if args.output:
        args.output.write_text(text, encoding="utf-8")
    print(text)
    return 0 if report["status"] == "accepted_fixed_controls" else 2


if __name__ == "__main__":
    raise SystemExit(main())
