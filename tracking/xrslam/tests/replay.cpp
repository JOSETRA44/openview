// Copyright 2026, OpenView contributors.
// SPDX-License-Identifier: BSL-1.0
// EuRoC consumer of the exported VIT API, never the XRSLAM C API.
#define VIT_INTERFACE_IMPLEMENTATION
#include "vit/vit_interface.h"
#include <algorithm>
#include <array>
#include <chrono>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <sstream>
#include <stdexcept>
#include <thread>
#include <vector>
#include <opencv2/opencv.hpp>

struct Event {
    int64_t ns;
    std::string image;
    std::array<float, 6> imu{};
};
static void read_csv(const std::string &path, bool camera, std::vector<Event> &events) {
    std::ifstream stream(path);
    if (!stream) throw std::runtime_error("Cannot open " + path);
    for (std::string line; std::getline(stream, line);) {
        if (line.empty() || line.front() == '#') continue;
        if (line.back() == '\r') line.pop_back();
        std::istringstream row(line);
        std::string field;
        std::getline(row, field, ',');
        Event event{}; event.ns = std::stoll(field);
        if (camera) std::getline(row, event.image, ',');
        else for (auto &v : event.imu) { std::getline(row, field, ','); v = std::stof(field); }
        events.push_back(std::move(event));
    }
}
struct Tracker {
    vit_tracker_t *value = nullptr;
    ~Tracker() { vit_tracker_destroy(value); }
};
static void require(vit_result_t result, const char *operation) {
    if (result != VIT_SUCCESS) throw std::runtime_error(std::string(operation) + " failed: " + std::to_string(result));
}
int main(int argc, char **argv) {
    if (argc != 5) { std::cerr << "Usage: vit-replay slam.yaml device.yaml sequence/mav0 output.tum\n"; return 2; }
    try {
        const std::string wrapper = std::string(argv[4]) + ".vit.yml";
        {
            cv::FileStorage config(wrapper, cv::FileStorage::WRITE);
            config << "slam_config" << std::filesystem::absolute(argv[1]).string();
            config << "device_config" << std::filesystem::absolute(argv[2]).string();
        }
        Tracker tracker;
        vit_config_t config{wrapper.c_str(), 1, 1, false};
        require(vit_tracker_create(&config, &tracker.value), "create");
        // The real backend is a process singleton, not a reentrant API.
        vit_tracker_t *second = nullptr;
        if (vit_tracker_create(&config, &second) == VIT_SUCCESS || second)
            throw std::runtime_error("Backend incorrectly accepted a second instance");
        require(vit_tracker_start(tracker.value), "start");
        std::vector<Event> events;
        const std::string directory(argv[3]);
        read_csv(directory + "/cam0/data.csv", true, events);
        read_csv(directory + "/imu0/data.csv", false, events);
        std::stable_sort(events.begin(), events.end(), [](const Event &a, const Event &b) {
            return a.ns < b.ns || (a.ns == b.ns && a.image.empty() && !b.image.empty());
        });
        if (events.empty()) throw std::runtime_error("Empty dataset");
        std::ofstream output(argv[4]);
        if (!output) throw std::runtime_error("Cannot create trajectory");
        output << std::setprecision(17);
        size_t poses = 0, frames = 0;
        auto drain = [&] {
            for (;;) {
                vit_pose_t *pose = nullptr;
                require(vit_tracker_pop_pose(tracker.value, &pose), "pop pose");
                if (!pose) break;
                vit_pose_data_t p{};
                const auto result = vit_pose_get_data(pose, &p);
                vit_pose_destroy(pose);
                require(result, "pose data");
                output << double(p.timestamp) * 1e-9 << ' ' << p.px << ' ' << p.py << ' ' << p.pz
                       << ' ' << p.ox << ' ' << p.oy << ' ' << p.oz << ' ' << p.ow << '\n';
                ++poses;
            }
        };
        // Replay at sensor rate to exercise the asynchronous, bounded API.
        // This is a desktop pipeline throughput check, not an Android latency test.
        const auto start = std::chrono::steady_clock::now();
        for (const auto &event : events) {
            std::this_thread::sleep_until(start + std::chrono::nanoseconds(event.ns - events.front().ns));
            if (event.image.empty()) {
                vit_imu_sample_t sample{event.ns, event.imu[3], event.imu[4], event.imu[5],
                    event.imu[0], event.imu[1], event.imu[2]};
                require(vit_tracker_push_imu_sample(tracker.value, &sample), "push imu");
            } else {
                const auto image = cv::imread(directory + "/cam0/data/" + event.image, cv::IMREAD_GRAYSCALE);
                if (image.empty()) throw std::runtime_error("Missing image");
                vit_img_sample_t sample{0, event.ns, image.data, uint32_t(image.cols), uint32_t(image.rows),
                    uint32_t(image.step[0]), uint32_t(image.step[0] * image.rows), VIT_IMAGE_FORMAT_L8, 0, nullptr};
                require(vit_tracker_push_img_sample(tracker.value, &sample), "push image");
                if (++frames % 100 == 0) std::cout << "frames=" << frames << " poses=" << poses << std::endl;
            }
            drain();
        }
        for (int i = 0; i < 100; ++i) { std::this_thread::sleep_for(std::chrono::milliseconds(10)); drain(); }
        require(vit_tracker_stop(tracker.value), "stop");
        bool running = true;
        require(vit_tracker_is_running(tracker.value, &running), "is running");
        if (running) throw std::runtime_error("Stop did not stop tracker");
        std::cout << "completed VIT frames=" << frames << " poses=" << poses << std::endl;
        return poses >= 3 ? 0 : 3;
    } catch (const std::exception &e) { std::cerr << e.what() << '\n'; return 1; }
}
