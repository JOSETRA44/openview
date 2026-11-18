// Copyright 2026, OpenView contributors. SPDX-License-Identifier: BSL-1.0
package dev.openview.experience

import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.View.MeasureSpec
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A floating panel: ordinary Android views drawn into a texture that the
 * native renderer places in the space, the way a headset shows its 2D
 * windows. The views are never attached to a window; this class measures,
 * lays out and draws them itself.
 *
 * Threads: the views belong to the main thread. The render thread only
 * creates and updates the texture, and hit-tests against tile rectangles
 * that the main thread publishes after each layout.
 */
class XrPanel(private val context: Context, val widthPx: Int, val heightPx: Int) {
    /**
     * One thing the user can select by looking at it. A tile with [dwell] false
     * needs a tap or a button: looking at it is not enough. Leaving the space or
     * launching another app must not happen because a phone lay on a table
     * staring at that button.
     */
    class Tile(val view: TextView, val dwell: Boolean = true, val action: () -> Unit)

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var texture: SurfaceTexture? = null
    private var surface: Surface? = null
    private val frameAvailable = AtomicBoolean(false)
    private var hasContent = false

    private val root = FrameLayout(context)
    private var tiles: List<Tile> = emptyList()

    @Volatile private var tileRects: List<Rect> = emptyList()
    @Volatile private var hovered = -1

    /** Replaces the panel's content. Main thread. */
    fun setContent(content: View, tiles: List<Tile>) {
        root.removeAllViews()
        root.addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        this.tiles = tiles
        hovered = -1
        redraw()
    }

    /** Draws the views into the texture. Main thread. */
    fun redraw() {
        val target = synchronized(lock) { surface } ?: return
        try {
            root.measure(MeasureSpec.makeMeasureSpec(widthPx, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(heightPx, MeasureSpec.EXACTLY))
            root.layout(0, 0, widthPx, heightPx)
            val rects = tiles.map { tile ->
                val r = Rect()
                tile.view.getDrawingRect(r)
                root.offsetDescendantRectToMyCoords(tile.view, r)
                r
            }
            tileRects = rects
            tiles.forEachIndexed { index, tile -> tile.view.isSelected = index == hovered }
            val canvas = target.lockHardwareCanvas()
            try {
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                root.draw(canvas)
            } finally {
                target.unlockCanvasAndPost(canvas)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Panel draw failed", e)
        }
    }

    /** Render thread, EGL context current. */
    fun attach(textureId: Int): Boolean {
        synchronized(lock) {
            val created = SurfaceTexture(textureId)
            created.setDefaultBufferSize(widthPx, heightPx)
            created.setOnFrameAvailableListener({ frameAvailable.set(true) }, main)
            texture = created
            surface = Surface(created)
            hasContent = false
        }
        main.post { redraw() }
        return true
    }

    /** Render thread. Returns whether the texture holds a drawn panel. */
    fun update(transform: FloatArray): Boolean {
        synchronized(lock) {
            val current = texture ?: return false
            if (frameAvailable.getAndSet(false)) {
                current.updateTexImage()
                current.getTransformMatrix(transform)
                hasContent = true
            }
            return hasContent
        }
    }

    /**
     * Render thread. [u] and [v] run from the panel's bottom left corner.
     * Returns the tile under that point, or -1.
     */
    fun hover(u: Float, v: Float): Int {
        var hit = -1
        if (u in 0f..1f && v in 0f..1f) {
            val x = (u * widthPx).toInt()
            val y = ((1f - v) * heightPx).toInt()
            hit = tileRects.indexOfFirst { it.contains(x, y) }
        }
        if (hit != hovered) {
            hovered = hit
            main.post { redraw() }
        }
        return hit
    }

    /** Render thread. [fromDwell] is true when the gaze rested on the tile, false for a tap. */
    fun click(index: Int, fromDwell: Boolean) {
        main.post {
            val tile = tiles.getOrNull(index) ?: return@post
            if (fromDwell && !tile.dwell) {
                Log.i(TAG, "dwell ignored on a tap-only tile index=$index")
                return@post
            }
            tile.action()
        }
    }

    /** Render thread, EGL context current. */
    fun release() {
        synchronized(lock) {
            surface?.release(); surface = null
            texture?.release(); texture = null
            hasContent = false
            frameAvailable.set(false)
        }
    }

    companion object {
        private const val TAG = "OpenViewXR"
        val INK: Int = Color.rgb(232, 240, 244)
        val MUTED: Int = Color.rgb(150, 170, 182)
        val ACCENT: Int = Color.rgb(96, 226, 186)

        fun background(context: Context): GradientDrawable = GradientDrawable().apply {
            setColor(Color.argb(238, 18, 26, 34))
            cornerRadius = 44f
            setStroke(3, Color.argb(120, 120, 160, 176))
        }

        fun label(context: Context, text: String, sizePx: Float, color: Int = INK, bold: Boolean = false): TextView =
            TextView(context).apply {
                this.text = text
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, sizePx)
                setTextColor(color)
                if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                includeFontPadding = false
            }

        /** A selectable tile: lights up while looked at. */
        fun tile(context: Context, title: String, subtitle: String, icon: Drawable?, dwell: Boolean = true,
                 action: () -> Unit): Tile {
            val normal = GradientDrawable().apply { setColor(Color.argb(255, 34, 46, 58)); cornerRadius = 30f }
            val selected = GradientDrawable().apply {
                setColor(Color.argb(255, 44, 78, 82)); cornerRadius = 30f; setStroke(5, ACCENT)
            }
            val states = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_selected), selected)
                addState(intArrayOf(), normal)
            }
            val view = TextView(context).apply {
                val spanned = android.text.SpannableString("$title\n$subtitle")
                spanned.setSpan(android.text.style.RelativeSizeSpan(0.62f), title.length + 1, spanned.length, 0)
                spanned.setSpan(android.text.style.ForegroundColorSpan(MUTED), title.length + 1, spanned.length, 0)
                text = spanned
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 40f)
                setTextColor(INK)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(34, 20, 26, 20)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                this.background = states
                if (icon != null) {
                    icon.setBounds(0, 0, 96, 96)
                    setCompoundDrawables(icon, null, null, null)
                    compoundDrawablePadding = 30
                }
            }
            return Tile(view, dwell, action)
        }

        fun row(context: Context): LinearLayout = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    }
}
