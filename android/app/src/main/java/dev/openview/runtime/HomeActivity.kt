// Copyright 2026, OpenView contributors.
// SPDX-License-Identifier: BSL-1.0
package dev.openview.runtime

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
        notice = label("", 14f, accent)
        column.addView(notice)
        permission = action(getString(R.string.home_allow_overlay)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        column.addView(permission)
        column.addView(action(getString(R.string.home_settings)) {
            startActivity(Intent(this, AboutActivity::class.java))
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
