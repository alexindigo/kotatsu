package com.gezimos.katapult.screenshot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.view.Gravity
import android.view.Choreographer
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.gezimos.katapult.R
import kotlin.math.roundToInt

/** Owns one noninteractive window, its image content and its one expiry callback. Main thread only. */
class ScreenshotPreviewWindow(
    private val context: Context,
    private val handler: Handler,
    private val wm: WindowManager = context.getSystemService(WindowManager::class.java),
) : AutoCloseable {
    companion object { const val DURATION_MS = 3000L; private const val HALO_DP = 3; private const val INSET_DP = 16 }
    private var root: FrameLayout? = null
    private var captures = 0
    private var announcementPending = false
    private val expire = Runnable { hide() }
    private fun dp(value: Float) = (value * context.resources.displayMetrics.density).roundToInt()
    private fun dp(value: Int) = dp(value.toFloat())
    private fun box(stroke: Int) = GradientDrawable().apply { setColor(Color.WHITE); setStroke(dp(stroke), Color.BLACK) }

    private fun label(text: String, bold: Boolean) = TextView(context).apply {
        this.text = text; setTextColor(Color.BLACK); textSize = 14f; includeFontPadding = false
        typeface = ResourcesCompat.getFont(context, if (bold) com.gezimos.katapult.paper.R.font.lato_bold else com.gezimos.katapult.paper.R.font.lato_regular)
        if (bold) maxLines = 1
        maxWidth = wm.maximumWindowMetrics.bounds.width() - dp(2 * INSET_DP + 24)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun saved(bitmap: Bitmap) {
        val width = (wm.maximumWindowMetrics.bounds.width() / 4).coerceAtLeast(1)
        val height = (width.toFloat() * bitmap.height / bitmap.width).roundToInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        // Always own a distinct thumbnail: callers release the full capture after this method.
        val next = if (scaled === bitmap) scaled.copy(Bitmap.Config.ARGB_8888, false) else scaled
        val image = ImageView(context).apply { setImageBitmap(next); scaleType = ImageView.ScaleType.FIT_XY }
        val frame = FrameLayout(context).apply {
            background = box(1); setPadding(dp(1), dp(1), dp(1), dp(1))
            addView(image, FrameLayout.LayoutParams(width, height))
        }
        show(context.getString(R.string.screenshot_saved_announcement), false) { card ->
            card.addView(frame, LinearLayout.LayoutParams(-2, -2))
            card.addView(label(context.getString(R.string.screenshot_saved), true), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4.5f) })
        }
        // The drawable owns the scaled bitmap. Render-thread display lists may outlive a
        // replaced View, so never recycle a bitmap that has been handed to the view hierarchy.
    }

    fun failed(reason: ScreenshotFailure) {
        val title = context.getString(R.string.screenshot_not_saved)
        val help = context.getString(if (reason == ScreenshotFailure.STORAGE_FULL) R.string.screenshot_storage_full else R.string.screenshot_unavailable)
        show("$title. $help", true) { card ->
            card.addView(label(title, true), LinearLayout.LayoutParams(-2, -2))
            card.addView(label(help, false), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(1.5f) })
        }
    }

    private fun show(announcement: String, failure: Boolean, content: (LinearLayout) -> Unit) {
        val container = root ?: FrameLayout(context).apply {
            setBackgroundColor(Color.WHITE); setPadding(dp(HALO_DP), dp(HALO_DP), dp(HALO_DP), dp(HALO_DP))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }.also { root = it }
        container.removeAllViews()
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; background = box(2)
            val horizontal = dp(if (failure) 10.5f else 6f)
            val vertical = dp(if (failure) 7.5f else 6f)
            setPadding(horizontal + dp(2), vertical + dp(2), horizontal + dp(2), vertical + dp(2))
        }
        content(card)
        container.addView(card, FrameLayout.LayoutParams(-2, -2))
        container.contentDescription = announcement
        container.visibility = if (captures == 0) View.VISIBLE else View.INVISIBLE
        try {
            // Registration precedes dispatchAttachedToWindow. A second result in the
            // same main-loop turn must reuse the already-registered window.
            if (container.parent == null) {
                val params = WindowManager.LayoutParams().apply {
                    type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY; format = PixelFormat.TRANSLUCENT
                    width = -2; height = -2; gravity = Gravity.BOTTOM or Gravity.LEFT
                    flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    x = dp(INSET_DP - HALO_DP); y = dp(INSET_DP - HALO_DP)
                    setFitInsetsTypes(0); setTitle(context.getString(R.string.screenshot_preview))
                }
                wm.addView(container, params)
            }
            announcementPending = true
            announce()
            handler.removeCallbacks(expire); handler.postDelayed(expire, DURATION_MS)
        } catch (error: RuntimeException) {
            android.util.Log.w("ScreenshotPreview", "Could not show preview", error); hide()
        }
    }

    /** Keep the same window attached, but let its hidden frame reach the compositor before capture. */
    fun beforeCapture(action: () -> Unit) {
        captures++
        val view = root
        if (view?.parent != null) {
            view.visibility = View.INVISIBLE
            Choreographer.getInstance().postFrameCallback {
                Choreographer.getInstance().postFrameCallback { action() }
            }
        } else action()
    }

    private fun announce() {
        val view = root ?: return
        if (captures == 0 && announcementPending) {
            announcementPending = false
            view.announceForAccessibility(view.contentDescription)
        }
    }

    fun captured() {
        captures = (captures - 1).coerceAtLeast(0)
        if (captures == 0) { root?.visibility = View.VISIBLE; announce() }
    }

    fun hide() {
        handler.removeCallbacks(expire)
        announcementPending = false
        val view = root
        if (view?.parent != null) try { wm.removeViewImmediate(view) }
        catch (error: RuntimeException) { android.util.Log.w("ScreenshotPreview", "Could not remove preview", error) }
        if (view?.parent != null) view.visibility = View.GONE else root = null
    }

    override fun close() { hide() }
}
