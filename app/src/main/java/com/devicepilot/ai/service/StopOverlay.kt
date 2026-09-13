package com.devicepilot.ai.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A small draggable pill shown over other apps while the agent works:
 * "● DevicePilot: Step 3: Tap "Search"   [STOP]".
 * Requires SYSTEM_ALERT_WINDOW; silently does nothing if not granted.
 */
class StopOverlay(private val ctx: Context) {

    companion object {
        /** The currently shown overlay, so tools can hide it while reading the screen. */
        @Volatile var current: StopOverlay? = null
    }

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: LinearLayout? = null
    private var status: TextView? = null
    private var params: WindowManager.LayoutParams? = null

    fun show(onStop: () -> Unit) {
        if (!Settings.canDrawOverlays(ctx)) return
        if (root != null) return

        val dp = { v: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt() }

        val pill = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(8f), dp(8f), dp(8f))
            background = GradientDrawable().apply { cornerRadius = dp(24f).toFloat(); setColor(Color.parseColor("#E61E293B")); setStroke(dp(1f), Color.parseColor("#38BDF8")) }
            elevation = dp(6f).toFloat()
        }
        val dot = TextView(ctx).apply { text = "●"; setTextColor(Color.parseColor("#38BDF8")); textSize = 10f }
        status = TextView(ctx).apply {
            text = "DevicePilot working…"; setTextColor(Color.WHITE); textSize = 12f; maxLines = 1
            maxWidth = dp(200f); ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dp(6f), 0, dp(10f), 0)
        }
        val stop = TextView(ctx).apply {
            text = "STOP"; setTextColor(Color.WHITE); textSize = 12f; typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
            background = GradientDrawable().apply { cornerRadius = dp(16f).toFloat(); setColor(Color.parseColor("#F43F5E")) }
            setOnClickListener { onStop() }
        }
        pill.addView(dot); pill.addView(status); pill.addView(stop)

        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = dp(12f); y = dp(80f) }

        // Drag support
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        pill.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (e.rawX - downX).toInt(); lp.y = startY + (e.rawY - downY).toInt()
                    if (Math.abs(e.rawX - downX) > 8 || Math.abs(e.rawY - downY) > 8) moved = true
                    runCatching { wm.updateViewLayout(v, lp) }; true
                }
                MotionEvent.ACTION_UP -> { if (!moved) v.performClick(); true }
                else -> false
            }
        }

        try {
            wm.addView(pill, lp)
            root = pill; params = lp
            current = this
        } catch (_: Throwable) { root = null }
    }

    fun setStatus(text: String) {
        status?.post { status?.text = text }
    }

    fun hide() {
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        if (current === this) current = null
    }

    /** Hide temporarily (e.g. while reading the screen so the pill isn't in the dump). */
    fun setVisible(v: Boolean) { root?.post { root?.visibility = if (v) View.VISIBLE else View.GONE } }
}
