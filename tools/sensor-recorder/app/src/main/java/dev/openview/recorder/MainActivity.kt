// Copyright 2026, OpenView contributors.
// SPDX-License-Identifier: BSL-1.0

package dev.openview.recorder

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraCharacteristics
import android.graphics.Matrix
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.EditText
import android.widget.ImageView
import android.widget.ScrollView
import android.text.InputType
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One button. Can also be driven without touching the phone, for repeatable runs:
 *
 *     adb shell am start -n dev.openview.recorder/.MainActivity --ei seconds 30
 */
class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var button: Button
    private lateinit var exposureInput: EditText
    private lateinit var isoInput: EditText
    private lateinit var focusInput: EditText
    private lateinit var preview: ImageView

    private var recording: Recording? = null
    private var recordingDir: File? = null
    private var startedAtMs = 0L

    private val refreshStatus =
        object : Runnable {
            override fun run() {
                val r = recording ?: return
                val seconds = (System.currentTimeMillis() - startedAtMs) / 1000.0
                status.text =
                    getString(
                        R.string.status_recording,
                        seconds,
                        r.frameCount.get(),
                        r.gyroCount.get(),
                        r.accelCount.get(),
                    ) + "\n${getString(R.string.controls_status, r.controlStatus, r.verifiedCount.get())}\n${r.actualControls}"
                ui.postDelayed(this, 250)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        status = TextView(this).apply { textSize = 16f }
        button = Button(this).apply { setOnClickListener { if (recording == null) start() else stop() } }
        exposureInput = numericInput(getString(R.string.exposure_label), intent.getFloatExtra("exposure_ms", 5f).toString())
        isoInput = numericInput(getString(R.string.iso_label), intent.getIntExtra("iso", 0).toString())
        focusInput = numericInput(getString(R.string.focus_label), intent.getFloatExtra("focus_diopters", 0f).toString())
        preview = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(0xff000000.toInt()) }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(16, 16, 16, 16)
                addView(preview, LinearLayout.LayoutParams(0, -1, 1f))
                addView(ScrollView(this@MainActivity).apply {
                    addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(16, 0, 0, 0)
                        addView(status)
                        addView(TextView(this@MainActivity).apply { setText(R.string.exposure_label) })
                        addView(exposureInput)
                        addView(TextView(this@MainActivity).apply { setText(R.string.iso_label) })
                        addView(isoInput)
                        addView(TextView(this@MainActivity).apply { setText(R.string.focus_label) })
                        addView(focusInput)
                        addView(button)
                        addView(TextView(this@MainActivity).apply { setText(R.string.calibration_help) })
                    })
                }, LinearLayout.LayoutParams(0, -1, 1f))
            }
        )
        showIdle(getString(R.string.status_idle))

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 0)
        } else {
            autoStartIfRequested()
        }
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<String>, results: IntArray) {
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            autoStartIfRequested()
        } else {
            showIdle(getString(R.string.status_no_camera_permission))
        }
    }

    override fun onPause() {
        super.onPause()
        // The camera is taken away in the background, so a recording cannot outlive the screen.
        if (recording != null) {
            stop()
        }
    }

    private fun autoStartIfRequested() {
        val seconds = intent.getIntExtra("seconds", 0)
        if (seconds > 0 && recording == null) {
            start()
            ui.postDelayed({ if (recording != null) stop() }, seconds * 1000L)
        }
    }

    private fun start() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 0)
            return
        }
        val settings = try {
            CaptureSettings(
                (exposureInput.text.toString().toDouble() * 1e6).toLong(),
                isoInput.text.toString().toInt(), focusInput.text.toString().toFloat(),
            )
        } catch (e: IllegalArgumentException) { showIdle(e.message ?: "Invalid settings"); return }
        val cameraId = Recording.backCameraId(getSystemService(CameraManager::class.java))
        if (cameraId == null) {
            showIdle(getString(R.string.status_no_camera))
            return
        }

        val name = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        val dir = File(getExternalFilesDir(null), name).apply { mkdirs() }
        File(dir, "device.json").writeText(DeviceReport.build(this, cameraId).toString(2))

        recordingDir = dir
        startedAtMs = System.currentTimeMillis()
        val sensorOrientation = getSystemService(CameraManager::class.java).getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val displayDegrees = windowManager.defaultDisplay.rotation * 90
        val rotation = (sensorOrientation - displayDegrees + 360) % 360
        val r = Recording(this, dir, cameraId, settings) { bitmap ->
            ui.post {
                if (recording != null && recordingDir == dir) {
                    val transformed = if (rotation == 0) bitmap else Bitmap.createBitmap(
                        bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
                    preview.setImageBitmap(transformed)
                }
            }
        }
        recording = r
        try { r.start() } catch (e: Exception) {
            recording = null
            r.stop()
            File(dir, "ERROR").writeText(e.toString())
            showIdle(e.message ?: "Capture failed")
            return
        }
        listOf(exposureInput, isoInput, focusInput).forEach { it.isEnabled = false }
        button.setText(R.string.button_stop)
        ui.post(refreshStatus)
    }

    private fun stop() {
        val r = recording ?: return
        recording = null
        ui.removeCallbacks(refreshStatus)
        r.stop()
        // The marker tells a script pulling the directory that nothing is still being written.
        File(recordingDir, "DONE").writeText("")
        showIdle(
            getString(
                R.string.status_saved,
                r.frameCount.get(),
                r.gyroCount.get(),
                r.accelCount.get(),
                recordingDir?.absolutePath,
            )
        )
    }

    private fun showIdle(text: String) {
        status.text = text
        button.setText(R.string.button_start)
        listOf(exposureInput, isoInput, focusInput).forEach { it.isEnabled = true }
    }

    private fun numericInput(label: String, value: String) = EditText(this).apply {
        hint = label
        contentDescription = label
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        setText(value)
    }
}
