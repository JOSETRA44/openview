// Copyright 2026, OpenView contributors.
// SPDX-License-Identifier: BSL-1.0
package dev.openview.runtime

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.TimeUnit
import org.freedesktop.monado.android_common.AboutActivity

/** Product entry point. The experience remains an independent standard OpenXR client. */
class HomeActivity : Activity() {
    private val ink = Color.rgb(229, 237, 239)
    private val muted = Color.rgb(152, 171, 180)
    private val accent = Color.rgb(147, 227, 198)
    private lateinit var notice: TextView
    private lateinit var permission: Button

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun label(value: String, size: Float, color: Int = ink) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        setPadding(0, dp(7), 0, dp(7))
    }
    private fun surface(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(18).toFloat()
    }
    private fun action(title: String, primary: Boolean = false, click: () -> Unit) = Button(this).apply {
        text = title
        textSize = 16f
        isAllCaps = false
        setTextColor(if (primary) Color.rgb(15, 39, 35) else ink)
        background = surface(if (primary) accent else Color.rgb(31, 48, 58))
        minHeight = dp(58)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(10)
            bottomMargin = dp(4)
        }
        setOnClickListener { click() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The SLAM frameserver needs the camera granted before the runtime
        // service tries to open it; without it tracking stays 3DoF.
        if (checkSelfPermission(android.Manifest.permission.CAMERA) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 1)
        }
        window.statusBarColor = Color.rgb(13, 23, 30)
        window.navigationBarColor = Color.rgb(13, 23, 30)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(26), dp(22), dp(26), dp(28))
        }
        column.addView(label("OPENVIEW", 15f, accent).apply {
            letterSpacing = .22f
            typeface = Typeface.DEFAULT_BOLD
        })
        column.addView(label(getString(R.string.home_title), 36f).apply {
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        })
        column.addView(label(getString(R.string.home_intro), 16f, muted))
        column.addView(SpatialPreview().apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(-1, dp(170)).apply { topMargin = dp(12) }
        })
        column.addView(action(getString(R.string.home_enter), true) { enter(false) })
        column.addView(action(getString(R.string.home_camera)) { enter(true) })
        column.addView(label(getString(R.string.home_camera_hint), 13f, muted))
        column.addView(label(getString(R.string.home_controls), 13f, muted))
        notice = label("", 14f, accent)
        column.addView(notice)
        permission = action(getString(R.string.home_allow_overlay)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        column.addView(permission)
        column.addView(action(getString(R.string.home_settings)) {
            startActivity(Intent(this, AboutActivity::class.java))
        })
        column.addView(action(getString(R.string.home_diagnostics)) {
            showDiagnostics()
        })
        column.addView(label(getString(R.string.home_tracking_hint), 12f, muted).apply {
            gravity = Gravity.CENTER
        })
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.rgb(13, 23, 30))
            isFillViewport = true
            addView(column)
        })
    }

    override fun onResume() {
        super.onResume()
        val ready = Settings.canDrawOverlays(this)
        permission.visibility = if (ready) View.GONE else View.VISIBLE
        notice.text = getString(if (ready) R.string.home_ready else R.string.home_permission_hint)
    }

    private fun enter(camera: Boolean) {
        if (!Settings.canDrawOverlays(this)) {
            notice.text = getString(R.string.home_permission_hint)
            permission.requestFocus()
            return
        }
        try {
            startActivity(Intent().setClassName("dev.openview.experience", "dev.openview.experience.MainActivity")
                .putExtra("camera", camera))
        } catch (_: ActivityNotFoundException) {
            notice.text = getString(R.string.home_missing_experience)
        }
    }

    private fun runLogcat(cmd: Array<String>): List<String>? {
        return try {
            val process = Runtime.getRuntime().exec(cmd)
            val lines = process.inputStream.bufferedReader().use { it.readLines() }
            val finished = process.waitFor(3, TimeUnit.SECONDS)
            if (finished && process.exitValue() == 0 && lines.isNotEmpty()) lines else null
        } catch (_: Exception) {
            null
        }
    }

    private fun showDiagnostics() {
        try {
            val uid = Process.myUid()
            var lines = runLogcat(arrayOf("logcat", "-d", "--uid=$uid"))
            if (lines.isNullOrEmpty()) {
                val pid = Process.myPid()
                lines = runLogcat(arrayOf("logcat", "-d", "--pid=$pid"))
            }
            val text = when {
                lines == null -> "No se pudo obtener el registro de logcat."
                lines.isEmpty() -> "No hay registros disponibles para UID $uid."
                else -> lines.takeLast(300).joinToString("\n")
            }
            displayDiagnosticDialog(text)
        } catch (e: Exception) {
            displayDiagnosticDialog("Error al ejecutar diagnóstico: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun displayDiagnosticDialog(logs: String) {
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(Color.rgb(13, 23, 30))

            val scroll = ScrollView(this@HomeActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(360)
                )
                setBackgroundColor(Color.rgb(20, 36, 45))
                val pad = dp(10)
                setPadding(pad, pad, pad, pad)
                addView(TextView(this@HomeActivity).apply {
                    text = logs
                    textSize = 11f
                    typeface = Typeface.MONOSPACE
                    setTextColor(ink)
                    setTextIsSelectable(true)
                })
            }
            addView(scroll)

            val shareButton = action(getString(R.string.home_diag_share), primary = true) {
                shareLogs(logs)
            }
            addView(shareButton)
        }

        try {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.home_diagnostics))
                .setView(dialogView)
                .setNegativeButton(getString(R.string.home_diag_close), null)
                .show()
        } catch (e: Exception) {
            notice.text = "Error al mostrar diálogo: ${e.message}"
        }
    }

    private fun shareLogs(logs: String) {
        try {
            val sendIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, logs)
                type = "text/plain"
            }
            startActivity(Intent.createChooser(sendIntent, getString(R.string.home_diag_share)))
        } catch (e: Exception) {
            notice.text = "Error al compartir registros: ${e.message}"
        }
    }

    /** Static illustration; the real stereo scene is rendered by the OpenXR client. */
    private inner class SpatialPreview : View(this@HomeActivity) {
        private val pen = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            pen.color = Color.rgb(20, 36, 45)
            canvas.drawRoundRect(0f, 0f, w, h, dp(18).toFloat(), dp(18).toFloat(), pen)
            pen.color = Color.rgb(42, 65, 72)
            pen.strokeWidth = dp(1).toFloat()
            for (i in -5..5) canvas.drawLine(w / 2 + i * w / 14, h * .52f, w / 2 + i * w / 4, h, pen)
            for (f in floatArrayOf(.55f, .61f, .71f, .85f)) canvas.drawLine(0f, h * f, w, h * f, pen)
            pen.color = accent
            canvas.drawCircle(w * .5f, h * .36f, h * .16f, pen)
            pen.color = Color.rgb(66, 111, 110)
            canvas.drawCircle(w * .76f, h * .47f, h * .06f, pen)
            pen.color = Color.rgb(198, 176, 137)
            canvas.drawRoundRect(w * .2f, h * .43f, w * .3f, h * .62f, 5f, 5f, pen)
        }
    }
}
