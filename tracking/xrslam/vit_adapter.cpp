// Copyright 2026, OpenView contributors.
// SPDX-License-Identifier: BSL-1.0
#define VIT_INTERFACE_IMPLEMENTATION
#include "backend.h"
#include <cmath>
#include <condition_variable>
#include <cstring>
#include <deque>
#include <mutex>
#include <thread>

using openview::xrslam::Backend;
using openview::xrslam::Image;
struct vit_pose { vit_pose_data_t data; };
struct vit_tracker {
    std::unique_ptr<Backend> backend;
    mutable std::mutex mutex;
    std::condition_variable changed;
    std::thread worker;
    std::deque<vit_imu_sample_t> imu;
    std::deque<Image> images;
    std::deque<vit_pose_data_t> poses;
    int64_t last_imu = -1, last_image = -1, last_pose = -1;
    bool running = false, failed = false;
};

namespace {
constexpr size_t max_imu = 4096, max_images = 16, max_poses = 64;
bool finite_imu(const vit_imu_sample_t &s) {
    return std::isfinite(s.ax) && std::isfinite(s.ay) && std::isfinite(s.az) &&
           std::isfinite(s.wx) && std::isfinite(s.wy) && std::isfinite(s.wz);
}
bool finite_pose(const vit_pose_data_t &p) {
    const double norm = double(p.ox)*p.ox + double(p.oy)*p.oy + double(p.oz)*p.oz + double(p.ow)*p.ow;
    return p.timestamp >= 0 && std::isfinite(p.px) && std::isfinite(p.py) && std::isfinite(p.pz) &&
        std::isfinite(p.vx) && std::isfinite(p.vy) && std::isfinite(p.vz) && std::isfinite(norm) &&
        std::abs(norm - 1.0) < 0.01;
}
void fail(vit_tracker *t) {
    t->failed = true;
    t->running = false;
    t->poses.clear();
    t->changed.notify_all();
}
void run(vit_tracker *t) noexcept {
    try {
        t->backend->start();
        for (;;) {
            Image image;
            std::deque<vit_imu_sample_t> samples;
            {
                std::unique_lock<std::mutex> lock(t->mutex);
                // An IMU watermark brackets each image. Do not infer timestamps
                // or process a camera frame before its inertial samples arrive.
                t->changed.wait(lock, [&] {
                    return !t->running || (!t->images.empty() && t->last_imu >= t->images.front().timestamp);
                });
                if (!t->running) return;
                image = std::move(t->images.front());
                t->images.pop_front();
                while (!t->imu.empty() && t->imu.front().timestamp <= image.timestamp) {
                    samples.push_back(t->imu.front());
                    t->imu.pop_front();
                }
            }
            for (const auto &sample : samples) t->backend->imu(sample);
            const auto pose = t->backend->image(image);
            std::lock_guard<std::mutex> lock(t->mutex);
            if (!t->running) return;
            if (pose) {
                if (!finite_pose(*pose) || pose->timestamp > image.timestamp) { fail(t); return; }
                if (pose->timestamp <= t->last_pose) continue; // upstream can repeat its latest pose
                t->last_pose = pose->timestamp;
                // A slow consumer loses old results, never sensor samples.
                if (t->poses.size() == max_poses) t->poses.pop_front();
                t->poses.push_back(*pose);
            }
        }
    } catch (...) {
        std::lock_guard<std::mutex> lock(t->mutex);
        fail(t); // exceptions never cross the C ABI or escape the worker
    }
}
}

extern "C" {
vit_result_t vit_api_get_version(uint32_t *a, uint32_t *b, uint32_t *c) {
    if (!a || !b || !c) return VIT_ERROR_INVALID_VALUE;
    *a = VIT_HEADER_VERSION_MAJOR; *b = VIT_HEADER_VERSION_MINOR; *c = VIT_HEADER_VERSION_PATCH;
    return VIT_SUCCESS;
}
vit_result_t vit_tracker_create(const vit_config_t *c, vit_tracker_t **out) {
    if (out) *out = nullptr;
    if (!out || !c || !c->file || !*c->file || c->cam_count != 1 || c->imu_count != 1 || c->show_ui)
        return VIT_ERROR_INVALID_VALUE;
    try {
        auto t = std::make_unique<vit_tracker>();
        t->backend = openview::xrslam::make_backend(c->file);
        if (!t->backend || !t->backend->width() || !t->backend->height()) return VIT_ERROR_INVALID_VALUE;
        *out = t.release();
        return VIT_SUCCESS;
    } catch (const std::bad_alloc &) { return VIT_ERROR_ALLOCATION_FAILURE; }
      catch (...) { return VIT_ERROR_INVALID_VALUE; }
}
vit_result_t vit_tracker_stop(vit_tracker_t *t) {
    if (!t) return VIT_ERROR_INVALID_VALUE;
    {
        std::lock_guard<std::mutex> lock(t->mutex);
        t->running = false;
        t->poses.clear();
        t->changed.notify_all();
    }
    if (t->worker.joinable()) t->worker.join();
    return VIT_SUCCESS;
}
void vit_tracker_destroy(vit_tracker_t *t) { if (t) { vit_tracker_stop(t); delete t; } }
vit_result_t vit_tracker_reset(vit_tracker_t *t) {
    if (!t) return VIT_ERROR_INVALID_VALUE;
    vit_tracker_stop(t);
    std::lock_guard<std::mutex> lock(t->mutex);
    t->imu.clear(); t->images.clear(); t->poses.clear();
    t->last_imu = t->last_image = t->last_pose = -1;
    t->failed = false;
    return VIT_SUCCESS;
}
vit_result_t vit_tracker_start(vit_tracker_t *t) {
    if (!t) return VIT_ERROR_INVALID_VALUE;
    std::lock_guard<std::mutex> lock(t->mutex);
    if (t->running || t->failed || t->worker.joinable()) return VIT_ERROR_INVALID_VALUE;
    // Each start initializes fresh upstream state; old samples must not leak.
    t->imu.clear(); t->images.clear(); t->poses.clear();
    t->last_imu = t->last_image = t->last_pose = -1;
    t->running = true;
    try { t->worker = std::thread(run, t); }
    catch (...) { t->running = false; return VIT_ERROR_ALLOCATION_FAILURE; }
    return VIT_SUCCESS;
}
vit_result_t vit_tracker_is_running(const vit_tracker_t *t, bool *out) {
    if (!t || !out) return VIT_ERROR_INVALID_VALUE;
    std::lock_guard<std::mutex> lock(t->mutex); *out = t->running; return VIT_SUCCESS;
}
vit_result_t vit_tracker_push_imu_sample(vit_tracker_t *t, const vit_imu_sample_t *s) {
    if (!t || !s || s->timestamp < 0 || !finite_imu(*s)) return VIT_ERROR_INVALID_VALUE;
    std::lock_guard<std::mutex> lock(t->mutex);
    if (!t->running || s->timestamp <= t->last_imu) return VIT_ERROR_INVALID_VALUE;
    if (t->imu.size() == max_imu) { fail(t); return VIT_ERROR_ALLOCATION_FAILURE; }
    try { t->imu.push_back(*s); } catch (...) { fail(t); return VIT_ERROR_ALLOCATION_FAILURE; }
    t->last_imu = s->timestamp; t->changed.notify_one(); return VIT_SUCCESS;
}
vit_result_t vit_tracker_push_img_sample(vit_tracker_t *t, const vit_img_sample_t *s) {
    if (!t || !s || s->timestamp < 0 || !s->data || s->cam_index != 0 || s->mask_count != 0 ||
        s->format != VIT_IMAGE_FORMAT_L8 || s->width != t->backend->width() ||
        s->height != t->backend->height() || s->stride < s->width ||
        uint64_t(s->size) < uint64_t(s->height - 1) * s->stride + s->width)
        return VIT_ERROR_INVALID_VALUE;
    try {
        // Own the image before returning; VIT does not transfer caller ownership.
        Image image{s->timestamp, s->width, s->height, std::vector<uint8_t>(size_t(s->width) * s->height)};
        for (uint32_t row = 0; row < s->height; ++row)
            std::memcpy(image.pixels.data() + size_t(row) * s->width, s->data + size_t(row) * s->stride, s->width);
        std::lock_guard<std::mutex> lock(t->mutex);
        if (!t->running || s->timestamp <= t->last_image) return VIT_ERROR_INVALID_VALUE;
        if (t->images.size() == max_images) { fail(t); return VIT_ERROR_ALLOCATION_FAILURE; }
        t->images.push_back(std::move(image)); t->last_image = s->timestamp;
        t->changed.notify_one(); return VIT_SUCCESS;
    } catch (...) { return VIT_ERROR_ALLOCATION_FAILURE; }
}
vit_result_t vit_tracker_pop_pose(vit_tracker_t *t, vit_pose_t **out) {
    if (out) *out = nullptr;
    if (!t) return VIT_ERROR_INVALID_VALUE;
    std::lock_guard<std::mutex> lock(t->mutex);
    if (t->failed) return VIT_ERROR_INVALID_VALUE;
    if (!t->running || t->poses.empty()) return VIT_SUCCESS;
    if (out) {
        try { *out = new vit_pose{t->poses.front()}; }
        catch (...) { return VIT_ERROR_ALLOCATION_FAILURE; }
    }
    t->poses.pop_front(); return VIT_SUCCESS;
}
void vit_pose_destroy(vit_pose_t *p) { delete p; }
vit_result_t vit_pose_get_data(const vit_pose_t *p, vit_pose_data_t *out) {
    if (!p || !out) return VIT_ERROR_INVALID_VALUE;
    *out = p->data; return VIT_SUCCESS;
}
vit_result_t vit_tracker_has_image_format(const vit_tracker_t *t, vit_image_format_t f, bool *out) {
    if (!t || !out) return VIT_ERROR_INVALID_VALUE;
    *out = f == VIT_IMAGE_FORMAT_L8; return VIT_SUCCESS;
}
vit_result_t vit_tracker_get_supported_extensions(const vit_tracker_t *t, vit_tracker_extension_set_t *out) {
    if (!t || !out) return VIT_ERROR_INVALID_VALUE;
    *out = {}; return VIT_SUCCESS;
}
vit_result_t vit_tracker_get_enabled_extensions(const vit_tracker_t *t, vit_tracker_extension_set_t *out) {
    return vit_tracker_get_supported_extensions(t, out);
}
vit_result_t vit_tracker_enable_extension(vit_tracker_t *, vit_tracker_extension_t, bool) { return VIT_ERROR_NOT_SUPPORTED; }
vit_result_t vit_tracker_add_imu_calibration(vit_tracker_t *, const vit_imu_calibration_t *) { return VIT_ERROR_NOT_SUPPORTED; }
vit_result_t vit_tracker_add_camera_calibration(vit_tracker_t *, const vit_camera_calibration_t *) { return VIT_ERROR_NOT_SUPPORTED; }
vit_result_t vit_tracker_get_timing_titles(const vit_tracker_t *, vit_tracker_timing_titles *) { return VIT_ERROR_NOT_SUPPORTED; }
vit_result_t vit_pose_get_timing(const vit_pose_t *, vit_pose_timing_t *) { return VIT_ERROR_NOT_SUPPORTED; }
vit_result_t vit_pose_get_features(const vit_pose_t *, uint32_t, vit_pose_features_t *) { return VIT_ERROR_NOT_SUPPORTED; }
}
