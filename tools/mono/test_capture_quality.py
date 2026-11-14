# Copyright 2026, OpenView contributors. SPDX-License-Identifier: BSL-1.0
import csv
from pathlib import Path
import tempfile
import unittest
from capture_quality import audit


class CaptureQualityTests(unittest.TestCase):
    def fixture(self, root, change=None):
        (root / "DONE").touch()
        (root / "frames").mkdir()
        rows = []
        for i in range(35):
            # Integers above float's exact range must remain precise filenames.
            stamp = str(12345678901234567 + i * 33333333)
            row = dict(sensor_timestamp_ns=stamp, control_verified="1" if i >= 5 else "0",
                       exposure_ns="5000000" if i >= 5 else "30000000", iso="400", focus_distance_diopters="0", ae_mode="0", af_mode="0")
            if change:
                change(i, row)
            rows.append(row)
            (root / "frames" / (stamp + ".pgm")).touch()
        with (root / "frames.csv").open("w", newline="") as stream:
            writer = csv.DictWriter(stream, fieldnames=rows[0].keys())
            writer.writeheader()
            writer.writerows(rows)

    def check(self, change=None, remove_done=False):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self.fixture(root, change)
            if remove_done:
                (root / "DONE").unlink()
            return audit(root)

    def test_excludes_auto_warmup_preserves_integer_timestamps(self):
        result = self.check()
        self.assertEqual(result["status"], "accepted_fixed_controls")
        self.assertEqual(result["verified_frames"], 30)
        self.assertEqual(result["ranges"]["exposure_ns"], [5000000, 5000000])

    def test_rejects_incomplete_capture(self):
        self.assertEqual(self.check(remove_done=True)["status"], "rejected")

    def test_rejects_lying_flag_when_exposure_changes(self):
        result = self.check(lambda i, r: r.update(exposure_ns="10000000") if i == 20 else None)
        self.assertEqual(result["status"], "rejected")

    def test_rejects_nonfinite_focus(self):
        result = self.check(lambda i, r: r.update(focus_distance_diopters="nan") if i == 20 else None)
        self.assertEqual(result["status"], "rejected")

    def test_rejects_automatic_modes_despite_verified_flag(self):
        self.assertEqual(self.check(lambda i, r: r.update(ae_mode="1"))["status"], "rejected")

    def test_rejects_missing_image(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self.fixture(root)
            # A verified frame (i=20) must have its image; deleting an
            # unverified warmup frame instead would be filesystem-order luck.
            (root / "frames" / (str(12345678901234567 + 20 * 33333333) + ".pgm")).unlink()
            with self.assertRaises(ValueError):
                audit(root)


if __name__ == "__main__":
    unittest.main()
