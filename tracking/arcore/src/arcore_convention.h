// Copyright 2026, OpenView contributors.
// SPDX-License-Identifier: BSL-1.0
/*!
 * @file
 * @brief Axis conventions between ARCore and the Android device's SLAM input.
 *
 * Pure arithmetic with no Monado or Android types, so it is tested on the host.
 *
 * ARCore's display oriented camera pose already uses the convention the
 * runtime outputs: world Y up (gravity), camera X right, Y up and -Z ahead
 * of the screen. The Android device, however, was written for Basalt and
 * converts every tracker pose with `android_slam_to_output_convention`
 * (patch 0004). Handing ARCore's pose through the inverse of that conversion
 * lets the device keep a single code path for every tracker.
 */
#pragma once

#ifdef __cplusplus
extern "C" {
#endif

struct ov_quat
{
	float x, y, z, w;
};

struct ov_vec3
{
	float x, y, z;
};

struct ov_pose
{
	struct ov_quat orientation;
	struct ov_vec3 position;
};

//! Hamilton product a * b.
static inline struct ov_quat
ov_quat_mul(struct ov_quat a, struct ov_quat b)
{
	struct ov_quat r;
	r.w = a.w * b.w - a.x * b.x - a.y * b.y - a.z * b.z;
	r.x = a.w * b.x + a.x * b.w + a.y * b.z - a.z * b.y;
	r.y = a.w * b.y - a.x * b.z + a.y * b.w + a.z * b.x;
	r.z = a.w * b.z + a.x * b.y - a.y * b.x + a.z * b.w;
	return r;
}

// The two fixed rotations of android_slam_to_output_convention (patch 0004).
#define OV_SQRT1_2 0.70710678f
//! Minus a quarter turn about X: Z up to Y up.
static const struct ov_quat OV_WORLD_Y_UP = {-OV_SQRT1_2, 0.0f, 0.0f, OV_SQRT1_2};
//! Minus a quarter turn about Z: head axes expressed in sensor axes.
static const struct ov_quat OV_SENSOR_FROM_HEAD = {0.0f, 0.0f, -OV_SQRT1_2, OV_SQRT1_2};

static inline struct ov_quat
ov_quat_conjugate(struct ov_quat q)
{
	struct ov_quat r = {-q.x, -q.y, -q.z, q.w};
	return r;
}

//! What the device does to a tracker pose (copy of patch 0004, kept for the tests).
static inline struct ov_pose
ov_slam_to_output(struct ov_pose slam)
{
	struct ov_pose out;
	out.orientation = ov_quat_mul(ov_quat_mul(OV_WORLD_Y_UP, slam.orientation), OV_SENSOR_FROM_HEAD);
	out.position.x = slam.position.x;
	out.position.y = slam.position.z;
	out.position.z = -slam.position.y;
	return out;
}

//! The inverse: an output convention pose (ARCore's) as the device expects tracker poses.
static inline struct ov_pose
ov_output_to_slam(struct ov_pose out)
{
	struct ov_pose slam;
	slam.orientation = ov_quat_mul(ov_quat_mul(ov_quat_conjugate(OV_WORLD_Y_UP), out.orientation),
	                               ov_quat_conjugate(OV_SENSOR_FROM_HEAD));
	slam.position.x = out.position.x;
	slam.position.y = -out.position.z;
	slam.position.z = out.position.y;
	return slam;
}

#ifdef __cplusplus
}
#endif
