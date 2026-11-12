# Copyright 2026, OpenView contributors.
# SPDX-License-Identifier: BSL-1.0
"""Checks the estimators in analyze.py against signals with a known answer.

    python -m pytest tools/sensor-recorder      (or run this file directly)
"""

import numpy as np

from analyze import estimate_time_offset, image_shift, interval_stats


def test_image_shift_recovers_a_known_translation():
    rng = np.random.default_rng(0)
    a = rng.integers(0, 255, size=(120, 160)).astype(np.uint8)
    b = np.roll(a, shift=(5, -9), axis=(0, 1))  # 5 px down, 9 px left

    assert image_shift(a, b) == (-9.0, 5.0)


def test_time_offset_recovers_a_known_delay():
    # A wandering motion signal, sampled like a 200 Hz gyro and a 30 Hz camera
    # whose clock runs 23 ms ahead of the gyro's.
    rng = np.random.default_rng(1)
    t = np.arange(0, 10e9, 1e6)
    signal = np.convolve(rng.standard_normal(len(t)), np.ones(150) / 150, mode="same")

    gyro_t = t[::5]
    cam_true_t = t[::33]
    cam_t = cam_true_t + 23e6

    offset_ms, score = estimate_time_offset(gyro_t, signal[::5], cam_t, signal[::33])

    assert abs(offset_ms - (-23.0)) <= 1.0
    assert score > 0.9


def test_time_offset_declines_when_the_capture_is_too_short():
    t = np.arange(0, 0.5e9, 5e6)
    assert estimate_time_offset(t, np.sin(t), t, np.sin(t)) == (None, 0.0)


def test_interval_stats_counts_dropped_samples():
    t = np.arange(0, 1000) * 5e6
    t = np.delete(t, [100, 101, 500])  # one double drop, one single

    stats = interval_stats(t)

    assert round(stats["rate_hz"]) == 200
    assert stats["gaps"] == 2
    assert round(stats["interval_max_ms"]) == 15


if __name__ == "__main__":
    for name, fn in list(globals().items()):
        if name.startswith("test_"):
            fn()
            print("ok", name)
