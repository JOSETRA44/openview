# Copyright 2026, OpenView contributors. SPDX-License-Identifier: BSL-1.0
import unittest

import analyze

STEPS = [("still_start", "STILL", "0,0,0"), ("forward", "MOVE", "0,0,-1"), ("still_forward", "STILL", "0,0,0"),
         ("turn", "ROTATE", "any"), ("still_end", "STILL", "0,0,0")]


def synthetic(positions_for):
    """A log with 20 samples per step at 15 Hz; positions_for(step, i) gives the head."""
    lines, t = [], 0.0
    for index, (sid, kind, exp) in enumerate(STEPS):
        lines.append(f"I/OpenViewXR: OV_TEST_STEP index={index} id={sid} kind={kind} seconds=4 expected={exp}")
        for i in range(20):
            x, y, z = positions_for(sid, i)
            lines.append(f"I/OpenViewXR: OV_TEST t={t:.3f} step={index} pos={x:.4f},{y:.4f},{z:.4f} "
                         f"yaw=0.0 pitch=0.0 flags=VT")
            t += 1 / 15
    return lines


def ideal(step, i):
    if step == "forward":
        return 0.0, 0.0, -i / 19
    if step in ("still_forward", "turn", "still_end"):
        return 0.0, 0.0, -1.0 if step != "still_end" else -1.0
    return 0.0, 0.0, 0.0


class AnalyseTest(unittest.TestCase):
    def report(self, fn):
        return analyze.analyse(*analyze.parse(synthetic(fn)))

    def step(self, report, sid):
        return next(e for e in report["steps"] if e["id"] == sid)

    def test_ideal_walk_grades_well(self):
        r = self.report(ideal)
        self.assertEqual(self.step(r, "forward")["grade"], "BIEN")
        self.assertAlmostEqual(self.step(r, "forward")["scale"], 1.0, places=2)
        self.assertEqual(self.step(r, "still_start")["drift_m"], 0.0)
        self.assertEqual(r["jumps"], [])
        self.assertEqual(r["tracked_fraction"], 1.0)

    def test_short_scale_and_wrong_direction_are_caught(self):
        def short_sideways(step, i):
            if step == "forward":
                return 0.6 * i / 19, 0.0, 0.0
            if step == "still_start":
                return 0.0, 0.0, 0.0
            return 0.6, 0.0, 0.0
        e = self.step(self.report(short_sideways), "forward")
        self.assertAlmostEqual(e["scale"], 0.6, places=2)
        self.assertGreater(e["direction_error_deg"], 80)
        self.assertEqual(e["grade"], "MAL")

    def test_a_jump_while_still_is_reported(self):
        def jumpy(step, i):
            x, y, z = ideal(step, i)
            return (x + 2.0, y, z) if step == "still_start" and i >= 10 else (x, y, z)
        r = self.report(jumpy)
        self.assertTrue(any(j["step"] == "still_start" for j in r["jumps"]))
        self.assertEqual(self.step(r, "still_start")["grade"], "MAL")

    def test_moving_while_turning_is_reported(self):
        def wobble(step, i):
            x, y, z = ideal(step, i)
            return (x + (0.1 if i % 2 else -0.1), y, z) if step == "turn" else (x, y, z)
        self.assertEqual(self.step(self.report(wobble), "turn")["grade"], "MAL")

    def test_render_mentions_every_step(self):
        text = analyze.render(self.report(ideal))
        for sid, _, _ in STEPS:
            self.assertIn(sid, text)


if __name__ == "__main__":
    unittest.main()
