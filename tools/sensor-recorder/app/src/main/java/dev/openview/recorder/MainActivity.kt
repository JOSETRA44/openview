// Copyright 2026, OpenView contributors.
// SPDX-License-Identifier: BSL-1.0

package dev.openview.recorder

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
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
                    )
                ui.postDelayed(this, 250)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        status = TextView(this).apply { textSize = 16f }
        button = Button(this).apply { setOnClickListener { if (recording == null) start() else stop() } }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(48, 48, 48, 48)
                addView(status)
                addView(button)
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
        val cameraId = Recording.backCameraId(getSystemService(CameraManager::class.java))
        if (cameraId == null) {
            showIdle(getString(R.string.status_no_camera))
            return
        }

        val name = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dir = File(getExternalFilesDir(null), name).apply { mkdirs() }
        File(dir, "device.json").writeText(DeviceReport.build(this, cameraId).toString(2))

        recordingDir = dir
        startedAtMs = System.currentTimeMillis()
        recording = Recording(this, dir, cameraId).apply { start() }
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
    }
}
