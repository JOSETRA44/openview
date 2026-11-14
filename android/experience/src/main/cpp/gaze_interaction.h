// Copyright 2026, OpenView contributors. SPDX-License-Identifier: BSL-1.0
#pragma once

#include <cmath>
#include <limits>

namespace openview {

//! Minimal 3D vector for gaze math; an aggregate of floats, like the pose
//! matrix elements it is built from.
struct GazeVector {
    float x, y, z;
};

//! Distance from the ray origin to the sphere surface, in world units. The
//! direction need not be normalized. Returns +infinity when the ray misses,
//! when the sphere lies behind the origin, or when the direction is zero.
//! An origin inside the sphere returns the exit distance.
inline float raySphere(GazeVector origin, GazeVector direction, GazeVector center, float radius) {
    const float length = std::sqrt(direction.x * direction.x + direction.y * direction.y +
                                   direction.z * direction.z);
    if (!(length > 0.0f) || !std::isfinite(length)) {
        return std::numeric_limits<float>::infinity();
    }
    const GazeVector d{direction.x / length, direction.y / length, direction.z / length};
    const GazeVector oc{origin.x - center.x, origin.y - center.y, origin.z - center.z};
    const float b = oc.x * d.x + oc.y * d.y + oc.z * d.z;
    const float c = oc.x * oc.x + oc.y * oc.y + oc.z * oc.z - radius * radius;
    const float disc = b * b - c;
    if (disc < 0.0f) {
        return std::numeric_limits<float>::infinity();
    }
    const float root = std::sqrt(disc);
    float t = -b - root;
    if (t < 0.0f) {
        t = -b + root; // origin inside the sphere: take the exit point
    }
    if (t < 0.0f) {
        return std::numeric_limits<float>::infinity();
    }
    return t;
}

//! Dwell activation for gaze selection. Looking at one object for
//! kDwellSeconds activates it, exactly once; a trigger press activates the
//! current target immediately. Looking away, changing object, a clock
//! discontinuity, a non-finite timestamp or reset() rearm the timer, and
//! empty space (a negative id) can never activate.
class GazeDwell {
public:
    static constexpr double kDwellSeconds = 1.25;

    int target = -1;
    float progress = 0.0f;

    bool update(int id, double now, bool trigger = false) {
        if (!std::isfinite(now)) {
            reset();
            return false;
        }
        if (id < 0) {
            reset();
            return false;
        }
        if (id != target || now < start_) {
            target = id;
            start_ = now;
            progress = 0.0f;
            fired_ = false;
            return false;
        }
        if (fired_) {
            return false;
        }
        progress = static_cast<float>((now - start_) / kDwellSeconds);
        if (trigger || progress >= 1.0f) {
            progress = 1.0f;
            fired_ = true;
            return true;
        }
        return false;
    }

    void reset() {
        target = -1;
        progress = 0.0f;
        start_ = 0.0;
        fired_ = false;
    }

private:
    double start_ = 0.0;
    bool fired_ = false;
};

} // namespace openview
