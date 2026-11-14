// Copyright 2026, OpenView contributors.
// SPDX-License-Identifier: BSL-1.0
#include "backend.h"
#include <atomic>
#include <cmath>
#include <filesystem>
#include <stdexcept>
#include <opencv2/opencv.hpp>
#include "XRSLAM.h"

namespace openview::xrslam {
namespace {
std::atomic<bool> instance_claimed{false};
struct InstanceClaim {
    InstanceClaim() {
        if (instance_claimed.exchange(true)) throw std::runtime_error("XRSLAM supports only one live tracker per process");
    }
    ~InstanceClaim() { instance_claimed = false; }
};
void require(bool condition, const char *message) { if (!condition) throw std::runtime_error(message); }
std::string scalar(const cv::FileNode &node) {
    std::string value;
    node >> value;
    // OpenCV's YAML reader keeps an inline comment inside a plain scalar
    // ("pinhole  # camera model"); calibration files in the wild use them,
    // so strip the comment and surrounding blanks before comparing.
    const auto comment = value.find(" #");
    if (comment != std::string::npos) value.erase(comment);
    const auto first = value.find_first_not_of(" \t\r\n");
    const auto last = value.find_last_not_of(" \t\r\n");
    return first == std::string::npos ? std::string() : value.substr(first, last - first + 1);
}
std::vector<double> values(const cv::FileNode &node, size_t count) {
    std::vector<double> result;
    node >> result;
    require(result.size() == count, "Missing or wrong-sized calibration array");
    for (double x : result) require(std::isfinite(x), "Nonfinite calibration value");
    return result;
}
class XrslamBackend final : public Backend {
    InstanceClaim claim;
    std::string slam_path, device_path;
    uint32_t image_width = 0, image_height = 0;
    cv::Mat map_x, map_y;
    std::optional<int64_t> epoch;
    std::optional<vit_pose_data_t> previous;
    bool started = false;
public:
    explicit XrslamBackend(const std::string &path) {
        const auto base = std::filesystem::absolute(path).parent_path();
        cv::FileStorage wrapper(path, cv::FileStorage::READ);
        require(wrapper.isOpened(), "Cannot open adapter configuration");
        std::string slam, device;
        wrapper["slam_config"] >> slam; wrapper["device_config"] >> device;
        require(!slam.empty() && !device.empty(), "slam_config and device_config are required");
        slam_path = (base / slam).string(); device_path = (base / device).string();
        require(std::filesystem::is_regular_file(slam_path), "SLAM configuration does not exist");
        cv::FileStorage sensor(device_path, cv::FileStorage::READ);
        require(sensor.isOpened(), "Cannot open device calibration");
        const auto camera = sensor["cam0"];
        const std::string model = scalar(camera["camera_model"]);
        const std::string distortion_model = scalar(camera["distortion_model"]);
        require(model == "pinhole" && distortion_model == "radtan", "Only calibrated pinhole/radtan is supported");
        require(!camera["time_offset"].empty() && double(camera["time_offset"]) == 0,
                "Nonzero/missing time offset is not supported by this experiment");
        require(!camera["camera_readout_time"].empty() && double(camera["camera_readout_time"]) == 0,
                "Only global-shutter data is validated by this experiment");
        const auto resolution = values(camera["resolution"], 2);
        require(resolution[0] >= 1 && resolution[1] >= 1 && resolution[0] <= 4096 && resolution[1] <= 4096 &&
                std::floor(resolution[0]) == resolution[0] && std::floor(resolution[1]) == resolution[1], "Invalid resolution");
        image_width = uint32_t(resolution[0]); image_height = uint32_t(resolution[1]);
        const auto intrinsics = values(camera["intrinsics"], 4);
        const auto distortion = values(camera["distortion"], 4);
        require(intrinsics[0] > 0 && intrinsics[1] > 0, "Invalid focal length");
        // Returning the IMU pose avoids silently changing the tracked body.
        const auto q_bi = values(sensor["imu"]["extrinsic"]["q_bi"], 4);
        const auto p_bi = values(sensor["imu"]["extrinsic"]["p_bi"], 3);
        require(q_bi == std::vector<double>({0, 0, 0, 1}) && p_bi == std::vector<double>({0, 0, 0}),
                "Experimental adapter requires body frame equal to IMU frame");
        values(camera["extrinsic"]["q_bc"], 4); values(camera["extrinsic"]["p_bc"], 3);
        const cv::Mat k = (cv::Mat_<double>(3, 3) << intrinsics[0], 0, intrinsics[2], 0, intrinsics[1], intrinsics[3], 0, 0, 1);
        cv::initUndistortRectifyMap(k, cv::Mat(distortion), cv::Mat(), k,
            cv::Size(image_width, image_height), CV_32FC1, map_x, map_y);
    }
    ~XrslamBackend() override {
        if (started) XRSLAMDestroy(); // Upstream currently logs; see README lifetime limitation.
    }
    uint32_t width() const override { return image_width; }
    uint32_t height() const override { return image_height; }
    void start() override {
        void *configuration = nullptr;
        require(XRSLAMCreate(slam_path.c_str(), device_path.c_str(), "", "OpenView experimental VIT", &configuration) == 1,
                "XRSLAM initialization failed");
        started = true; epoch.reset(); previous.reset();
    }
    void imu(const vit_imu_sample_t &s) override {
        if (!epoch) epoch = s.timestamp - 1000000000LL;
        const double seconds = double(s.timestamp - *epoch) * 1e-9;
        XRSLAMGyroscope gyro{{s.wx, s.wy, s.wz}, seconds};
        XRSLAMAcceleration accel{{s.ax, s.ay, s.az}, seconds};
        XRSLAMPushSensorData(XRSLAM_SENSOR_GYROSCOPE, &gyro);
        XRSLAMPushSensorData(XRSLAM_SENSOR_ACCELERATION, &accel);
    }
    std::optional<vit_pose_data_t> image(const Image &s) override {
        require(epoch.has_value(), "Image preceded all IMU samples");
        cv::Mat raw(s.height, s.width, CV_8UC1, const_cast<uint8_t *>(s.pixels.data()));
        cv::Mat rectified;
        cv::remap(raw, rectified, map_x, map_y, cv::INTER_LINEAR);
        XRSLAMImage input{};
        input.data = rectified.data; input.stride = int(rectified.step[0]);
        input.timeStamp = double(s.timestamp - *epoch) * 1e-9; input.camera_id = 0; input.channel = 1;
        XRSLAMPushSensorData(XRSLAM_SENSOR_CAMERA, &input); XRSLAMRunOneFrame();
        XRSLAMState state = XRSLAM_STATE_INITIALIZING;
        XRSLAMGetResult(XRSLAM_RESULT_STATE, &state);
        if (state != XRSLAM_STATE_TRACKING_SUCCESS) { previous.reset(); return std::nullopt; }
        XRSLAMPose pose{}; XRSLAMGetResult(XRSLAM_RESULT_BODY_POSE, &pose);
        require(std::isfinite(pose.timestamp) && pose.timestamp >= 0 && pose.timestamp <= input.timeStamp + 1e-9,
                "Invalid upstream pose timestamp");
        vit_pose_data_t p{};
        p.timestamp = *epoch + std::llround(pose.timestamp * 1e9);
        if (std::abs(p.timestamp - s.timestamp) <= 2) p.timestamp = s.timestamp;
        p.px = float(pose.translation[0]); p.py = float(pose.translation[1]); p.pz = float(pose.translation[2]);
        p.ox = float(pose.quaternion[0]); p.oy = float(pose.quaternion[1]);
        p.oz = float(pose.quaternion[2]); p.ow = float(pose.quaternion[3]);
        // The public C API omits estimator velocity. Report a measured backward
        // position difference; suppress the first pose rather than invent zero.
        if (!previous) { previous = p; return std::nullopt; }
        if (p.timestamp <= previous->timestamp) return std::nullopt;
        const double dt = double(p.timestamp - previous->timestamp) * 1e-9;
        p.vx = float((p.px - previous->px) / dt); p.vy = float((p.py - previous->py) / dt);
        p.vz = float((p.pz - previous->pz) / dt); previous = p;
        return p;
    }
};
}
std::unique_ptr<Backend> make_backend(const std::string &path) { return std::make_unique<XrslamBackend>(path); }
} // namespace openview::xrslam
