// Copyright 2026, OpenView contributors.
// SPDX-License-Identifier: BSL-1.0
/*!
 * @file
 * @brief Head position from ARCore, behind Monado's SLAM tracker port.
 *
 * ARCore runs in the runtime's own process (dev.openview.runtime.tracking.ArCoreTracker,
 * on a Java thread: its C API calls back into Java classes that a native thread cannot
 * load). Poses come back through a registered native method and are handed to the
 * Android device exactly like the VIT tracker's, so the device's fusion, alignment
 * and hold logic apply unchanged.
 */
#pragma once

#ifdef __cplusplus
extern "C" {
#endif

struct xrt_frame_context;
struct xrt_tracked_slam;

/*!
 * Starts ARCore when this phone supports it and has it installed, and the
 * runtime may use the camera. On success the tracker is owned by @p xfctx.
 *
 * @return 0 on success, -1 when ARCore is not available here (the caller
 *         falls back to another tracker).
 */
int
ov_arcore_tracker_create(struct xrt_frame_context *xfctx, struct xrt_tracked_slam **out_xts);

#ifdef __cplusplus
}
#endif
