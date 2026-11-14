// Copyright 2026, OpenView contributors.
// SPDX-License-Identifier: BSL-1.0
#define VIT_INTERFACE_IMPLEMENTATION
#include "backend.h"
#include <atomic>
#include <chrono>
#include <iostream>
#include <limits>
#include <stdexcept>
#include <thread>

using namespace std::chrono_literals;
static std::atomic<int> images_seen{0}, imu_seen{0};
static std::atomic<int64_t> last_imu{0};
namespace openview::xrslam {
class TestBackend : public Backend {
    std::string mode;
public:
    explicit TestBackend(std::string m) : mode(std::move(m)) {}
    uint32_t width() const override { return 2; }
    uint32_t height() const override { return 2; }
    void start() override { images_seen = 0; imu_seen = 0; last_imu = 0; }
    void imu(const vit_imu_sample_t &s) override { ++imu_seen; last_imu = s.timestamp; }
    std::optional<vit_pose_data_t> image(const Image &s) override {
        ++images_seen;
        if (mode == "throw") throw std::runtime_error("injected backend failure");
        if (last_imu > s.timestamp) throw std::runtime_error("future IMU processed too early");
        vit_pose_data_t p{}; p.timestamp = s.timestamp; p.ow = 1;
        p.px = float(s.pixels[0]); p.py = float(s.pixels[2]);
        if (mode == "invalid") p.px = std::numeric_limits<float>::quiet_NaN();
        return p;
    }
};
std::unique_ptr<Backend> make_backend(const std::string &configuration) {
    if (configuration == "missing") throw std::runtime_error("missing calibration");
    return std::make_unique<TestBackend>(configuration);
}
}

static void check(bool v, const char *message) { if (!v) throw std::runtime_error(message); }
template<class Predicate> static void wait_for(Predicate test) {
    for (int n = 0; n < 2000; ++n) { if (test()) return; std::this_thread::sleep_for(1ms); }
    throw std::runtime_error("worker deadline exceeded");
}
struct Tracker {
    vit_tracker_t *t = nullptr;
    explicit Tracker(const char *mode = "normal") {
        vit_config_t config{mode, 1, 1, false};
        check(vit_tracker_create(&config, &t) == VIT_SUCCESS, "create");
        check(vit_tracker_start(t) == VIT_SUCCESS, "start");
    }
    ~Tracker() { vit_tracker_destroy(t); }
};
static vit_img_sample_t image(uint8_t *data, int64_t ns) {
    return {0, ns, data, 2, 2, 3, 5, VIT_IMAGE_FORMAT_L8, 0, nullptr};
}
int main() {
    try {
        uint32_t a, b, c;
        check(vit_api_get_version(&a, &b, &c) == VIT_SUCCESS && a == 2, "version");
        check(vit_api_get_version(nullptr, &b, &c) == VIT_ERROR_INVALID_VALUE, "null version");
        vit_tracker_t *bad = reinterpret_cast<vit_tracker_t *>(1);
        vit_config_t config{"missing", 1, 1, false};
        check(vit_tracker_create(&config, &bad) == VIT_ERROR_INVALID_VALUE && !bad, "configuration failure clears output");
        config = {"normal", 2, 1, false};
        check(vit_tracker_create(&config, &bad) == VIT_ERROR_INVALID_VALUE, "reject stereo");
        {
            Tracker t;
            bool supported = true;
            check(vit_tracker_has_image_format(t.t, VIT_IMAGE_FORMAT_L16, &supported) == VIT_SUCCESS && !supported, "reject 16-bit format");
            vit_tracker_extension_set_t extensions{};
            check(vit_tracker_get_supported_extensions(t.t, &extensions) == VIT_SUCCESS && !extensions.has_add_camera_calibration, "honest extensions");
            uint8_t pixels[]{11, 12, 99, 21, 22};
            auto frame = image(pixels, 20);
            auto invalid = frame; invalid.size = 4;
            check(vit_tracker_push_img_sample(t.t, &invalid) == VIT_ERROR_INVALID_VALUE, "short last row");
            invalid = frame; invalid.mask_count = 1;
            check(vit_tracker_push_img_sample(t.t, &invalid) == VIT_ERROR_INVALID_VALUE, "unsupported mask");
            check(vit_tracker_push_img_sample(t.t, &frame) == VIT_SUCCESS, "padded image accepted");
            pixels[0] = pixels[3] = 0; // caller can reuse storage immediately
            check(vit_tracker_push_img_sample(t.t, &frame) == VIT_ERROR_INVALID_VALUE, "duplicate image rejected");
            vit_pose_t *pose = nullptr;
            check(vit_tracker_pop_pose(t.t, &pose) == VIT_SUCCESS && !pose, "no pose before IMU watermark");
            vit_imu_sample_t imu{10, 0, 0, 9.81f, 0, 0, 0};
            check(vit_tracker_push_imu_sample(t.t, &imu) == VIT_SUCCESS, "imu accepted");
            check(vit_tracker_push_imu_sample(t.t, &imu) == VIT_ERROR_INVALID_VALUE, "duplicate imu rejected");
            imu.timestamp = 30;
            check(vit_tracker_push_imu_sample(t.t, &imu) == VIT_SUCCESS, "watermark accepted");
            wait_for([&] { return vit_tracker_pop_pose(t.t, &pose) == VIT_SUCCESS && pose; });
            vit_pose_data_t data{};
            check(vit_pose_get_data(pose, &data) == VIT_SUCCESS && data.timestamp == 20 && data.px == 11 && data.py == 21,
                  "owned image, row stride and timestamp preserved");
            check(imu_seen == 1 && last_imu == 10, "future IMU held for next image");
            vit_pose_destroy(pose);
            imu.timestamp = 40; imu.ax = std::numeric_limits<float>::infinity();
            check(vit_tracker_push_imu_sample(t.t, &imu) == VIT_ERROR_INVALID_VALUE, "nonfinite imu rejected");
            check(vit_tracker_stop(t.t) == VIT_SUCCESS, "stop");
            check(vit_tracker_push_img_sample(t.t, &frame) == VIT_ERROR_INVALID_VALUE, "stopped rejects input");
            check(vit_tracker_pop_pose(t.t, &pose) == VIT_SUCCESS && !pose, "stop clears poses");
            check(vit_tracker_reset(t.t) == VIT_SUCCESS && vit_tracker_start(t.t) == VIT_SUCCESS, "reset and restart");
            frame.timestamp = 1;
            check(vit_tracker_push_img_sample(t.t, &frame) == VIT_SUCCESS, "reset accepts new timestamp origin");
        }
        {
            Tracker t;
            uint8_t pixels[]{1, 2, 0, 3, 4};
            for (int i = 0; i < 16; ++i) {
                auto frame = image(pixels, i);
                check(vit_tracker_push_img_sample(t.t, &frame) == VIT_SUCCESS, "bounded queue fills");
            }
            auto frame = image(pixels, 16);
            check(vit_tracker_push_img_sample(t.t, &frame) == VIT_ERROR_ALLOCATION_FAILURE, "overflow fails instead of dropping sensors");
            bool running = true; vit_tracker_is_running(t.t, &running);
            check(!running, "overflow stops tracking");
            vit_pose_t *pose = nullptr;
            check(vit_tracker_pop_pose(t.t, &pose) == VIT_ERROR_INVALID_VALUE && !pose, "overflow cannot return stale pose");
        }
        for (const char *mode : {"throw", "invalid"}) {
            Tracker t(mode);
            uint8_t pixels[]{1, 2, 0, 3, 4}; auto frame = image(pixels, 10);
            check(vit_tracker_push_img_sample(t.t, &frame) == VIT_SUCCESS, "failure image queued");
            vit_imu_sample_t imu{10, 0, 0, 9.81f, 0, 0, 0};
            check(vit_tracker_push_imu_sample(t.t, &imu) == VIT_SUCCESS, "failure imu queued");
            wait_for([&] { bool running = true; vit_tracker_is_running(t.t, &running); return !running; });
            vit_pose_t *pose = nullptr;
            check(vit_tracker_pop_pose(t.t, &pose) == VIT_ERROR_INVALID_VALUE && !pose, "bad backend cannot leak pose");
        }
        std::cout << "VIT contract passed: ownership, ordering, bounds, lifecycle, failure isolation\n";
        return 0;
    } catch (const std::exception &e) { std::cerr << e.what() << '\n'; return 1; }
}
