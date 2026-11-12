// Copyright 2026, OpenView contributors.
// SPDX-License-Identifier: BSL-1.0

package dev.openview.recorder

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorDirectChannel
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the platform declares about its camera and IMU: the claims that the
 * recorded data is then checked against. Written next to every recording.
 */
object DeviceReport {
    fun build(context: Context, cameraId: String): JSONObject {
        val cameras = context.getSystemService(CameraManager::class.java)
        val sensors = context.getSystemService(SensorManager::class.java)

        return JSONObject()
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("soc", if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else Build.HARDWARE)
            .put("android_sdk", Build.VERSION.SDK_INT)
            // The offset between the two clocks samples may be stamped with.
            .put("boottime_minus_monotonic_ns", SystemClock.elapsedRealtimeNanos() - System.nanoTime())
            .put("camera", camera(cameras.getCameraCharacteristics(cameraId)).put("id", cameraId))
            .put("camera_ids", JSONArray(cameras.cameraIdList))
            .put("gyroscope", sensor(sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)))
            .put("accelerometer", sensor(sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)))
    }

    private fun camera(c: CameraCharacteristics): JSONObject {
        val timestampSource =
            when (c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)) {
                CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME -> "REALTIME"
                CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN -> "UNKNOWN"
                else -> null
            }
        val hardwareLevel =
            when (c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
                else -> null
            }
        val physicalSize = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val activeArray = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)

        return JSONObject()
            .put("timestamp_source", timestampSource)
            .put("hardware_level", hardwareLevel)
            .put("physical_camera_ids", JSONArray(c.physicalCameraIds))
            .put("focal_lengths_mm", floats(c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)))
            .put("sensor_size_mm", physicalSize?.let { JSONArray(listOf(it.width, it.height)) })
            .put("active_array_px", activeArray?.let { JSONArray(listOf(it.width(), it.height())) })
            // Null on most phones: then intrinsics have to be calibrated.
            .put("intrinsic_calibration", floats(c.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)))
            .put("distortion", floats(c.get(CameraCharacteristics.LENS_DISTORTION)))
            .put("pose_rotation", floats(c.get(CameraCharacteristics.LENS_POSE_ROTATION)))
            .put("pose_translation", floats(c.get(CameraCharacteristics.LENS_POSE_TRANSLATION)))
            .put("pose_reference", c.get(CameraCharacteristics.LENS_POSE_REFERENCE))
            .put(
                "optical_stabilization_modes",
                ints(c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)),
            )
            .put("min_focus_distance_diopters", c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE))
    }

    private fun sensor(s: Sensor?): JSONObject? {
        s ?: return null
        return JSONObject()
            .put("name", s.name)
            .put("vendor", s.vendor)
            .put("min_delay_us", s.minDelay)
            .put("max_rate_hz", if (s.minDelay > 0) 1e6 / s.minDelay else null)
            .put("resolution", s.resolution.toDouble())
            .put("max_range", s.maximumRange.toDouble())
            .put("fifo_max_events", s.fifoMaxEventCount)
            .put("direct_channel_memory_file", s.isDirectChannelTypeSupported(SensorDirectChannel.TYPE_MEMORY_FILE))
            .put("direct_channel_highest_rate_level", s.highestDirectReportRateLevel)
    }

    private fun floats(values: FloatArray?): JSONArray? =
        values?.let { JSONArray(it.map { v -> v.toDouble() }) }

    private fun ints(values: IntArray?): JSONArray? = values?.let { JSONArray(it.toList()) }
}
