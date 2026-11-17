// Copyright 2026, OpenView contributors. SPDX-License-Identifier: BSL-1.0
// Host test: c++ -std=c++17 -I android/experience/src/main/cpp android/experience/tests/spatial_window_test.cpp
#include "domain/spatial_window.h"

#include <cmath>
#include <cstdio>

using namespace openview;

static int failures = 0;
static void near(const char *what, float got, float want, float tolerance = 1e-4f) {
    if (std::fabs(got - want) > tolerance) {
        std::printf("FAIL %s: %f != %f\n", what, got, want);
        ++failures;
    }
}

int main() {
    // The renderer's existing layout: a window ahead at 2 m faces the viewer with yaw 0,
    // and the status column to the right turns towards them (about -0.56 rad).
    near("ahead", yawFacing({0, 0, -2}, {0, 0, 0}), 0.0f);
    near("right", yawFacing({1.15f, 0, -1.83f}, {0, 0, 0}), std::atan2(-1.15f, 1.83f));

    // Placing along the gaze: 1.5 m straight ahead, then 1.5 m to the left.
    auto ahead = placeAlongGaze({0, 1.6f, 0}, {0, 0, -3}, 1.5f);
    near("ahead z", ahead.z, -1.5f);
    near("ahead y", ahead.y, 1.6f);
    near("ahead yaw", ahead.yaw, 0.0f);
    auto left = placeAlongGaze({0, 0, 0}, {-1, 0, 0}, 1.5f);
    near("left x", left.x, -1.5f);
    near("left yaw faces back", left.yaw, std::atan2(1.5f, 0.0f));

    // A grab keeps the distance it started from and follows the gaze.
    WindowGrab grab;
    grab.begin({0, 0, -2, 0}, {0, 0, 0});
    near("grab distance", grab.distance(), 2.0f);
    WindowPose pose{};
    for (int i = 0; i < 120; ++i) pose = grab.update({0, 0, 0}, {1, 0, 0}, 1.0 / 60);  // look right for 2 s
    near("follows x", pose.x, 2.0f, 1e-3f);
    near("follows z", pose.z, 0.0f, 1e-3f);
    near("turns to face", pose.yaw, std::atan2(-2.0f, 0.0f), 1e-3f);

    // It eases instead of snapping: one frame moves only part of the way.
    WindowGrab easing;
    easing.begin({0, 0, -2, 0}, {0, 0, 0});
    auto first = easing.update({0, 0, 0}, {1, 0, 0}, 1.0 / 60);
    if (!(first.x > 0.1f && first.x < 1.0f)) { std::printf("FAIL easing x=%f\n", first.x); ++failures; }

    // Pushing and pulling is clamped.
    grab.changeDistance(10);
    near("max", grab.distance(), WindowGrab::kMaxDistance);
    grab.changeDistance(-10);
    near("min", grab.distance(), WindowGrab::kMinDistance);
    grab.end();
    if (grab.active()) { std::printf("FAIL still active\n"); ++failures; }

    // Turning the short way round: from just under +pi to just over -pi is a small step.
    near("wrap", angleDelta(3.1f, -3.1f), 6.28318530718f - 6.2f, 1e-4f);

    // Gaze-only placing: turning keeps the window moving, a still head places it.
    GazeSettle settle;
    bool placed = false;
    for (int i = 0; i < 60 && !placed; ++i) {  // turning at ~1 rad/s for 1 s
        const float a = i / 60.0f;
        placed = settle.update({std::sin(a), 0, -std::cos(a)}, 1.0 / 60);
    }
    if (placed) { std::printf("FAIL placed while turning\n"); ++failures; }
    int frames = 0;
    while (!placed && frames < 300) { placed = settle.update({std::sin(1.0f), 0, -std::cos(1.0f)}, 1.0 / 60); ++frames; }
    if (!placed) { std::printf("FAIL never placed\n"); ++failures; }
    near("settles after ~1.2 s", frames / 60.0f, 1.2f, 0.05f);
    near("progress full", settle.progress(), 1.0f);
    settle.reset();
    near("progress reset", settle.progress(), 0.0f);
    // A head held still from the start still waits for the minimum move time.
    for (int i = 0; i < 30; ++i) placed = settle.update({0, 0, -1}, 1.0 / 60);
    if (placed) { std::printf("FAIL placed before the minimum\n"); ++failures; }

    if (failures == 0) std::printf("spatial window: ok\n");
    return failures == 0 ? 0 : 1;
}
