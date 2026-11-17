// Copyright 2026, OpenView contributors. SPDX-License-Identifier: BSL-1.0
package dev.openview.experience

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.openview.experience.test.GuidedTestController
import dev.openview.experience.test.TestStep
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
    private external fun nativeAnchor(handle: Long)
    private external fun nativeCamera(handle: Long, enabled: Boolean)
    private external fun nativeActivate(handle: Long)
    private external fun nativeTestStep(handle: Long, step: Int)
    private external fun nativeDestroy(handle: Long)
    private val engineLock = Any()
    private var engine = 0L
    private var worker: Thread? = null
    private var resumed = false
    private var failed = false
    private var wantsCamera = false
    private lateinit var status: TextView
    private lateinit var diagnostic: TextView
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
    private var cameraDisplayDegrees = 0
    private var loggedCameraTransform = false
    private lateinit var panels: List<XrPanel>
    private lateinit var clockText: TextView
    private lateinit var detailText: TextView
    private lateinit var diagText: TextView
    private lateinit var statusDiagText: TextView
    private lateinit var guideTitle: TextView
    private lateinit var guideDetail: TextView
    private lateinit var guidedTest: GuidedTestController
    /** Index of the running test step, mirrored to native code whenever an engine starts. */
    private var testStep = -1
    private var pendingTest = false
    private val uiHandler = Handler(android.os.Looper.getMainLooper())
    private val clockTick = object : Runnable {
        override fun run() {
            updateStatusPanel()
            uiHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wantsCamera = savedInstanceState?.getBoolean("camera") ?: intent.getBooleanExtra("camera", false)
        // `adb shell am start -n dev.openview.experience/.MainActivity --ez test true` starts the guided test.
        pendingTest = savedInstanceState == null && intent.getBooleanExtra("test", false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        status = TextView(this).apply {
            text = "OpenView · Preparando tu espacio…\nMira un botón del panel durante 1,25 s para elegirlo, " +
                "o toca la pantalla.\nVolumen +: centrar · Volumen −: cámara · Atrás: salir"
            textSize = 18f; setTextColor(Color.rgb(164, 234, 207)); gravity = Gravity.CENTER
        }
        diagnostic = TextView(this).apply {
            text = "Pos: — · sin sesión · -- fps"
            textSize = 20f; setTextColor(XrPanel.ACCENT); gravity = Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(13, 23, 30)); setPadding(24, 24, 24, 24)
            addView(status)
            addView(diagnostic)
            addView(Button(this@MainActivity).apply {
                text = "Activar objeto señalado"; setOnClickListener { activateObject() }
            })
            addView(Button(this@MainActivity).apply {
                text = "Centrar vista"; setOnClickListener { recenter() }
            })
            addView(Button(this@MainActivity).apply {
                text = "Fijar punto aquí"; setOnClickListener { anchorPoint() }
            })
            addView(Button(this@MainActivity).apply {
                text = "Prueba de seguimiento"; setOnClickListener { startGuidedTest() }
            })
            addView(Button(this@MainActivity).apply {
                text = "Cámara / espacio"; setOnClickListener { setCamera(!wantsCamera) }
            })
            addView(Button(this@MainActivity).apply { text = "Salir"; setOnClickListener { finish() } })
        })
        buildPanels()
        guidedTest = GuidedTestController(this, guidedTestListener)
    }

    /*
     * The home: what the user sees floating in front of them. Panel 0 is the
     * launcher, panel 1 the status column beside it. Sizes are in texture
     * pixels; the native side decides where they hang and how big they look.
     */
    private fun buildPanels() {
        panels = listOf(XrPanel(this, 1280, 800), XrPanel(this, 560, 800), XrPanel(this, 1000, 400))
        clockText = XrPanel.label(this, "", 150f, XrPanel.INK, bold = true)
        detailText = XrPanel.label(this, "", 34f, XrPanel.MUTED).apply { setLineSpacing(14f, 1f) }
        statusDiagText = XrPanel.label(this, "Pos: 0.00, 0.00, 0.00 m · NO TRACKED · -- fps", 24f, XrPanel.ACCENT, bold = true)
        panels[1].setContent(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = XrPanel.background(this@MainActivity)
            setPadding(48, 56, 48, 48)
            addView(clockText)
            addView(detailText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 24 })
            addView(statusDiagText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 24 })
        }, emptyList())
        guideTitle = XrPanel.label(this, "", 72f, XrPanel.INK, bold = true).apply { gravity = Gravity.CENTER }
        guideDetail = XrPanel.label(this, "", 34f, XrPanel.ACCENT).apply { gravity = Gravity.CENTER }
        panels[2].setContent(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            background = XrPanel.background(this@MainActivity)
            setPadding(40, 30, 40, 30)
            addView(guideTitle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(guideDetail, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 22 })
        }, emptyList())
        refreshHomePanel()
        updateStatusPanel()
    }

    private val guidedTestListener = object : GuidedTestController.Listener {
        override fun onStep(index: Int, step: TestStep?) {
            testStep = index
            synchronized(engineLock) { if (engine != 0L) nativeTestStep(engine, index) }
        }
        override fun onTick(step: TestStep, index: Int, count: Int, secondsLeft: Int) {
            guideTitle.text = step.title
            guideDetail.text = "Paso ${index + 1} de $count · $secondsLeft s"
            status.text = "Prueba de seguimiento · ${step.title} · $secondsLeft s"
            panels[2].redraw()
        }
        override fun onFinished(cancelled: Boolean) {
            status.text = if (cancelled) "Prueba cancelada." else "Prueba terminada. Los datos quedaron en el registro."
        }
    }

    /** Recentres first, so that "forward" in the log is where the user faces now. */
    private fun startGuidedTest() {
        if (guidedTest.running) return
        recenter()
        guidedTest.start()
    }

    /** Apps that declare themselves immersive OpenXR apps, the same way headsets find them. */
    private fun immersiveApps(): List<android.content.pm.ResolveInfo> {
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory("org.khronos.openxr.intent.category.IMMERSIVE_HMD")
        return try {
            packageManager.queryIntentActivities(intent, 0)
                .filter { it.activityInfo.packageName != packageName }
                .sortedBy { it.loadLabel(packageManager).toString().lowercase() }
        } catch (e: Exception) {
            Log.e(TAG, "App query failed", e); emptyList()
        }
    }

    private fun refreshHomePanel() {
        val tiles = ArrayList<XrPanel.Tile>()
        tiles += XrPanel.tile(this, if (wantsCamera) "Volver al espacio" else "Ver mi entorno",
            if (wantsCamera) "Cierra la cámara" else "Cámara del teléfono", null) {
            setCamera(!wantsCamera); refreshHomePanel()
        }
        tiles += XrPanel.tile(this, "Centrar vista", "Pone el panel frente a ti", null) { recenter() }
        tiles += XrPanel.tile(this, "Fijar punto aquí", "Deja un marcador donde miras", null) { anchorPoint() }
        tiles += XrPanel.tile(this, "Prueba de seguimiento", "Te guía paso a paso", null) { startGuidedTest() }
        // Launching and leaving need a tap: a gaze alone must not end the session.
        for (app in immersiveApps().take(3)) {
            val label = app.loadLabel(packageManager).toString()
            val icon = try { app.loadIcon(packageManager) } catch (e: Exception) { null }
            tiles += XrPanel.tile(this, label, "Toca para abrir", icon, dwell = false) { launch(app) }
        }
        tiles += XrPanel.tile(this, "Salir", "Toca para cerrar OpenView", null, dwell = false) { finish() }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = XrPanel.background(this@MainActivity)
            setPadding(52, 36, 52, 28)
            addView(XrPanel.label(this@MainActivity, "OpenView", 56f, XrPanel.INK, bold = true))
            val currentDiag = if (::diagText.isInitialized) diagText.text.toString() else "Pos: 0.00, 0.00, 0.00 m · NO TRACKED · -- fps"
            diagText = XrPanel.label(this@MainActivity, currentDiag, 28f, XrPanel.ACCENT, bold = true)
            addView(diagText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = 6; bottomMargin = 8 })
            addView(XrPanel.label(this@MainActivity, "Inicio · mira un botón para elegirlo", 26f, XrPanel.MUTED),
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = 18 })
        }
        for (pair in tiles.chunked(2)) {
            val row = XrPanel.row(this)
            pair.forEachIndexed { index, tile ->
                row.addView(tile.view, LinearLayout.LayoutParams(0, 170, 1f).apply { if (index == 0) rightMargin = 24 })
            }
            if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, 170, 1f))
            column.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 22 })
        }
        panels[0].setContent(column, tiles)
    }

    private fun launch(app: android.content.pm.ResolveInfo) {
        try {
            startActivity(android.content.Intent(android.content.Intent.ACTION_MAIN)
                .setClassName(app.activityInfo.packageName, app.activityInfo.name)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.e(TAG, "Could not launch ${app.activityInfo.packageName}", e)
        }
    }

    private fun updateStatusPanel() {
        val now = java.util.Date()
        clockText.text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(now)
        val battery = getSystemService(android.os.BatteryManager::class.java)
            ?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val date = java.text.SimpleDateFormat("EEEE d 'de' MMMM", java.util.Locale("es")).format(now)
        detailText.text = buildString {
            append(date.replaceFirstChar { it.uppercase() })
            if (battery >= 0) append("\nBatería $battery %")
            append(if (wantsCamera) "\nViendo tu entorno" else "\nEspacio virtual")
            append("\n\nVolumen +  centrar\nVolumen −  entorno\nTocar  elegir")
        }
        panels[1].redraw()
    }

    // The next five are invoked only on the render thread, with its EGL context current.
    @Suppress("unused")
    fun createPanelTexture(panel: Int, textureId: Int): Boolean = panels.getOrNull(panel)?.attach(textureId) ?: false

    @Suppress("unused")
    fun updatePanelTexture(panel: Int, transform: FloatArray): Boolean = panels.getOrNull(panel)?.update(transform) ?: false

    @Suppress("unused")
    fun panelHover(panel: Int, u: Float, v: Float): Int = panels.getOrNull(panel)?.hover(u, v) ?: -1

    @Suppress("unused")
    fun panelClick(panel: Int, index: Int, fromDwell: Boolean) { panels.getOrNull(panel)?.click(index, fromDwell) }

    @Suppress("unused")
    fun releasePanelTextures() { panels.forEach { it.release() } }

    @Suppress("unused")
    fun getPanelAspect(panel: Int): Float = panels.getOrNull(panel)?.let { it.widthPx.toFloat() / it.heightPx } ?: 1f

    @Suppress("unused")
    fun updateDiagnostic(x: Float, y: Float, z: Float, tracked: Boolean, fps: Float) {
        uiHandler.post {
            val status = if (tracked) "POSITION_TRACKED" else "NO TRACKED"
            val text = String.format(java.util.Locale.US, "Pos: %.2f, %.2f, %.2f m · %s · %.0f fps", x, y, z, status, fps)
            diagnostic.text = text
            if (::diagText.isInitialized) {
                diagText.text = text
                if (panels.isNotEmpty()) panels[0].redraw()
            }
            if (::statusDiagText.isInitialized) {
                statusDiagText.text = text
                if (panels.size > 1) panels[1].redraw()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        refreshHomePanel()
        uiHandler.removeCallbacks(clockTick); uiHandler.post(clockTick)
        cameraAllowed.set(checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        startEngine()
        if (wantsCamera && !cameraAllowed.get()) requestPermissions(arrayOf(Manifest.permission.CAMERA), 10)
        if (pendingTest) {
            pendingTest = false
            // Give the session a moment to reach FOCUSED so the recentre lands.
            uiHandler.postDelayed({ if (resumed) startGuidedTest() }, 1500)
        }
    }

    private fun startEngine() {
        synchronized(engineLock) {
            if (worker != null || !resumed || failed || isFinishing || isDestroyed) return
            engine = nativeCreate()
            nativeCamera(engine, wantsCamera && cameraAllowed.get())
            nativeTestStep(engine, testStep)
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
        uiHandler.removeCallbacks(clockTick)
        guidedTest.cancel()
        synchronized(engineLock) { if (engine != 0L) nativeStop(engine) }
        // Render thread owns the SurfaceTexture and releases it while EGL remains current.
        closeCameraDevice()
        super.onPause()
    }
    override fun onDestroy() {
        guidedTest.shutdown()
        super.onDestroy()
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
    fun anchorPoint() { synchronized(engineLock) { if (engine != 0L) nativeAnchor(engine) } }
    private fun activateObject() { synchronized(engineLock) { if (engine != 0L) nativeActivate(engine) } }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Some compositors own the touch window. Dwell remains available in that case.
        if (event.actionMasked == MotionEvent.ACTION_UP) { activateObject(); return true }
        return event.actionMasked == MotionEvent.ACTION_DOWN || super.onTouchEvent(event)
    }
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
            if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                keyCode == KeyEvent.KEYCODE_SPACE) { activateObject(); return true }
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
                val sensorDegrees = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                @Suppress("DEPRECATION")
                val displayRotation = windowManager.defaultDisplay.rotation
                cameraDisplayDegrees = when (displayRotation) {
                    Surface.ROTATION_90 -> 90
                    Surface.ROTATION_180 -> 180
                    Surface.ROTATION_270 -> 270
                    else -> 0
                }
                // A direct Camera2 SurfaceTexture already carries the sensor-to-natural-display
                // transform. Only display rotation remains; applying sensor rotation again doubles it.
                val swapDimensions = (sensorDegrees - cameraDisplayDegrees + 360) % 180 != 0
                cameraPreviewAspect = if (swapDimensions) size.height.toFloat() / size.width
                    else size.width.toFloat() / size.height
                loggedCameraTransform = false
                Log.i(TAG, "camera orientation sensor=$sensorDegrees display=$cameraDisplayDegrees " +
                    "sampleRotation=${-cameraDisplayDegrees} buffer=${size.width}x${size.height} aspect=$cameraPreviewAspect " +
                    "sensorTransform=SurfaceTexture")
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
                                                    if (Build.VERSION.SDK_INT >= 31 && characteristics.get(
                                                            CameraCharacteristics.SCALER_AVAILABLE_ROTATE_AND_CROP_MODES
                                                        )?.contains(CaptureRequest.SCALER_ROTATE_AND_CROP_NONE) == true) {
                                                        set(CaptureRequest.SCALER_ROTATE_AND_CROP, CaptureRequest.SCALER_ROTATE_AND_CROP_NONE)
                                                    }
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
    fun getCameraDisplayRotation(): Int = synchronized(cameraLock) { cameraDisplayDegrees }

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
                if (!loggedCameraTransform) {
                    loggedCameraTransform = true
                    Log.i(TAG, "camera SurfaceTexture matrix=" + transform.joinToString(","))
                }
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
