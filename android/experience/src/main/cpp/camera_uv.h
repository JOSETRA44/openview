// Copyright 2026, OpenView contributors. SPDX-License-Identifier: BSL-1.0
#pragma once
#include <algorithm>
#include <array>
#include <cmath>

struct CameraUv {
    float cropX, cropY, cosine, sine;
    std::array<float, 2> sample(float x, float y) const {
        x=(x-.5f)*cropX; y=(y-.5f)*cropY;
        return {.5f+cosine*x-sine*y, .5f+sine*x+cosine*y};
    }
};

// Camera2's SurfaceTexture already transforms sensor buffers to natural orientation.
// Android rotates that view by -displayDegrees in Y-down coordinates. GL samples the
// inverse in Y-up coordinates: both inversions cancel, leaving the same negative angle.
inline CameraUv cameraUv(float orientedAspect, float eyeAspect, int displayDegrees) {
    const float angle=-displayDegrees*3.14159265358979323846f/180.f;
    return {std::min(1.f,eyeAspect/orientedAspect), std::min(1.f,orientedAspect/eyeAspect),
            std::cos(angle),std::sin(angle)};
}
