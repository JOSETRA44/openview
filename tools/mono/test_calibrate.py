# Copyright 2026, OpenView contributors.
# SPDX-License-Identifier: BSL-1.0
"""Synthetic geometry tests plus image detection and CLI rejection contracts."""
import json
from pathlib import Path
import tempfile
import unittest

import cv2
import numpy as np

from calibrate import board_points, calibration_report, main, scan_images, write_board


def synthetic_views(noise=.08):
    rng = np.random.default_rng(123)
    matrix = np.array([[510., 0., 320.], [0., 505., 240.], [0., 0., 1.]])
    distortion = np.array([-.08, .015, .001, -.001, 0.])
    obj = board_points(8, 6, .02)
    points = []
    for i in range(32):
        rotation = rng.uniform(-.65, .65, 3)
        translation = np.array([rng.uniform(-.22, .07), rng.uniform(-.17, .02), rng.uniform(.35, .65)])
        p, _ = cv2.projectPoints(obj, rotation, translation, matrix, distortion)
        p = p.reshape(-1, 2)
        if (p > 5).all() and (p < [635, 475]).all():
            points.append((p + rng.normal(0, noise, p.shape)).astype(np.float32))
    return matrix, points


class CalibrationTests(unittest.TestCase):
    def test_recovers_known_intrinsics_without_fitting_holdout(self):
        matrix, points = synthetic_views()
        result = calibration_report(points, (640, 480))
        self.assertEqual(result["status"], "accepted_intrinsics_only", result)
        for name, expected in [("fx", matrix[0, 0]), ("fy", matrix[1, 1]), ("cx", matrix[0, 2]), ("cy", matrix[1, 2])]:
            self.assertLess(abs(result["intrinsics"][name] - expected), 3.0)
        self.assertGreaterEqual(result["metrics"]["holdout_views"], 3)
        self.assertLess(result["metrics"]["holdout_max_view_rms_px"], .3)

    def test_repeated_frames_never_produce_a_calibration(self):
        _, points = synthetic_views()
        report = calibration_report([points[0]] * 20, (640, 480))
        self.assertEqual(report["status"], "rejected")
        self.assertNotIn("intrinsics", report)

    def test_corrupted_corner_locations_are_rejected(self):
        _, points = synthetic_views(noise=5)
        result = calibration_report(points, (640, 480))
        self.assertEqual(result["status"], "rejected")
        self.assertNotIn("intrinsics", result)

    def test_too_few_views_are_rejected(self):
        _, points = synthetic_views()
        report = calibration_report(points[:3], (640, 480))
        self.assertEqual(report["status"], "rejected")
        self.assertNotIn("distortion", report)

    def test_nonfinite_input_is_rejected(self):
        _, points = synthetic_views()
        points[0][0, 0] = np.nan
        with self.assertRaises(ValueError):
            calibration_report(points, (640, 480))

    def test_generated_pgm_board_is_detected(self):
        # Exact pixel grid: 9x7 squares means 8x6 INTERNAL corners.
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            image = np.full((480, 640), 255, np.uint8)
            for y in range(7):
                for x in range(9):
                    if (x + y) % 2 == 0:
                        image[100 + y * 35:100 + (y + 1) * 35, 150 + x * 35:150 + (x + 1) * 35] = 0
            cv2.imwrite(str(root / "1.pgm"), image)
            points, size, report = scan_images(root, 8, 6, stride=1)
            self.assertEqual(size, (640, 480))
            self.assertEqual(len(points), 1)
            self.assertEqual(len(points[0]), 48)
            self.assertEqual(report["detected_files"], ["1.pgm"])

    def test_blank_capture_cli_fails_and_report_has_no_intrinsics(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            cv2.imwrite(str(root / "1.pgm"), np.zeros((480, 640), np.uint8))
            output = root / "result.json"
            self.assertEqual(main([str(root), "--output", str(output)]), 2)
            result = json.loads(output.read_text())
            self.assertNotIn("intrinsics", result)
            self.assertEqual(result["views"], 0)

    def test_svg_dimensions_preserve_square_scale(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "board.svg"
            write_board(path, 8, 6, 20)
            self.assertIn('width="220mm" height="180mm"', path.read_text())


if __name__ == "__main__":
    unittest.main()
