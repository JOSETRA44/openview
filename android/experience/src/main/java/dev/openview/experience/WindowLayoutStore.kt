// Copyright 2026, OpenView contributors. SPDX-License-Identifier: BSL-1.0
package dev.openview.experience

import android.content.Context

/**
 * Where each window was left, so the space looks the same the next time it is
 * entered. Poses are in the experience's recentred space (x, y, z metres, yaw
 * radians); the native side owns their meaning, this class only keeps them.
 */
class WindowLayoutStore(context: Context) {
    private val prefs = context.getSharedPreferences("window_layout", Context.MODE_PRIVATE)

    /** Fills [out] with x, y, z, yaw and returns true when the window has a stored place. */
    fun load(window: Int, out: FloatArray): Boolean {
        if (!prefs.contains("w$window.x")) return false
        out[0] = prefs.getFloat("w$window.x", 0f)
        out[1] = prefs.getFloat("w$window.y", 0f)
        out[2] = prefs.getFloat("w$window.z", 0f)
        out[3] = prefs.getFloat("w$window.yaw", 0f)
        return true
    }

    fun save(window: Int, x: Float, y: Float, z: Float, yaw: Float) {
        prefs.edit().putFloat("w$window.x", x).putFloat("w$window.y", y)
            .putFloat("w$window.z", z).putFloat("w$window.yaw", yaw).apply()
    }
}
