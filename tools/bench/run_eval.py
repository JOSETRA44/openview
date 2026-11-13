#!/usr/bin/env python3
# Copyright 2026, OpenView contributors.
# SPDX-License-Identifier: BSL-1.0
"""Runs a VIT tracking backend over EuRoC-format sequences and scores it.

    run_eval.py --cli <monado-cli> --vit-library <libbasalt.so> \\
                --calib <calib.json> --vio-config <config.json> \\
                --work <dir> <sequence_dir>...

Each sequence is played through Monado's own SLAM tracker (`monado-cli
slambatch`), the same code path the runtime uses, once with every camera the
calibration describes and once with the first camera only. The monocular run is
the one that matters for phones.

Linux only: Monado builds its SLAM tracker and EuRoC player nowhere else.
"""

import argparse
import csv
import json
import os
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from ate import evaluate, load_trajectory  # noqa: E402

# Ground truth directory names used by EuRoC, TUM-VI and the Monado datasets.
GT_DEVICES = ["gt", "state_groundtruth_estimate0", "mocap0", "vicon0", "leica0"]


def first_camera_only(calib):
    """A copy of a Basalt calibration describing just camera 0."""
    value = dict(calib["value0"])
    cam_count = len(value["intrinsics"])
    for key, entry in value.items():
        # Per-camera fields are the lists with one element per camera.
        if isinstance(entry, list) and len(entry) == cam_count and key != "calib_accel_bias":
            value[key] = entry[:1]
    return {"value0": value}


def write_slam_config(path, calib_path, vio_config_path):
    path.write_text(
        "show-gui=0\n"
        f'cam-calib="{calib_path}"\n'
        f'config-path="{vio_config_path}"\n'
        'marg-data=""\n'
        "print-queue=0\n"
        "use-double=0\n"
        # Without this two runs of the same sequence differ, and so would the scores.
        "deterministic=1\n"
        "num-threads=0\n"
    )


def sequence_duration_s(sequence):
    with open(sequence / "mav0" / "cam0" / "data.csv", newline="") as f:
        stamps = [int(row[0]) for row in csv.reader(f) if row and not row[0].startswith("#")]
    return (stamps[-1] - stamps[0]) / 1e9, len(stamps)


def find_groundtruth(sequence):
    for device in GT_DEVICES:
        candidate = sequence / "mav0" / device / "data.csv"
        if candidate.exists():
            return candidate
    return None


def run_one(args, sequence, mode, calib_path, out_dir):
    out_dir.mkdir(parents=True, exist_ok=True)
    slam_config = out_dir / "slam.toml"
    write_slam_config(slam_config, calib_path, Path(args.vio_config).resolve())

    env = dict(os.environ, VIT_SYSTEM_LIBRARY_PATH=str(Path(args.vit_library).resolve()))
    if mode == "mono":
        env["EUROC_CAM_COUNT"] = "1"

    duration_s, frame_count = sequence_duration_s(sequence)
    result = {"sequence": sequence.name, "mode": mode, "duration_s": duration_s, "frames": frame_count}

    started = time.monotonic()
    log_path = out_dir / "log.txt"
    with open(log_path, "w") as log:
        # slambatch quits when it can read from stdin, so stdin must stay an open, silent pipe.
        process = subprocess.Popen(
            [args.cli, "slambatch", str(sequence), str(slam_config), str(out_dir) + "/"],
            stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, env=env,
        )
        try:
            result["exit_code"] = process.wait(timeout=args.timeout)
        except subprocess.TimeoutExpired:
            process.kill()
            result["exit_code"] = "timeout"
    wall_s = time.monotonic() - started
    result["wall_s"] = wall_s
    # Played at maximum speed: above 1 the tracker keeps up with a live camera on this machine.
    result["realtime_factor"] = duration_s / wall_s if wall_s > 0 else None

    tracking = out_dir / "tracking.csv"
    groundtruth = find_groundtruth(sequence)
    if result["exit_code"] != 0 or not tracking.exists():
        result["error"] = "tracker did not finish"
        result["log_tail"] = log_path.read_text(errors="replace").splitlines()[-15:]
        return result
    if groundtruth is None:
        result["error"] = "sequence has no ground truth"
        return result

    est_t, est_p = load_trajectory(tracking)
    result["poses"] = int(len(est_t))
    scores = evaluate(est_t, est_p, *load_trajectory(groundtruth)) if len(est_t) else None
    if scores is None:
        result["error"] = "too few tracked poses"
        result["log_tail"] = log_path.read_text(errors="replace").splitlines()[-15:]
    else:
        result.update(scores)
    return result


def to_markdown(results):
    lines = [
        "| Sequence | Cameras | Duration | ATE RMSE | % of path | Coverage | Realtime factor | Note |",
        "|---|---|---|---|---|---|---|---|",
    ]
    for r in results:
        if "ate_rmse_m" in r:
            lines.append(
                f"| {r['sequence']} | {r['mode']} | {r['duration_s']:.0f} s | {r['ate_rmse_m'] * 100:.1f} cm "
                f"| {r['ate_rmse_percent_of_path']:.2f} % | {r['coverage'] * 100:.0f} % "
                f"| {r['realtime_factor']:.1f}x | |"
            )
        else:
            lines.append(f"| {r['sequence']} | {r['mode']} | {r['duration_s']:.0f} s | | | | | {r['error']} |")
    return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--cli", required=True, help="path to monado-cli")
    parser.add_argument("--vit-library", required=True, help="tracking backend implementing VIT")
    parser.add_argument("--calib", required=True, help="Basalt calibration for the sequences' device")
    parser.add_argument("--vio-config", required=True, help="Basalt VIO configuration")
    parser.add_argument("--work", required=True, type=Path, help="output directory")
    parser.add_argument("--timeout", type=int, default=1800, help="seconds allowed per run")
    parser.add_argument("--modes", default="stereo,mono")
    parser.add_argument("sequences", nargs="+", type=Path)
    args = parser.parse_args()

    args.work.mkdir(parents=True, exist_ok=True)
    calibs = {"stereo": Path(args.calib).resolve()}
    mono_calib = args.work / "calib_mono.json"
    mono_calib.write_text(json.dumps(first_camera_only(json.loads(Path(args.calib).read_text()))))
    calibs["mono"] = mono_calib.resolve()

    results = []
    for sequence in args.sequences:
        for mode in args.modes.split(","):
            result = run_one(args, sequence.resolve(), mode, calibs[mode], args.work / sequence.name / mode)
            results.append(result)
            print(json.dumps(result), flush=True)

    (args.work / "results.json").write_text(json.dumps(results, indent=2))
    (args.work / "results.md").write_text(to_markdown(results))
    print(to_markdown(results))


if __name__ == "__main__":
    main()
