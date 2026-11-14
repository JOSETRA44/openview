// Copyright 2026, OpenView contributors.
// SPDX-License-Identifier: BSL-1.0
#pragma once
#include <cstdint>
#include <memory>
#include <optional>
#include <string>
#include <vector>
#include "vit/vit_interface.h"

namespace openview::xrslam {
struct Image {
    int64_t timestamp;
    uint32_t width, height;
    std::vector<uint8_t> pixels;
};

// Internal implementation seam for deterministic contract tests. The exported
// boundary remains the standard VIT API; this is not a runtime tracking API.
class Backend {
public:
    virtual ~Backend() = default;
    virtual uint32_t width() const = 0;
    virtual uint32_t height() const = 0;
    virtual void start() = 0;
    virtual void imu(const vit_imu_sample_t &) = 0;
    virtual std::optional<vit_pose_data_t> image(const Image &) = 0;
};
std::unique_ptr<Backend> make_backend(const std::string &configuration);
} // namespace openview::xrslam
