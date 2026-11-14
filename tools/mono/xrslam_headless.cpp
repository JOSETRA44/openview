// Copyright 2026, OpenView contributors.
// SPDX-License-Identifier: BSL-1.0
// Original headless experiment harness for XRSLAM's public C API. No VIT integration yet.
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>
#include <opencv2/opencv.hpp>
#include "XRSLAM.h"

struct Sample {
    int64_t ns;
    bool camera;
    std::string filename;
    std::array<double, 6> imu{};
};

static std::vector<std::string> fields(std::string line) {
    if (!line.empty() && line.back() == '\r') line.pop_back();
    std::vector<std::string> result;
    std::istringstream stream(line);
    for (std::string value; std::getline(stream, value, ',');) result.push_back(value);
    return result;
}

static void read_samples(const std::string &path, bool camera, std::vector<Sample> &samples) {
    std::ifstream stream(path);
    if (!stream) throw std::runtime_error("Cannot read " + path);
    for (std::string line; std::getline(stream, line);) {
        if (line.empty() || line[0] == '#') continue;
        const auto row = fields(line);
        if (row.size() < (camera ? 2u : 7u)) throw std::runtime_error("Bad CSV row in " + path);
        Sample sample{std::stoll(row[0]), camera, camera ? row[1] : "", {}};
        if (!camera) for (int axis = 0; axis < 6; ++axis) sample.imu[axis] = std::stod(row[axis + 1]);
        samples.push_back(sample);
    }
}

int main(int argc, char **argv) {
    if (argc != 5) {
        std::cerr << "Usage: xrslam-headless slam.yaml device.yaml sequence/mav0 output.tum\n";
        return 2;
    }
    try {
        // Match upstream EuRoC reader: undistort cam0 before passing it to the tracker.
        cv::FileStorage configuration(argv[2], cv::FileStorage::READ);
        if (!configuration.isOpened()) throw std::runtime_error("Cannot read device configuration");
        std::vector<double> intrinsics, distortion, resolution;
        const auto camera = configuration["cam0"];
        camera["intrinsics"] >> intrinsics;
        camera["distortion"] >> distortion;
        camera["resolution"] >> resolution;
        if (intrinsics.size() != 4 || distortion.size() != 4 || resolution.size() != 2)
            throw std::runtime_error("Expected EuRoC pinhole/radtan cam0 calibration");
        const double offset = static_cast<double>(camera["time_offset"]);
        if (std::abs(offset) > 1e-12) throw std::runtime_error("This experiment requires zero configured time offset");
        const cv::Mat matrix = (cv::Mat_<double>(3, 3) << intrinsics[0], 0, intrinsics[2], 0, intrinsics[1], intrinsics[3], 0, 0, 1);
        cv::Mat map_x, map_y;
        cv::initUndistortRectifyMap(matrix, cv::Mat(distortion), cv::Mat(), matrix,
            cv::Size(static_cast<int>(resolution[0]), static_cast<int>(resolution[1])), CV_32FC1, map_x, map_y);
        std::vector<Sample> samples;
        const std::string directory(argv[3]);
        read_samples(directory + "/cam0/data.csv", true, samples);
        read_samples(directory + "/imu0/data.csv", false, samples);
        std::stable_sort(samples.begin(), samples.end(), [](const Sample &a, const Sample &b) {
            return a.ns < b.ns || (a.ns == b.ns && !a.camera && b.camera);
        });
        void *config = nullptr; // Owned by XRSLAM, never delete this pointer.
        if (XRSLAMCreate(argv[1], argv[2], "", "OpenView offline experiment", &config) != 1)
            throw std::runtime_error("XRSLAMCreate failed");
        std::ofstream trajectory(argv[4]);
        if (!trajectory) throw std::runtime_error("Cannot create trajectory output");
        trajectory << std::setprecision(17);
        uint64_t frames = 0, poses = 0;
        double last_pose_time = -1;
        for (const auto &sample : samples) {
            const double timestamp = static_cast<double>(sample.ns) * 1e-9;
            if (!sample.camera) {
                XRSLAMGyroscope gyro{{sample.imu[0], sample.imu[1], sample.imu[2]}, timestamp};
                XRSLAMAcceleration accel{{sample.imu[3], sample.imu[4], sample.imu[5]}, timestamp};
                XRSLAMPushSensorData(XRSLAM_SENSOR_GYROSCOPE, &gyro);
                XRSLAMPushSensorData(XRSLAM_SENSOR_ACCELERATION, &accel);
                continue;
            }
            const cv::Mat raw = cv::imread(directory + "/cam0/data/" + sample.filename, cv::IMREAD_GRAYSCALE);
            if (raw.empty() || raw.size() != map_x.size()) throw std::runtime_error("Missing image or unexpected resolution: " + sample.filename);
            cv::Mat image;
            cv::remap(raw, image, map_x, map_y, cv::INTER_LINEAR);
            XRSLAMImage input{};
            input.data = image.data;
            input.timeStamp = timestamp;
            input.stride = static_cast<int>(image.step[0]);
            input.camera_id = 0;
            input.channel = 1;
            XRSLAMPushSensorData(XRSLAM_SENSOR_CAMERA, &input);
            XRSLAMRunOneFrame();
            XRSLAMState state = XRSLAM_STATE_INITIALIZING;
            XRSLAMGetResult(XRSLAM_RESULT_STATE, &state);
            if (state == XRSLAM_STATE_TRACKING_SUCCESS) {
                XRSLAMPose pose{};
                XRSLAMGetResult(XRSLAM_RESULT_BODY_POSE, &pose);
                // Output the IMU/body pose, not camera pose: EuRoC truth is body/IMU.
                if (std::isfinite(pose.timestamp) && pose.timestamp > last_pose_time && pose.timestamp > 0) {
                    trajectory << pose.timestamp;
                    for (const double value : pose.translation) trajectory << ' ' << value;
                    for (const double value : pose.quaternion) trajectory << ' ' << value;
                    trajectory << '\n';
                    last_pose_time = pose.timestamp;
                    ++poses;
                }
            }
            if (++frames % 100 == 0) {
                trajectory.flush();
                std::cout << "frames=" << frames << " poses=" << poses << " state=" << state << std::endl;
            }
        }
        trajectory.close();
        XRSLAMDestroy();
        std::cout << "completed frames=" << frames << " poses=" << poses << std::endl;
        return poses >= 3 ? 0 : 3;
    } catch (const std::exception &error) {
        std::cerr << error.what() << std::endl;
        return 1;
    }
}
