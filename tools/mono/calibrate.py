#!/usr/bin/env python3
# Copyright 2026, OpenView contributors.
# SPDX-License-Identifier: BSL-1.0
"""Calibrate recorder images using a printed checkerboard; never invent a phone calibration.

Input is a recorder directory (frames/*.pgm) or a directory containing images.
Output is a versioned JSON report: intrinsics are present ONLY when quality gates pass.
This is intrinsic calibration, not camera/IMU extrinsics or a tracking backend.
"""

import argparse
import csv
import json
from pathlib import Path

import cv2
import numpy as np


def board_points(columns, rows, square_m):
    if columns < 3 or rows < 3 or not np.isfinite(square_m) or square_m <= 0:
        raise ValueError("Board needs at least 3x3 internal corners and a positive square size")
    points = np.zeros((rows * columns, 3), np.float32)
    points[:, :2] = np.mgrid[:columns, :rows].T.reshape(-1, 2) * square_m
    return points


def write_board(path, columns, rows, square_mm):
    board_points(columns, rows, square_mm / 1000)
    margin = square_mm
    width, height = (columns + 3) * square_mm, (rows + 3) * square_mm
    parts = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}mm" height="{height}mm" viewBox="0 0 {width} {height}">',
             f'<rect width="{width}" height="{height}" fill="white"/>']
    for row in range(rows + 1):
        for col in range(columns + 1):
            if (row + col) % 2 == 0:
                parts.append(f'<rect x="{margin + col * square_mm}" y="{margin + row * square_mm}" width="{square_mm}" height="{square_mm}" fill="black"/>')
    parts.append("</svg>\n")
    path.write_text("\n".join(parts), encoding="utf-8")


def calibration_report(image_points, image_size, columns=8, rows=6, square_m=.02,
                       max_error_px=1.0, min_views=12):
    """Fit train views, validate unused views, and refit only after all gates pass.

    Holdout views still fit their own board pose using solvePnP; they never fit
    the intrinsics. Thresholds are acceptance heuristics, not an accuracy guarantee.
    """
    obj = board_points(columns, rows, square_m)
    if min_views < 12 or not np.isfinite(max_error_px) or max_error_px <= 0:
        raise ValueError("min_views must be >=12; max_error_px must be positive")
    width, height = image_size
    if width <= 0 or height <= 0:
        raise ValueError("Invalid image dimensions")
    points = [np.asarray(p, np.float32).reshape(-1, 2) for p in image_points]
    for p in points:
        if len(p) != len(obj) or not np.isfinite(p).all():
            raise ValueError("Each view needs finite coordinates for every internal corner")
        if (p < 0).any() or (p >= np.array([width, height])).any():
            raise ValueError("Corners outside image bounds")
    report = {"schema": "openview.camera-calibration.v1", "status": "rejected",
              "image_size": [int(width), int(height)], "model": "pinhole-radtan5",
              "board": {"internal_corners": [columns, rows], "square_size_m": square_m},
              "views": len(points), "reasons": [], "metrics": {},
              "not_estimated": ["camera_to_imu", "time_offset", "rolling_shutter", "metric_trajectory"]}
    reasons, metrics = report["reasons"], report["metrics"]
    if len(points) < min_views:
        reasons.append(f"Need at least {min_views} detected board views; found {len(points)}")
        return report
    centers = np.array([p.mean(axis=0) for p in points]) / [width, height]
    span = np.ptp(centers, axis=0)
    metrics["center_span_fraction"] = span.tolist()
    if span[0] < .2 or span[1] < .15:
        reasons.append("Move the board across both image axes; central repeated views are insufficient")
    areas = np.array([cv2.contourArea(cv2.convexHull(p)) / (width * height) for p in points])
    metrics["board_area_min_max_fraction"] = [float(areas.min()), float(areas.max())]
    if areas.min() <= .01 or areas.max() < .08 or areas.max() / max(areas.min(), 1e-9) < 1.5:
        reasons.append("Vary board distance while keeping corners readable and include a large board view")
    # Nearly identical frames must not count as independent calibration evidence.
    unique = []
    for p in points:
        if all(np.sqrt(np.mean((p - q) ** 2)) > 3.0 for q in unique):
            unique.append(p)
    metrics["distinct_views"] = len(unique)
    if len(unique) < min_views:
        reasons.append("Too many repeated views; hold the board in at least twelve distinct poses")
    if reasons:
        return report

    holdout = [i for i in range(len(points)) if i % 4 == 0]
    train = [i for i in range(len(points)) if i not in holdout]
    # Fix k3: low-resolution phone images rarely constrain the sixth-order term.
    flags = cv2.CALIB_FIX_K3
    try:
        rms, matrix, distortion, rvecs, _ = cv2.calibrateCamera(
            [obj] * len(train), [points[i] for i in train], image_size, None, None, flags=flags)
        normals = np.array([cv2.Rodrigues(r)[0][:, 2] for r in rvecs])
        normal_span = float(np.rad2deg(np.arccos(np.clip(normals @ normals.T, -1, 1))).max())
        metrics["normal_span_degrees"] = normal_span
        if normal_span < 15:
            reasons.append("Tilt the board: frontoparallel views cannot constrain focal length reliably")
        held_errors = []
        for i in holdout:
            ok, rotation, translation = cv2.solvePnP(obj, points[i], matrix, distortion)
            if not ok:
                reasons.append("Could not estimate a held-out board pose")
                return report
            projected, _ = cv2.projectPoints(obj, rotation, translation, matrix, distortion)
            held_errors.append(float(np.sqrt(np.mean(np.sum((projected.reshape(-1, 2) - points[i]) ** 2, axis=1)))))
        metrics.update({"train_rms_px": float(rms), "holdout_views": len(holdout),
                        "holdout_rms_px": float(np.sqrt(np.mean(np.square(held_errors)))),
                        "holdout_max_view_rms_px": max(held_errors)})
        if not np.isfinite(matrix).all() or not np.isfinite(distortion).all() or not np.isfinite(rms):
            reasons.append("Non-finite solver result")
        if not np.isfinite(held_errors).all() or rms > max_error_px or max(held_errors) > max_error_px:
            reasons.append("Reprojection error exceeds threshold; check blur, board flatness and detection")
        if not (.2 * width < matrix[0, 0] < 5 * width and .2 * height < matrix[1, 1] < 5 * height
                and 0 < matrix[0, 2] < width and 0 < matrix[1, 2] < height):
            reasons.append("Implausible focal length or principal point")
        if reasons:
            return report
        final_rms, matrix, distortion, _, _ = cv2.calibrateCamera(
            [obj] * len(points), points, image_size, None, None, flags=flags)
        if not np.isfinite(matrix).all() or not np.isfinite(distortion).all() or not np.isfinite(final_rms) or final_rms > max_error_px:
            reasons.append("Final fit failed finite-value or reprojection checks")
            return report
    except cv2.error as error:
        reasons.append(f"OpenCV calibration failed: {error}")
        return report
    metrics["all_views_rms_px"] = float(final_rms)
    report["status"] = "accepted_intrinsics_only"
    report["intrinsics"] = {"fx": float(matrix[0, 0]), "fy": float(matrix[1, 1]),
                            "cx": float(matrix[0, 2]), "cy": float(matrix[1, 2])}
    report["distortion"] = dict(zip(["k1", "k2", "p1", "p2", "k3"], map(float, distortion.ravel()[:5])))
    return report


def scan_images(source, columns, rows, stride=10, max_images=300):
    directory = source / "frames" if (source / "frames").is_dir() else source
    files = sorted(p for p in directory.iterdir() if p.suffix.lower() in {".pgm", ".png", ".jpg", ".jpeg"})
    selected = files[::stride][:max_images]
    points, names, rejected = [], [], []
    size = None
    for path in selected:
        gray = cv2.imread(str(path), cv2.IMREAD_GRAYSCALE)
        if gray is None:
            raise ValueError(f"Cannot read {path}")
        current = (gray.shape[1], gray.shape[0])
        if size is not None and current != size:
            raise ValueError("Mixed image resolutions: calibrate each camera stream independently")
        size = current
        found, corners = cv2.findChessboardCornersSB(gray, (columns, rows), cv2.CALIB_CB_NORMALIZE_IMAGE)
        if found:
            points.append(corners.reshape(-1, 2))
            names.append(path.name)
        else:
            rejected.append(path.name)
    if size is None:
        raise ValueError("No images found")
    return points, size, {"sampled_images": len(selected), "detected_files": names, "undetected_files": rejected}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="recorder capture or image directory; output SVG for --board")
    parser.add_argument("--board", action="store_true", help="generate printable checkerboard SVG instead of calibrating")
    parser.add_argument("--columns", type=int, default=8, help="INTERNAL corners, not squares")
    parser.add_argument("--rows", type=int, default=6)
    parser.add_argument("--square-mm", type=float, default=20)
    parser.add_argument("--stride", type=int, default=10)
    parser.add_argument("--max-images", type=int, default=300)
    parser.add_argument("--max-error-px", type=float, default=1)
    parser.add_argument("--output", type=Path, default=Path("calibration.json"))
    args = parser.parse_args(argv)
    try:
        board_points(args.columns, args.rows, args.square_mm / 1000)
        if args.board:
            write_board(args.source, args.columns, args.rows, args.square_mm)
            return 0
        if args.stride < 1 or args.max_images < 12:
            raise ValueError("stride must be positive and max-images at least 12")
        points, size, detection = scan_images(args.source, args.columns, args.rows, args.stride, args.max_images)
        report = calibration_report(points, size, args.columns, args.rows, args.square_mm / 1000, args.max_error_px)
        report["detection"] = detection
        report["source"] = str(args.source.resolve())
        report["opencv_version"] = cv2.__version__
        device = args.source / "device.json"
        if device.exists():
            report["device"] = json.loads(device.read_text(encoding="utf-8"))
        metadata = args.source / "frames.csv"
        if metadata.exists():
            with metadata.open(newline="", encoding="utf-8") as stream:
                values = list(csv.DictReader(stream))
            selected = set(detection["detected_files"])
            focus = [float(v["focus_distance_diopters"]) for v in values
                     if v.get("focus_distance_diopters") and v["sensor_timestamp_ns"] + ".pgm" in selected]
            if focus:
                report["focus_distance_diopters_range"] = [min(focus), max(focus)]
                if max(focus) - min(focus) > .1:
                    report["status"] = "rejected"
                    report["reasons"].append("Focus changed during capture; lock focus before calibrating")
                    report.pop("intrinsics", None)
                    report.pop("distortion", None)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2, allow_nan=False) + "\n", encoding="utf-8")
        print(json.dumps({"status": report["status"], "views": report["views"], "reasons": report["reasons"]}))
        return 0 if report["status"] == "accepted_intrinsics_only" else 2
    except (ValueError, OSError, cv2.error) as error:
        parser.exit(2, f"calibration rejected: {error}\n")


if __name__ == "__main__":
    raise SystemExit(main())
