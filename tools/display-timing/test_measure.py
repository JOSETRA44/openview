# Copyright 2026, OpenView contributors.
# SPDX-License-Identifier: BSL-1.0
"""Checks the parsers in measure.py against text shaped like what the phone prints."""

from measure import late_frame_fraction, parse_driver_stats, parse_timestats, percentile, summarize

TIMESTATS = """SurfaceFlingerTimeStats:
totalFrames = 9000

layerName = SurfaceView[Monado](BLAST)#11
packageName = dev.openview.runtime
totalFrames = 14
droppedFrames = 0
averageFPS = 62.500
present2present histogram is as below:
0ms=0 16ms=14 33ms=0
post2present histogram is as below:
0ms=0 31ms=14

layerName = SurfaceView[Monado](BLAST)#12
packageName = dev.openview.runtime
totalFrames = 1000
droppedFrames = 20
averageFPS = 59.900
present2present histogram is as below:
0ms=0 16ms=940 33ms=60 50ms=0
post2present histogram is as below:
0ms=0 29ms=50 30ms=350 31ms=400 32ms=150 40ms=50

layerName = StatusBar#3
totalFrames = 5000
droppedFrames = 0
averageFPS = 60.000
present2present histogram is as below:
16ms=5000
post2present histogram is as below:
10ms=5000
"""

LOG = """11-12 14:22:35.969 1 2 I monado.android_stats_report_locked:  INFO [android_stats_report_locked] gyro 204 Hz, sample age avg 844 us max 2319 us; poses 33, horizon min -4 avg 14951 max 23698 us, limited 0
11-12 14:22:36.969 1 2 I monado.android_stats_report_locked:  INFO [android_stats_report_locked] gyro 204 Hz, sample age avg 1400 us max 3680 us; poses 100, horizon min 34000 avg 46000 max 61000 us, limited 0
11-12 14:22:37.969 1 2 I monado.android_stats_report_locked:  INFO [android_stats_report_locked] gyro 202 Hz, sample age avg 1600 us max 4624 us; poses 300, horizon min 36000 avg 48000 max 63000 us, limited 40
"""


def test_percentile_walks_the_histogram():
    histogram = [(10, 1), (20, 8), (30, 1)]
    assert percentile(histogram, 0.05) == 10
    assert percentile(histogram, 0.50) == 20
    assert percentile(histogram, 0.95) == 30


def test_timestats_picks_the_runtime_layer_with_the_session_in_it():
    stats = parse_timestats(TIMESTATS)

    assert stats["frames"] == 1000
    assert stats["dropped"] == 20
    assert stats["queue_to_display_ms"] == {"p5": 29, "p50": 31, "p95": 32}


def test_timestats_without_the_runtime_layer_is_no_result():
    assert parse_timestats(TIMESTATS.replace("Monado", "Other")) is None


def test_late_frames_are_those_a_whole_refresh_behind():
    intervals = [(16, 940), (33, 60)]
    assert late_frame_fraction(intervals, 1000 / 60) == 0.06
    # The same 16 ms cadence on a 120 Hz panel means every frame took two refreshes.
    assert late_frame_fraction(intervals, 1000 / 120) == 1.0


def test_driver_stats_skip_the_startup_window_and_weight_by_poses():
    stats = parse_driver_stats(LOG)

    assert stats["seconds"] == 2
    assert stats["gyro_hz"] == 203
    assert stats["horizon_min_ms"] == 34.0
    assert stats["horizon_max_ms"] == 63.0
    assert stats["horizon_avg_ms"] == (46000 * 100 + 48000 * 300) / 400 / 1000
    assert stats["limited_fraction"] == 0.1


def test_driver_stats_absent_when_the_driver_logged_nothing():
    assert parse_driver_stats("unrelated line\n") is None


def test_summary_expresses_latency_in_refresh_periods():
    result = summarize(60, parse_timestats(TIMESTATS), parse_driver_stats(LOG))

    assert abs(result["latency_in_periods"] - 31 / (1000 / 60)) < 1e-9
    assert result["dropped_fraction"] == 0.02
    assert result["late_frames"] == 0.06


if __name__ == "__main__":
    for name, fn in list(globals().items()):
        if name.startswith("test_"):
            fn()
            print("ok", name)
