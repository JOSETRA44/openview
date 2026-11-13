#!/usr/bin/env python3
# Copyright 2026, OpenView contributors.
# SPDX-License-Identifier: BSL-1.0
"""Run the headless cam0+IMU experiment, score TUM body poses without fitting scale."""
import argparse
import json
from pathlib import Path
import subprocess
import sys
import time

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "bench"))
from ate import evaluate, load_trajectory


def load_tum(path):
    rows = []
    for line in Path(path).read_text().splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        values = [float(v) for v in line.split()]
        if len(values) != 8 or not np.isfinite(values).all():
            raise ValueError("TUM pose must have eight finite values")
        rows.append(values)
    if len(rows) < 3:
        raise ValueError("Fewer than three tracked poses")
    poses = np.array(rows)
    if not np.all(np.diff(poses[:, 0]) > 0):
        raise ValueError("Pose timestamps must be strictly increasing")
    return poses[:, 0] * 1e9, poses[:, 1:4]


def score(trajectory, truth):
    estimated_t, estimated_p = load_tum(trajectory)
    result = evaluate(estimated_t, estimated_p, *load_trajectory(truth), with_scale=False)
    if result is None:
        raise ValueError("Insufficient overlapping ground truth")
    result["max_pose_gap_s"] = float(np.max(np.diff(estimated_t)) / 1e9)
    result["scale_fitted"] = False
    result["camera_count"] = 1
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--binary", required=True, type=Path)
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--sequence", required=True, type=Path, help="mav0 directory")
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--timeout", type=int, default=300)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    trajectory = args.output / "trajectory.tum"
    if trajectory.exists():
        parser.error("Refusing to reuse a directory containing a trajectory")
    report = {"backend": "XRSLAM", "revision": "4beb1a942f33da9afbfae2d70e2c641cfc2bb675",
              "status": "failed", "sequence": str(args.sequence), "camera_count": 1}
    start = time.monotonic()
    command = [str(args.binary.resolve()), str((args.source / "configs/euroc_slam.yaml").resolve()),
               str((args.source / "configs/euroc_sensor.yaml").resolve()), str(args.sequence.resolve()), str(trajectory.resolve())]
    report["command"] = command
    try:
        with (args.output / "tracker.log").open("w") as log:
            run = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=args.timeout)
        report["exit_code"] = run.returncode
        if run.returncode != 0:
            raise RuntimeError(f"Tracker exited with {run.returncode}; no completed-run score accepted")
        report.update(score(trajectory, args.sequence / "state_groundtruth_estimate0/data.csv"))
        # Loose smoke gate; not a product quality or phone suitability claim.
        if report["coverage"] < .8 or report["ate_rmse_m"] > 1 or report["max_pose_gap_s"] > 2:
            report["status"] = "quality_gate_failed"
        else:
            report["status"] = "passed_offline_smoke"
    except (OSError, ValueError, RuntimeError, subprocess.TimeoutExpired) as error:
        report["error"] = str(error)
    report["wall_s"] = time.monotonic() - start
    (args.output / "results.json").write_text(json.dumps(report, indent=2, allow_nan=False) + "\n")
    print(json.dumps(report, indent=2, allow_nan=False))
    return 0 if report["status"] == "passed_offline_smoke" else 1


if __name__ == "__main__":
    raise SystemExit(main())
