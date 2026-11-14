#!/usr/bin/env python3
# Copyright 2026, OpenView contributors.
# SPDX-License-Identifier: BSL-1.0
"""Runs OKVIS2 with one camera over EuRoC sequences and scores it.

    run_okvis2_eval.py --app <okvis_app_synchronous> --config <euroc_mono.yaml> \\
                       --work <dir> <sequence_dir>...

Each sequence is an extracted EuRoC download (a directory holding `mav0/`).
OKVIS2 is pointed at cam0 only (the config lists a single camera, so its
DatasetReader never opens cam1), and the final trajectory it writes next to
the data is scored with the same ATE code as the Basalt bench
(`tools/bench/ate.py`), without fitting scale: a visual-inertial tracker is
supposed to recover metric scale from the IMU.

This is the experiment ADR-0003 asks for: whether a permissive monocular VIO
exists that a phone could use. EuRoC cameras are global shutter, so a good
score here does not by itself transfer to a phone's rolling shutter sensor.
"""

import argparse
import csv
import json
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent / "bench"))
from ate import evaluate, load_trajectory  # noqa: E402

GT_DEVICES = ["state_groundtruth_estimate0", "gt", "vicon0", "leica0", "mocap0"]


def find_mav0(sequence):
    if (sequence / "state_groundtruth_estimate0").exists() or sequence.name == "mav0":
        return sequence
    if (sequence / "mav0").exists():
        return sequence / "mav0"
    found = sorted(sequence.rglob("mav0"))
    return found[0] if found else None


def find_groundtruth(mav0):
    for device in GT_DEVICES:
        candidate = mav0 / device / "data.csv"
        if candidate.exists():
            return candidate
    return None


def sequence_duration_s(mav0):
    with open(mav0 / "cam0" / "data.csv", newline="") as f:
        stamps = [int(row[0]) for row in csv.reader(f) if row and not row[0].startswith("#")]
    return (stamps[-1] - stamps[0]) / 1e9


def run_one(args, sequence, out_dir):
    out_dir.mkdir(parents=True, exist_ok=True)
    result = {"backend": "okvis2", "mode": "mono", "sequence": sequence.name}
    mav0 = find_mav0(sequence)
    if mav0 is None:
        result["error"] = "no mav0 directory in sequence"
        return result
    groundtruth = find_groundtruth(mav0)
    if groundtruth is None:
        result["error"] = "sequence has no ground truth"
        return result
    result["duration_s"] = sequence_duration_s(mav0)

    trajectory = mav0 / "okvis2-slam-final_trajectory.csv"
    trajectory.unlink(missing_ok=True)
    log_path = out_dir / "log.txt"
    started = time.monotonic()
    try:
        with open(log_path, "w") as log:
            subprocess.run(
                ["xvfb-run", "-a", str(Path(args.app).resolve()), str(Path(args.config).resolve()),
                 str(mav0) + "/"],
                stdout=log, stderr=subprocess.STDOUT, timeout=args.timeout, check=False,
            )
    except subprocess.TimeoutExpired:
        result["error"] = f"timed out after {args.timeout} s"
        result["log_tail"] = log_path.read_text(errors="replace").splitlines()[-15:]
        return result
    result["wall_s"] = time.monotonic() - started
    result["realtime_factor"] = result["duration_s"] / result["wall_s"] if result["wall_s"] else None

    if not trajectory.exists():
        result["error"] = "OKVIS2 wrote no final trajectory"
        result["log_tail"] = log_path.read_text(errors="replace").splitlines()[-15:]
        return result

    est_t, est_p = load_trajectory(trajectory)
    result["poses"] = int(len(est_t))
    scores = evaluate(est_t, est_p, *load_trajectory(groundtruth)) if len(est_t) else None
    if scores is None:
        result["error"] = "too few tracked poses"
    else:
        result.update(scores)
    return result


def to_markdown(results):
    lines = [
        "| Sequence | Backend | Cameras | Duration | ATE RMSE | % of path | Coverage | Realtime factor | Note |",
        "|---|---|---|---|---|---|---|---|---|",
    ]
    for r in results:
        if "ate_rmse_m" in r:
            lines.append(
                f"| {r['sequence']} | OKVIS2 | 1 | {r['duration_s']:.0f} s | {r['ate_rmse_m'] * 100:.1f} cm "
                f"| {r['ate_rmse_percent_of_path']:.2f} % | {r['coverage'] * 100:.0f} % "
                f"| {r['realtime_factor']:.1f}x | |"
            )
        else:
            lines.append(f"| {r['sequence']} | OKVIS2 | 1 | {r.get('duration_s', 0):.0f} s | | | | | {r['error']} |")
    return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--app", required=True, help="path to okvis_app_synchronous")
    parser.add_argument("--config", required=True, help="monocular OKVIS2 YAML config")
    parser.add_argument("--work", required=True, type=Path, help="output directory")
    parser.add_argument("--timeout", type=int, default=3600, help="seconds allowed per run")
    parser.add_argument("sequences", nargs="+", type=Path)
    args = parser.parse_args()

    args.work.mkdir(parents=True, exist_ok=True)
    results = []
    for sequence in args.sequences:
        result = run_one(args, sequence.resolve(), args.work / sequence.name)
        results.append(result)
        print(json.dumps(result), flush=True)

    (args.work / "results.json").write_text(json.dumps(results, indent=2))
    (args.work / "results.md").write_text(to_markdown(results))
    print(to_markdown(results))


if __name__ == "__main__":
    main()
