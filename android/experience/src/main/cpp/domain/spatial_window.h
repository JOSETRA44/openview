// Copyright 2026, OpenView contributors. SPDX-License-Identifier: BSL-1.0
#pragma once
// Windows that stay where the user puts them, and the grab that moves them.
// Pure arithmetic: no OpenXR, GL, JNI or Android, so it is tested on the host.
//
// Space: the experience's recentred LOCAL space, metres, +Y up, the user
// started at the origin looking down -Z. A window is a vertical rectangle;
// `yaw` turns it about +Y, and at yaw 0 its front (+Z) faces a viewer at the
// origin when the window sits on -Z. This matches the renderer's panelModel.

#include "../gaze_interaction.h"

#include <algorithm>
#include <cmath>

namespace openview {

struct WindowPose {
    float x, y, z, yaw;
};

//! Yaw that turns a window at @p window to face a viewer at @p viewer.
inline float yawFacing(GazeVector window, GazeVector viewer) {
    const float dx = viewer.x - window.x, dz = viewer.z - window.z;
    if (std::fabs(dx) < 1e-6f && std::fabs(dz) < 1e-6f) return 0.0f;
    return std::atan2(dx, dz);
}

//! A window @p distance metres along the gaze, upright and facing the viewer.
inline WindowPose placeAlongGaze(GazeVector head, GazeVector direction, float distance) {
    const float length = std::sqrt(direction.x * direction.x + direction.y * direction.y +
                                   direction.z * direction.z);
    const GazeVector d = length > 1e-6f ? GazeVector{direction.x / length, direction.y / length, direction.z / length}
                                        : GazeVector{0.0f, 0.0f, -1.0f};
    const GazeVector at{head.x + d.x * distance, head.y + d.y * distance, head.z + d.z * distance};
    return {at.x, at.y, at.z, yawFacing(at, head)};
}

//! Shortest signed difference between two angles, in (-pi, pi].
inline float angleDelta(float from, float to) {
    float d = std::fmod(to - from, 6.28318530718f);
    if (d > 3.14159265359f) d -= 6.28318530718f;
    if (d <= -3.14159265359f) d += 6.28318530718f;
    return d;
}

/**
 * Moving a window with the gaze, like a pinch-and-drag on a headset: while
 * held, the window rides the gaze ray at the distance it was grabbed from,
 * keeps facing the user, and eases towards the ray instead of snapping to it
 * (head tremor would otherwise shake it). The distance can be pushed and
 * pulled while holding.
 */
class WindowGrab {
public:
    static constexpr float kMinDistance = 0.5f;
    static constexpr float kMaxDistance = 5.0f;
    //! Seconds for the window to cover ~63 % of the way to the gaze ray.
    static constexpr float kFollowSeconds = 0.07f;

    void begin(const WindowPose &current, GazeVector head) {
        pose_ = current;
        const float dx = current.x - head.x, dy = current.y - head.y, dz = current.z - head.z;
        distance_ = std::clamp(std::sqrt(dx * dx + dy * dy + dz * dz), kMinDistance, kMaxDistance);
        active_ = true;
    }

    //! The window's pose this frame. @p dt is the time since the previous update.
    WindowPose update(GazeVector head, GazeVector direction, double dt) {
        if (!active_) return pose_;
        const WindowPose target = placeAlongGaze(head, direction, distance_);
        const float a = dt > 0 ? 1.0f - std::exp(-static_cast<float>(dt) / kFollowSeconds) : 0.0f;
        pose_.x += (target.x - pose_.x) * a;
        pose_.y += (target.y - pose_.y) * a;
        pose_.z += (target.z - pose_.z) * a;
        pose_.yaw += angleDelta(pose_.yaw, target.yaw) * a;
        return pose_;
    }

    //! Pushes the window away (positive) or pulls it closer (negative).
    void changeDistance(float metres) { distance_ = std::clamp(distance_ + metres, kMinDistance, kMaxDistance); }

    WindowPose end() {
        active_ = false;
        return pose_;
    }

    bool active() const { return active_; }
    float distance() const { return distance_; }

private:
    WindowPose pose_{0, 0, -2, 0};
    float distance_ = 2.0f;
    bool active_ = false;
};

} // namespace openview
