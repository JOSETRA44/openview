// Copyright 2026, OpenView contributors.
// SPDX-License-Identifier: BSL-1.0
// Host test: cc -std=c11 -I tracking/arcore/src tracking/arcore/tests/arcore_convention_test.c -lm
#include "arcore_convention.h"

#include <math.h>
#include <stdio.h>

static int failures = 0;

static void
check(const char *what, float got, float want)
{
	if (fabsf(got - want) > 1e-5f) {
		printf("FAIL %s: %f != %f\n", what, got, want);
		failures++;
	}
}

static void
check_same_rotation(const char *what, struct ov_quat a, struct ov_quat b)
{
	// q and -q are the same rotation.
	float dot = a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w;
	check(what, fabsf(dot), 1.0f);
}

int
main(void)
{
	// A head pose: half a metre right, a bit up, a step ahead, turned and tilted.
	struct ov_pose head = {{0.1f, 0.3f, -0.05f, 0.948f}, {0.5f, 0.2f, -1.0f}};
	float n = sqrtf(head.orientation.x * head.orientation.x + head.orientation.y * head.orientation.y +
	                head.orientation.z * head.orientation.z + head.orientation.w * head.orientation.w);
	head.orientation.x /= n;
	head.orientation.y /= n;
	head.orientation.z /= n;
	head.orientation.w /= n;

	// Round trip through the device's conversion gives ARCore's pose back.
	struct ov_pose back = ov_slam_to_output(ov_output_to_slam(head));
	check("x", back.position.x, head.position.x);
	check("y", back.position.y, head.position.y);
	check("z", back.position.z, head.position.z);
	check_same_rotation("orientation", back.orientation, head.orientation);

	// Walking one metre forward in ARCore is one metre forward for the runtime.
	struct ov_pose forward = {{0, 0, 0, 1}, {0, 0, -1}};
	struct ov_pose seen = ov_slam_to_output(ov_output_to_slam(forward));
	check("forward z", seen.position.z, -1.0f);

	// The identity orientation stays the identity: the head looks down -Z.
	check_same_rotation("identity", seen.orientation, (struct ov_quat){0, 0, 0, 1});

	if (failures == 0) {
		printf("arcore convention: ok\n");
	}
	return failures == 0 ? 0 : 1;
}
