// Copyright 2026, OpenView contributors. SPDX-License-Identifier: BSL-1.0
package dev.openview.runtime.tracking

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Log
import android.view.Display
import android.view.Surface
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.TrackingState

/**
 * Head position from ARCore for the runtime's Android device.
 *
 * Native code (tracking/arcore) calls [start] and [stop] from the tracking
 * factory and registers [nativePushPose]. ARCore runs here, on a Java thread
 * with a tiny EGL context of its own, because its update needs a GL texture
 * and its C API calls into Java classes that a native thread cannot load.
 *
 * Poses are ARCore's display oriented camera pose: world Y up, camera -Z
 * ahead of the screen, which is the convention the runtime outputs.
 */
object ArCoreTracker {
    private const val TAG = "OpenViewARCore"

    @JvmStatic
    private external fun nativePushPose(qx: Float, qy: Float, qz: Float, qw: Float,
                                        x: Float, y: Float, z: Float, tracked: Boolean)

    @Volatile private var running = false
    private var thread: Thread? = null
    private var session: Session? = null

    /** Whether ARCore runs. False keeps the runtime on its other trackers. Any thread. */
    @JvmStatic
    @Synchronized
    fun start(context: Context): Boolean {
        if (running) return true
        val app = context.applicationContext
        if (app.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "no camera permission")
            return false
        }
        if (!installed(app)) return false
        val created = try {
            Session(app).also { s ->
                val config = Config(s).apply {
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    focusMode = Config.FocusMode.FIXED
                    planeFindingMode = Config.PlaneFindingMode.DISABLED
                    lightEstimationMode = Config.LightEstimationMode.DISABLED
                }
                s.configure(config)
            }
        } catch (e: Exception) {
            Log.w(TAG, "session could not be created: ${e.javaClass.simpleName}: ${e.message}")
            return false
        }
        session = created
        running = true
        thread = Thread({ loop(app, created) }, "OpenView-ARCore").also { it.start() }
        Log.i(TAG, "started")
        return true
    }

    @JvmStatic
    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        thread?.join(2000)
        thread = null
        session = null
        Log.i(TAG, "stopped")
    }

    /** ARCore is installed and this device is supported; the check can take a moment. */
    private fun installed(context: Context): Boolean {
        var availability = ArCoreApk.getInstance().checkAvailability(context)
        var waited = 0
        while (availability.isTransient && waited < 2000) {
            Thread.sleep(100); waited += 100
            availability = ArCoreApk.getInstance().checkAvailability(context)
        }
        Log.i(TAG, "availability $availability")
        return availability == ArCoreApk.Availability.SUPPORTED_INSTALLED
    }

    private fun loop(context: Context, session: Session) {
        val egl = MinimalEgl()
        try {
            egl.makeCurrent()
            val texture = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
            session.setCameraTextureName(texture)
            // The XR view is landscape; ask for poses oriented like the display shows it.
            val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
            val rotation = display?.rotation?.takeIf { it == Surface.ROTATION_90 || it == Surface.ROTATION_270 }
                ?: Surface.ROTATION_90
            session.setDisplayGeometry(rotation, 1920, 1080)
            session.resume()
            Log.i(TAG, "camera running, display rotation $rotation")

            var lastTimestamp = 0L
            var lastState: TrackingState? = null
            var poses = 0
            var windowStart = System.nanoTime()
            val raw = FloatArray(7)
            while (running) {
                val frame = session.update()
                if (frame.timestamp == lastTimestamp) {
                    Thread.sleep(4)
                    continue
                }
                lastTimestamp = frame.timestamp
                val camera = frame.camera
                val state = camera.trackingState
                if (state != lastState) {
                    val reason = if (state == TrackingState.PAUSED) " (${camera.trackingFailureReason})" else ""
                    Log.i(TAG, "tracking $state$reason")
                    lastState = state
                }
                if (state == TrackingState.TRACKING) {
                    val pose = camera.displayOrientedPose
                    pose.getRotationQuaternion(raw, 0)
                    pose.getTranslation(raw, 4)
                    nativePushPose(raw[0], raw[1], raw[2], raw[3], raw[4], raw[5], raw[6], true)
                    poses++
                } else {
                    nativePushPose(0f, 0f, 0f, 1f, 0f, 0f, 0f, false)
                }
                val now = System.nanoTime()
                if (now - windowStart > 5_000_000_000L) {
                    Log.i(TAG, "status: $state, $poses poses in 5 s, position %.2f %.2f %.2f m"
                        .format(java.util.Locale.US, raw[4], raw[5], raw[6]))
                    poses = 0; windowStart = now
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "stopped by ${e.javaClass.simpleName}: ${e.message}")
            try { nativePushPose(0f, 0f, 0f, 1f, 0f, 0f, 0f, false) } catch (_: Throwable) {}
        } finally {
            try { session.pause() } catch (_: Exception) {}
            session.close()
            egl.release()
            running = false
        }
    }

    /** A 1x1 pbuffer: ARCore wants a current GL context and an external texture, nothing is drawn. */
    private class MinimalEgl {
        private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        private var context = EGL14.EGL_NO_CONTEXT
        private var surface = EGL14.EGL_NO_SURFACE

        fun makeCurrent() {
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize" }
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            val attributes = intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE)
            check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) { "eglChooseConfig" }
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            surface = EGL14.eglCreatePbufferSurface(display, configs[0],
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent" }
        }

        fun release() {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
        }
    }
}
