#!/usr/bin/env python3
# Copyright 2026, OpenView contributors.
# SPDX-License-Identifier: BSL-1.0
"""Absolute trajectory error of a tracker's output against ground truth.

    ate.py <estimate.csv> <groundtruth.csv> [--scale] [--json]

Both files use the EuRoC trajectory layout that Monado writes and its datasets
ship: timestamp [ns], position xyz [m], orientation quaternion wxyz.

The two trajectories live in unrelated coordinate frames, so the estimate is
first moved onto the ground truth with the rigid transform that fits best.
What is left is the error a user would feel as drift.
"""

import argparse
import csv
import json
import sys

import numpy as np


def load_trajectory(path):
    """Returns (timestamps_ns, positions[N,3]), sorted by time."""
    rows = []
    with open(path, newline="") as f:
        for row in csv.reader(f):
            if not row or row[0].lstrip().startswith("#"):
                continue
            rows.append([float(v) for v in row[:4]])
    data = np.array(rows, dtype=np.float64).reshape(len(rows), 4)
    data = data[np.argsort(data[:, 0])]
    return data[:, 0], data[:, 1:4]


def align(estimate, reference, with_scale=False):
    """Least-squares similarity transform taking estimate onto reference (Umeyama, 1991).

    Returns (scale, rotation[3,3], translation[3]). Scale stays 1 unless asked
    for: a visual-inertial tracker is supposed to get metric scale right, and
    fitting it would hide that error.
    """
    mu_e, mu_r = estimate.mean(axis=0), reference.mean(axis=0)
    e, r = estimate - mu_e, reference - mu_r

    u, d, vt = np.linalg.svd(r.T @ e / len(estimate))
    # Without this the best fit can be a reflection, which is not a motion.
    s = np.eye(3)
    if np.linalg.det(u) * np.linalg.det(vt) < 0:
        s[2, 2] = -1

    rotation = u @ s @ vt
    scale = float(np.trace(np.diag(d) @ s) / e.var(axis=0).sum()) if with_scale else 1.0
    translation = mu_r - scale * rotation @ mu_e
    return scale, rotation, translation


def evaluate(est_t, est_p, gt_t, gt_p, with_scale=False):
    """Compares only where both trajectories exist; ground truth is interpolated."""
    inside = (est_t >= gt_t[0]) & (est_t <= gt_t[-1])
    t, p = est_t[inside], est_p[inside]
    if len(t) < 3:
        return None

    reference = np.stack([np.interp(t, gt_t, gt_p[:, i]) for i in range(3)], axis=1)
    scale, rotation, translation = align(p, reference, with_scale)
    error = np.linalg.norm((scale * (rotation @ p.T)).T + translation - reference, axis=1)

    path_length = float(np.linalg.norm(np.diff(reference, axis=0), axis=1).sum())
    rmse = float(np.sqrt(np.mean(error**2)))
    return {
        "poses": int(len(t)),
        "ate_rmse_m": rmse,
        "ate_median_m": float(np.median(error)),
        "ate_max_m": float(error.max()),
        "path_length_m": path_length,
        # Error relative to distance travelled, comparable across sequences.
        "ate_rmse_percent_of_path": 100.0 * rmse / path_length if path_length > 0 else None,
        "scale": scale,
        # A tracker that gives up early can still score a low error on what it did track.
        "coverage": float((t[-1] - t[0]) / (gt_t[-1] - gt_t[0])),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("estimate")
    parser.add_argument("groundtruth")
    parser.add_argument("--scale", action="store_true", help="also fit scale (for scale-free trackers)")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    result = evaluate(*load_trajectory(args.estimate), *load_trajectory(args.groundtruth), with_scale=args.scale)
    if result is None:
        print("Not enough overlapping poses to evaluate.", file=sys.stderr)
        sys.exit(1)

    if args.json:
        json.dump(result, sys.stdout, indent=2)
        print()
    else:
        for key, value in result.items():
            print(f"{key}: {value}")


if __name__ == "__main__":
    main()
