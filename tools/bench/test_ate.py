# Copyright 2026, OpenView contributors.
# SPDX-License-Identifier: BSL-1.0
"""Checks ate.py against trajectories whose error is known by construction."""

import numpy as np

from ate import align, evaluate


def rotation_about_z(angle):
    c, s = np.cos(angle), np.sin(angle)
    return np.array([[c, -s, 0.0], [s, c, 0.0], [0.0, 0.0, 1.0]])


def spiral(n=400):
    t = np.linspace(0, 10e9, n)
    u = t / 1e9
    return t, np.stack([np.cos(u), np.sin(u), 0.1 * u], axis=1)


def test_a_moved_copy_of_the_truth_has_no_error():
    t, truth = spiral()
    moved = (rotation_about_z(1.1) @ truth.T).T + np.array([4.0, -2.0, 7.0])

    result = evaluate(t, moved, t, truth)

    assert result["ate_rmse_m"] < 1e-9
    assert abs(result["coverage"] - 1.0) < 1e-9


def test_known_noise_comes_back_as_the_error():
    t, truth = spiral(4000)
    noise = np.random.default_rng(0).normal(0.0, 0.02, truth.shape)

    result = evaluate(t, truth + noise, t, truth)

    # Three independent axes of sigma 0.02 give an RMS distance of 0.02 * sqrt(3).
    assert abs(result["ate_rmse_m"] - 0.02 * np.sqrt(3)) < 0.002


def test_wrong_scale_counts_as_error_unless_scale_is_fitted():
    t, truth = spiral()

    assert evaluate(t, 1.2 * truth, t, truth)["ate_rmse_m"] > 0.05

    fitted = evaluate(t, 1.2 * truth, t, truth, with_scale=True)
    assert fitted["ate_rmse_m"] < 1e-9
    assert abs(fitted["scale"] - 1 / 1.2) < 1e-9


def test_a_mirror_image_is_not_accepted_as_a_match():
    _, truth = spiral()
    mirrored = truth * np.array([1.0, 1.0, -1.0])

    _, rotation, _ = align(mirrored, truth)

    assert np.linalg.det(rotation) > 0


def test_tracking_that_stops_early_shows_in_coverage():
    t, truth = spiral()

    result = evaluate(t[:100], truth[:100], t, truth)

    assert 0.2 < result["coverage"] < 0.3


def test_too_little_overlap_is_refused():
    t, truth = spiral()
    assert evaluate(t[:2], truth[:2], t, truth) is None


if __name__ == "__main__":
    for name, fn in list(globals().items()):
        if name.startswith("test_"):
            fn()
            print("ok", name)
