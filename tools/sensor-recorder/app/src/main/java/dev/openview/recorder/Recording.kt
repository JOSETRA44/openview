// Copyright 2026, OpenView contributors.
// SPDX-License-Identifier: BSL-1.0

package dev.openview.recorder

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Bitmap
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
import org.json.JSONObject
import kotlin.math.abs

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
class Recording(
    private val context: Context,
    private val dir: File,
    private val cameraId: String,
    private val settings: CaptureSettings = CaptureSettings(),
    private val onPreview: ((Bitmap) -> Unit)? = null,
) {
    val frameCount = AtomicInteger()
    val gyroCount = AtomicInteger()
    val accelCount = AtomicInteger()
    val verifiedCount = AtomicInteger()
    @Volatile var controlStatus = "warming_up"
        private set
    @Volatile var actualControls = ""
        private set
    @Volatile private var stopping = false
    private var lastPreviewNs = 0L
    private var lockedExposureNs = 0L
    private var lockedIso = 0
    private var firstVerifiedTimestampNs: Long? = null
    private var consecutiveVerified = 0

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
    private var requestBuilder: CaptureRequest.Builder? = null
    private var autoExposureResults = 0
    private var exposureLocked = false
    private lateinit var captureCallback: CameraCaptureSession.CaptureCallback

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
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        require(capabilities?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true) {
            "Camera does not support manual sensor controls"
        }
        val exposureRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        require(exposureRange?.contains(settings.exposureNs) == true) { "Exposure outside camera range: $exposureRange" }
        val isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        require(settings.iso == 0 || isoRange?.contains(settings.iso) == true) { "ISO outside camera range: $isoRange" }
        val minFocus = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        require(settings.focusDiopters <= minFocus) { "Focus must be 0–$minFocus diopters" }
        writeCaptureReport()
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
        stopping = true
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
        writeCaptureReport()
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
            val fields = resultFields.split(',')
            framesCsv.write("$timestampNs,${fields.take(5).joinToString(",")},$arrivalNs,${fields.drop(5).joinToString(",")}\n")
        }

        imageReader.setOnImageAvailableListener(
            { r ->
                val image = r.acquireNextImage() ?: return@setOnImageAvailableListener
                if (stopping) { image.close(); return@setOnImageAvailableListener }
                val arrivalNs = System.nanoTime()
                val timestampNs = image.timestamp
                try {
                    writePgm(File(framesDir, "$timestampNs.pgm"), image)
                    if (onPreview != null && arrivalNs - lastPreviewNs > 500_000_000L) {
                        lastPreviewNs = arrivalNs
                        val plane = image.planes[0]
                        val pixels = IntArray(image.width * image.height)
                        for (y in 0 until image.height) for (x in 0 until image.width) {
                            val luma = plane.buffer.get(y * plane.rowStride + x * plane.pixelStride).toInt() and 255
                            pixels[y * image.width + x] = (255 shl 24) or (luma shl 16) or (luma shl 8) or luma
                        }
                        onPreview.invoke(Bitmap.createBitmap(pixels, image.width, image.height, Bitmap.Config.ARGB_8888))
                    }
                } finally { image.close() }
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

        captureCallback =
            object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult,
                ) {
                    val timestampNs = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
                    if (stopping) return
                    lockShortExposure(result)
                    val verified = verifyControls(result)
                    val fields =
                        listOf(
                                result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                                result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW),
                                result.get(CaptureResult.SENSOR_FRAME_DURATION),
                                result.get(CaptureResult.SENSOR_SENSITIVITY),
                                result.get(CaptureResult.LENS_FOCUS_DISTANCE),
                                result.get(CaptureResult.CONTROL_AE_MODE),
                                result.get(CaptureResult.CONTROL_AF_MODE),
                                result.get(CaptureResult.CONTROL_AE_STATE),
                                if (verified) 1 else 0,
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
                    if (stopping) { device.close(); return }
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
                            set(CaptureRequest.LENS_FOCUS_DISTANCE, settings.focusDiopters)
                        }

                    requestBuilder = request

                    val config =
                        SessionConfiguration(
                            SessionConfiguration.SESSION_REGULAR,
                            listOf(OutputConfiguration(imageReader.surface)),
                            { command -> cameraHandler.post(command) },
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(s: CameraCaptureSession) {
                                    if (stopping) { s.close(); return }
                                    session = s
                                    s.setRepeatingRequest(request.build(), captureCallback, cameraHandler)
                                }

                                override fun onConfigureFailed(s: CameraCaptureSession) {
                                    controlStatus = "session_failed"
                                    Log.e(TAG, "Camera session configuration failed")
                                }
                            },
                        )
                    device.createCaptureSession(config)
                }

                override fun onDisconnected(device: CameraDevice) = device.close()

                override fun onError(device: CameraDevice, error: Int) {
                    controlStatus = "camera_error_$error"
                    Log.e(TAG, "Camera error $error")
                    device.close()
                }
            },
            cameraHandler,
        )
    }

    /*
     * Auto exposure indoors settles around 30 ms, and at head-like rotation
     * rates that smears the image beyond what any tracker can follow. So once
     * auto exposure has found the right brightness, the same brightness is
     * rebuilt from a short exposure and more gain: noisier, but sharp.
     */
    private fun lockShortExposure(result: TotalCaptureResult) {
        if (exposureLocked || ++autoExposureResults < AUTO_EXPOSURE_FRAMES) {
            return
        }
        val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
        if (settings.iso == 0 && aeState != CaptureResult.CONTROL_AE_STATE_CONVERGED &&
            aeState != CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED && autoExposureResults < 150) return
        val exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return
        val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: return
        val builder = requestBuilder ?: return
        val isoRange =
            cameraManager
                .getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: return
        exposureLocked = true

        lockedExposureNs = settings.exposureNs
        // If the gain runs out the frames come out darker, which is still better than blurred.
        lockedIso = if (settings.iso != 0) settings.iso else
            (iso * exposureNs / lockedExposureNs).coerceIn(isoRange.lower.toLong(), isoRange.upper.toLong()).toInt()

        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, lockedExposureNs)
        builder.set(CaptureRequest.SENSOR_SENSITIVITY, lockedIso)
        builder.set(CaptureRequest.SENSOR_FRAME_DURATION, FRAME_DURATION_NS)
        session?.setRepeatingRequest(builder.build(), captureCallback, cameraHandler)
        controlStatus = "waiting_for_manual_results"
        Log.i(TAG, "Exposure locked: $exposureNs ns iso $iso -> $lockedExposureNs ns iso $lockedIso")
    }

    private fun verifyControls(result: TotalCaptureResult): Boolean {
        val exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
        val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
        val focus = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
        actualControls = "${exposure?.div(1e6)} ms · ISO $iso · $focus D"
        // Sensor line timing can quantize exposure. This validates reported controls,
        // not sharpness, photometric calibration or physical focus accuracy.
        val verified = exposureLocked && exposure != null && iso != null && focus != null &&
            result.get(CaptureResult.CONTROL_AE_MODE) == CaptureResult.CONTROL_AE_MODE_OFF &&
            result.get(CaptureResult.CONTROL_AF_MODE) == CaptureResult.CONTROL_AF_MODE_OFF &&
            abs(exposure - lockedExposureNs) <= maxOf(100_000L, lockedExposureNs / 20) &&
            abs(iso - lockedIso) <= maxOf(1, lockedIso / 20) &&
            abs(focus - settings.focusDiopters) <= 0.02f &&
            result.get(CaptureResult.LENS_STATE) != CaptureResult.LENS_STATE_MOVING
        consecutiveVerified = if (verified) consecutiveVerified + 1 else 0
        if (exposureLocked) controlStatus = if (consecutiveVerified >= 5) "verified" else "waiting_for_manual_results"
        if (verified) {
            verifiedCount.incrementAndGet()
            if (firstVerifiedTimestampNs == null) firstVerifiedTimestampNs = result.get(CaptureResult.SENSOR_TIMESTAMP)
        }
        return verified
    }

    private fun writeCaptureReport() {
        File(dir, "capture.json").writeText(JSONObject()
            .put("schema_version", 1).put("camera_id", cameraId)
            .put("width", FRAME_SIZE.width).put("height", FRAME_SIZE.height)
            .put("requested_exposure_ns", settings.exposureNs)
            .put("requested_iso", settings.iso).put("requested_focus_diopters", settings.focusDiopters.toDouble())
            .put("locked_exposure_ns", lockedExposureNs).put("locked_iso", lockedIso)
            .put("control_status", controlStatus).put("verified_results", verifiedCount.get())
            .put("first_verified_timestamp_ns", firstVerifiedTimestampNs ?: JSONObject.NULL)
            .put("frames_saved", frameCount.get()).put("complete", stopping).toString(2))
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
        const val FRAME_DURATION_NS = 33_333_333L

        // At 1 rad/s a 5 ms exposure blurs about 2.5 px at this resolution.
        const val MAX_EXPOSURE_NS = 5_000_000L

        // Frames auto exposure gets to converge before the exposure is locked.
        const val AUTO_EXPOSURE_FRAMES = 30

        // Units: nanoseconds, except iso and focus distance (diopters).
        const val FRAMES_HEADER =
            "sensor_timestamp_ns,exposure_ns,rolling_shutter_skew_ns,frame_duration_ns,iso," +
                "focus_distance_diopters,arrival_monotonic_ns,ae_mode,af_mode,ae_state,control_verified\n"
        const val IMU_HEADER = "timestamp_ns,x,y,z\n"

        fun backCameraId(manager: CameraManager): String? =
            manager.cameraIdList.firstOrNull {
                manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            }
    }
}
