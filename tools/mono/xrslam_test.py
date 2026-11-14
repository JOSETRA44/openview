# Copyright 2026, OpenView contributors.
# SPDX-License-Identifier: BSL-1.0
import tempfile
from pathlib import Path
import unittest
import numpy as np
from xrslam_eval import load_tum, score


class ScoreTests(unittest.TestCase):
    def test_body_pose_units_and_no_scale_fitting(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            times = np.linspace(1, 11, 100)
            p = np.stack([np.sin(times), np.cos(times), times / 10], axis=1)
            truth = root / "truth.csv"
            tum = root / "pose.tum"
            np.savetxt(truth, np.column_stack([times * 1e9, p]), delimiter=",")
            np.savetxt(tum, np.column_stack([times, p + [1, 2, 3], np.tile([0, 0, 0, 1], (100, 1))]))
            result = score(tum, truth)
            self.assertLess(result["ate_rmse_m"], 1e-10)
            self.assertEqual(result["coverage"], 1)
            self.assertFalse(result["scale_fitted"])
            np.savetxt(tum, np.column_stack([times, p * 1.2, np.tile([0, 0, 0, 1], (100, 1))]))
            self.assertGreater(score(tum, truth)["ate_rmse_m"], .1)

    def test_bad_trajectory_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "pose.tum"
            path.write_text("1 0 0 0 0 0 0 1\n" * 3)
            with self.assertRaises(ValueError):
                load_tum(path)


if __name__ == "__main__":
    unittest.main()
