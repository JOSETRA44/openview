// Copyright 2026, OpenView contributors.
// SPDX-License-Identifier: BSL-1.0

package dev.openview.recorder

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import java.io.BufferedOutputStream
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Records one camera and the IMU, each sample with the timestamp the platform
 * gives it and nothing else done to it. Whether those timestamps share a clock,
 * and how far apart they are, is exactly what the recordings are for, so no
 * alignment or resampling happens on the device: see to_euroc.py.
 *
 * Layout written under [dir]:
 * - `frames/<timestamp_ns>.pgm`: luma plane of each frame.
 * - `frames.csv`: per frame timestamp, exposure, rolling shutter skew, arrival time.
 * - `gyro.csv`, `accel.csv`: raw events, one per line.
 */
class Recording(private val context: Context, private val dir: File, private val cameraId: String) {
    val frameCount = AtomicInteger()
    val gyroCount = AtomicInteger()
    val accelCount = AtomicInteger()

    private val cameraThread = HandlerThread("camera").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val sensorThread = HandlerThread("sensors").apply { start() }
    private val sensorHandler = Handler(sensorThread.looper)

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val cameraManager = context.getSystemService(CameraManager::class.java)

    private val framesDir = File(dir, "frames").apply { mkdirs() }
    private val framesCsv = writer("frames.csv", FRAMES_HEADER)
    private val gyroCsv = writer("gyro.csv", IMU_HEADER)
    private val accelCsv = writer("accel.csv", IMU_HEADER)

    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null

    private val sensorListener =
        object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val line = "${event.timestamp},${event.values[0]},${event.values[1]},${event.values[2]}\n"
                if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
                    gyroCsv.write(line)
                    gyroCount.incrementAndGet()
                } else {
                    accelCsv.write(line)
                    accelCount.incrementAndGet()
                }
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
        }

    fun start() {
        // Uncalibrated would skip the platform's bias removal, but not every
        // device has those sensors; the calibrated ones are what the runtime uses.
        for (type in intArrayOf(Sensor.TYPE_GYROSCOPE, Sensor.TYPE_ACCELEROMETER)) {
            val sensor = sensorManager.getDefaultSensor(type) ?: continue
            sensorManager.registerListener(
                sensorListener,
                sensor,
                SensorManager.SENSOR_DELAY_FASTEST,
                sensorHandler,
            )
        }
        openCamera()
    }

    fun stop() {
        sensorManager.unregisterListener(sensorListener)
        session?.close()
        camera?.close()
        cameraThread.quitSafely()
        sensorThread.quitSafely()
        cameraThread.join()
        sensorThread.join()
        reader?.close()
        framesCsv.close()
        gyroCsv.close()
        accelCsv.close()
    }

    @SuppressLint("MissingPermission") // The activity only starts a recording once granted.
    private fun openCamera() {
        val imageReader =
            ImageReader.newInstance(FRAME_SIZE.width, FRAME_SIZE.height, ImageFormat.YUV_420_888, 4)
        reader = imageReader

        // The capture result (exposure, skew) and the image arrive separately
        // and in no fixed order. Both run on the camera thread, so a plain map
        // keyed by the sensor timestamp is enough to pair them.
        val pendingResults = HashMap<Long, String>()
        val pendingImages = HashMap<Long, Long>()

        fun writeRow(timestampNs: Long, resultFields: String, arrivalNs: Long) {
            framesCsv.write("$timestampNs,$resultFields,$arrivalNs\n")
        }

        imageReader.setOnImageAvailableListener(
            { r ->
                val image = r.acquireNextImage() ?: return@setOnImageAvailableListener
                val arrivalNs = System.nanoTime()
                val timestampNs = image.timestamp
                writePgm(File(framesDir, "$timestampNs.pgm"), image)
                image.close()
                frameCount.incrementAndGet()

                val fields = pendingResults.remove(timestampNs)
                if (fields != null) {
                    writeRow(timestampNs, fields, arrivalNs)
                } else {
                    pendingImages[timestampNs] = arrivalNs
                }
            },
            cameraHandler,
        )

        val captureCallback =
            object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult,
                ) {
                    val timestampNs = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
                    val fields =
                        listOf(
                                result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                                result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW),
                                result.get(CaptureResult.SENSOR_FRAME_DURATION),
                                result.get(CaptureResult.SENSOR_SENSITIVITY),
                                result.get(CaptureResult.LENS_FOCUS_DISTANCE),
                            )
                            .joinToString(",") { it?.toString() ?: "" }

                    val arrivalNs = pendingImages.remove(timestampNs)
                    if (arrivalNs != null) {
                        writeRow(timestampNs, fields, arrivalNs)
                    } else {
                        pendingResults[timestampNs] = fields
                    }
                }
            }

        cameraManager.openCamera(
            cameraId,
            object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    camera = device
                    val request =
                        device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(imageReader.surface)
                            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
                            // Stabilisation moves the image independently of the
                            // device, and autofocus changes the focal length: both
                            // break the fixed camera model a tracker assumes.
                            set(
                                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
                            )
                            set(
                                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF,
                            )
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                            set(CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f)
                        }

                    val config =
                        SessionConfiguration(
                            SessionConfiguration.SESSION_REGULAR,
                            listOf(OutputConfiguration(imageReader.surface)),
                            { command -> cameraHandler.post(command) },
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(s: CameraCaptureSession) {
                                    session = s
                                    s.setRepeatingRequest(request.build(), captureCallback, cameraHandler)
                                }

                                override fun onConfigureFailed(s: CameraCaptureSession) {
                                    Log.e(TAG, "Camera session configuration failed")
                                }
                            },
                        )
                    device.createCaptureSession(config)
                }

                override fun onDisconnected(device: CameraDevice) = device.close()

                override fun onError(device: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error $error")
                    device.close()
                }
            },
            cameraHandler,
        )
    }

    /** PGM because it is the luma plane plus a header: lossless and no encoder in the capture path. */
    private fun writePgm(file: File, image: android.media.Image) {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val row = ByteArray(image.width)
        BufferedOutputStream(FileOutputStream(file), 1 shl 16).use { out ->
            out.write("P5\n${image.width} ${image.height}\n255\n".toByteArray())
            for (y in 0 until image.height) {
                buffer.position(y * plane.rowStride)
                buffer.get(row, 0, image.width)
                out.write(row)
            }
        }
    }

    private fun writer(name: String, header: String): BufferedWriter =
        File(dir, name).bufferedWriter(bufferSize = 1 shl 16).apply { write(header) }

    companion object {
        const val TAG = "OpenViewRecorder"
        val FRAME_SIZE = Size(640, 480)

        // Units: nanoseconds, except iso and focus distance (diopters).
        const val FRAMES_HEADER =
            "sensor_timestamp_ns,exposure_ns,rolling_shutter_skew_ns,frame_duration_ns,iso," +
                "focus_distance_diopters,arrival_monotonic_ns\n"
        const val IMU_HEADER = "timestamp_ns,x,y,z\n"

        fun backCameraId(manager: CameraManager): String? =
            manager.cameraIdList.firstOrNull {
                manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            }
    }
}
