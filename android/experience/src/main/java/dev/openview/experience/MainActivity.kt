// Copyright 2026, OpenView contributors. SPDX-License-Identifier: BSL-1.0
package dev.openview.experience

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Android owns lifecycle/permissions/camera; native code is an ordinary OpenXR application. */
class MainActivity : Activity() {
    companion object {
        init { System.loadLibrary("openxr_loader"); System.loadLibrary("openview-experience") }
        private const val TAG = "OpenViewXR"
    }
    private external fun nativeCreate(): Long
    private external fun nativeRun(handle: Long): String?
    private external fun nativeStop(handle: Long)
    private external fun nativeRecenter(handle: Long)
    private external fun nativeCamera(handle: Long, enabled: Boolean)
    private external fun nativeDestroy(handle: Long)
    private val engineLock = Any()
    private var engine = 0L
    private var worker: Thread? = null
    private var resumed = false
    private var failed = false
    private var wantsCamera = false
    private lateinit var status: TextView
    private val cameraLock = Any()
    private var cameraThread: HandlerThread? = null
    private var camera: CameraDevice? = null
    private var capture: CameraCaptureSession? = null
    private var cameraSurface: Surface? = null
    private var cameraTexture: SurfaceTexture? = null
    private var cameraGeneration = 0
    private val cameraAvailable = AtomicBoolean(false)
    private val cameraFrames = AtomicLong(0)
    private val cameraAllowed = AtomicBoolean(false)
    private val cameraFailed = AtomicBoolean(false)
    private var lastCameraFrameAt = 0L
    private var cameraPreviewAspect = 16f / 9f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wantsCamera = savedInstanceState?.getBoolean("camera") ?: intent.getBooleanExtra("camera", false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        status = TextView(this).apply {
            text = "OpenView · Preparando tu espacio…\nVolumen +: centrar · Volumen −: cámara · Atrás: salir"
            textSize = 18f; setTextColor(Color.rgb(164, 234, 207)); gravity = Gravity.CENTER
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(13, 23, 30)); setPadding(24, 24, 24, 24)
            addView(status)
            addView(Button(this@MainActivity).apply {
                text = "Centrar vista"; setOnClickListener { recenter() }
            })
            addView(Button(this@MainActivity).apply {
                text = "Cámara / espacio"; setOnClickListener { setCamera(!wantsCamera) }
            })
            addView(Button(this@MainActivity).apply { text = "Salir"; setOnClickListener { finish() } })
        })
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        cameraAllowed.set(checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        startEngine()
        if (wantsCamera && !cameraAllowed.get()) requestPermissions(arrayOf(Manifest.permission.CAMERA), 10)
    }

    private fun startEngine() {
        synchronized(engineLock) {
            if (worker != null || !resumed || failed || isFinishing || isDestroyed) return
            engine = nativeCreate()
            nativeCamera(engine, wantsCamera && cameraAllowed.get())
            val handle = engine
            worker = Thread({
                val error = nativeRun(handle)
                synchronized(engineLock) {
                    engine = 0
                    nativeDestroy(handle)
                    worker = null
                }
                runOnUiThread {
                    if (error != null) {
                        failed = true
                        status.text = "No se pudo abrir el espacio.\n$error\nVuelve a OpenView y revisa los permisos del visor."
                    } else startEngine()
                }
            }, "OpenView-render").also { it.start() }
        }
    }

    override fun onPause() {
        resumed = false
        synchronized(engineLock) { if (engine != 0L) nativeStop(engine) }
        // Render thread owns the SurfaceTexture and releases it while EGL remains current.
        closeCameraDevice()
        super.onPause()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("camera", wantsCamera)
        super.onSaveInstanceState(outState)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == 10) {
            val granted = results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED
            cameraAllowed.set(granted)
            if (!granted) {
                wantsCamera = false
                Log.i(TAG, "Camera permission denied; continuing in space mode")
                status.text = "Cámara no autorizada. Puedes seguir explorando el espacio."
            }
            synchronized(engineLock) { if (engine != 0L) nativeCamera(engine, wantsCamera && granted) }
        }
    }
    private fun recenter() { synchronized(engineLock) { if (engine != 0L) nativeRecenter(engine) } }
    private fun setCamera(enabled: Boolean) {
        wantsCamera = enabled
        if (enabled && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 10)
        } else synchronized(engineLock) { if (engine != 0L) nativeCamera(engine, enabled) }
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.repeatCount == 0) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) { recenter(); return true }
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) { setCamera(!wantsCamera); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    // Invoked only on the render thread, with its EGL context current.
    @Suppress("unused")
    fun createCameraTexture(textureId: Int): Boolean {
        if (!cameraAllowed.get()) return false
        synchronized(cameraLock) {
            val manager = getSystemService(CameraManager::class.java)
            try {
                val cameraId = manager.cameraIdList.firstOrNull {
                    manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                } ?: return false
                val characteristics = manager.getCameraCharacteristics(cameraId)
                val sizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?.getOutputSizes(SurfaceTexture::class.java) ?: return false
                val size = sizes.filter { it.width <= 1280 && it.height <= 720 }
                    .maxByOrNull { it.width * it.height } ?: sizes.minBy { it.width * it.height }
                cameraPreviewAspect = size.width.toFloat() / size.height
                val thread = HandlerThread("OpenView-camera").also { it.start() }
                cameraThread = thread
                val handler = Handler(thread.looper)
                val generation = ++cameraGeneration
                cameraFrames.set(0); cameraAvailable.set(false); cameraFailed.set(false)
                lastCameraFrameAt = android.os.SystemClock.elapsedRealtime()
                val texture = SurfaceTexture(textureId).apply {
                    setDefaultBufferSize(size.width, size.height)
                    setOnFrameAvailableListener({
                        synchronized(cameraLock) {
                            if (generation == cameraGeneration) {
                                cameraAvailable.set(true); cameraFrames.incrementAndGet()
                                lastCameraFrameAt = android.os.SystemClock.elapsedRealtime()
                            }
                        }
                    }, handler)
                }
                cameraTexture = texture
                val surface = Surface(texture)
                cameraSurface = surface
                manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        synchronized(cameraLock) {
                            if (generation != cameraGeneration) { device.close(); return }
                            camera = device
                            try {
                                device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                                    override fun onConfigured(session: CameraCaptureSession) {
                                        synchronized(cameraLock) {
                                            if (generation != cameraGeneration) { session.close(); return }
                                            capture = session
                                            try {
                                                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                                    addTarget(surface)
                                                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                                                }
                                                session.setRepeatingRequest(request.build(), null, handler)
                                                Log.i(TAG, "camera streaming ${size.width}x${size.height}; monoscopic background")
                                            } catch (e: Exception) {
                                                cameraFailed.set(true); Log.e(TAG, "Camera request failed", e)
                                            }
                                        }
                                    }
                                    override fun onConfigureFailed(session: CameraCaptureSession) {
                                        synchronized(cameraLock) {
                                            if (generation == cameraGeneration) cameraFailed.set(true)
                                        }
                                        Log.e(TAG, "Camera session configuration failed")
                                    }
                                }, handler)
                            } catch (e: Exception) { cameraFailed.set(true); Log.e(TAG, "Camera session failed", e) }
                        }
                    }
                    override fun onDisconnected(device: CameraDevice) {
                        synchronized(cameraLock) { if (generation == cameraGeneration) cameraFailed.set(true) }
                        device.close(); Log.i(TAG, "camera disconnected")
                    }
                    override fun onError(device: CameraDevice, error: Int) {
                        synchronized(cameraLock) { if (generation == cameraGeneration) cameraFailed.set(true) }
                        device.close(); Log.e(TAG, "camera error=$error")
                    }
                }, handler)
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Camera unavailable; space remains usable", e)
                return false
            }
        }
    }

    @Suppress("unused")
    fun getCameraAspect(): Float = synchronized(cameraLock) { cameraPreviewAspect }

    @Suppress("unused")
    fun updateCameraTexture(transform: FloatArray): Long {
        synchronized(cameraLock) {
            // A frozen last frame must not masquerade as a live view of the surroundings.
            if (cameraFailed.get() || android.os.SystemClock.elapsedRealtime() - lastCameraFrameAt > 3000) {
                runOnUiThread {
                    wantsCamera = false
                    status.text = "La cámara dejó de enviar imágenes. Puedes seguir en el espacio y volver a activarla."
                }
                return -1
            }
            if (cameraAvailable.getAndSet(false)) {
                cameraTexture?.updateTexImage()
                cameraTexture?.getTransformMatrix(transform)
            }
            return cameraFrames.get()
        }
    }
    private fun closeCameraDevice() {
        synchronized(cameraLock) {
            ++cameraGeneration
            capture?.close(); capture = null
            camera?.close(); camera = null
            cameraThread?.quitSafely(); cameraThread = null
        }
    }
    @Suppress("unused")
    fun releaseCameraTexture() {
        closeCameraDevice()
        synchronized(cameraLock) {
            cameraSurface?.release(); cameraSurface = null
            cameraTexture?.release(); cameraTexture = null
            cameraAvailable.set(false)
        }
    }
}
