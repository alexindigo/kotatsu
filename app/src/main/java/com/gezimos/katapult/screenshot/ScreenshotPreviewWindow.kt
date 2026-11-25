package com.gezimos.katapult.screenshot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.graphics.surface.SurfaceControlCompat
import com.gezimos.katapult.R
import com.gezimos.katapult.ui.Appearance
import kotlin.math.roundToInt

/** Owns one noninteractive window, its image content and its one expiry callback. Main thread only. */
class ScreenshotPreviewWindow internal constructor(
    private val context: Context,
    private val handler: Handler,
    private val wm: WindowManager,
    private val commitFrame: (SurfaceControlCompat.Transaction) -> Unit = SurfaceControlCompat.Transaction::commit,
    private val transaction: (show: Boolean) -> SurfaceControlCompat.Transaction,
) : AutoCloseable {
    constructor(context: Context, handler: Handler, wm: WindowManager = context.getSystemService(WindowManager::class.java)) :
        this(context, handler, wm, transaction = { SurfaceControlCompat.Transaction() })

    companion object {
        const val DURATION_MS = 3000L
        private const val EXCLUSION_TIMEOUT_MS = 3000L
        private const val HALO_DP = 3
        private const val INSET_DP = 16
    }
    private val main = Handler(Looper.getMainLooper())
    private var root: ScreenshotPreviewPixels? = null
    private var removing = false
    private var captures = 0
    private val waiting = mutableMapOf<Runnable, (Boolean) -> Unit>()
    private var announcementPending = false
    private val palette get() = Appearance.palette(context, Appearance.Surface.APP)
    private val colours = mutableListOf<() -> Unit>()
    private var appearance: AutoCloseable? = null
    private val expire = Runnable { hide() }
    private fun dp(value: Float) = (value * context.resources.displayMetrics.density).roundToInt()
    private fun dp(value: Int) = dp(value.toFloat())
    private fun box(stroke: Int) = GradientDrawable().apply {
        val redraw = { setColor(palette.paperArgb); setStroke(dp(stroke), palette.inkArgb) }
        colours.add(redraw); redraw()
    }

    private fun label(text: String, bold: Boolean) = TextView(context).apply {
        this.text = text; textSize = 14f; includeFontPadding = false
        val redraw = { setTextColor(palette.inkArgb) }
        colours.add(redraw); redraw()
        typeface = ResourcesCompat.getFont(context, if (bold) com.gezimos.katapult.paper.R.font.lato_bold else com.gezimos.katapult.paper.R.font.lato_regular)
        if (bold) maxLines = 1
        maxWidth = wm.maximumWindowMetrics.bounds.width() - dp(2 * INSET_DP + 24)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun saved(bitmap: Bitmap) {
        colours.clear()
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
        colours.clear()
        val title = context.getString(R.string.screenshot_not_saved)
        val help = context.getString(if (reason == ScreenshotFailure.STORAGE_FULL) R.string.screenshot_storage_full else R.string.screenshot_unavailable)
        show("$title. $help", true) { card ->
            card.addView(label(title, true), LinearLayout.LayoutParams(-2, -2))
            card.addView(label(help, false), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(1.5f) })
        }
    }

    private fun show(announcement: String, failure: Boolean, content: (LinearLayout) -> Unit) {
        removing = false
        val window = root ?: ScreenshotPreviewPixels(context, main, FrameLayout(context).apply {
            setBackgroundColor(palette.paperArgb)
            setPadding(dp(HALO_DP), dp(HALO_DP), dp(HALO_DP), dp(HALO_DP))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, ::excluded, transaction, commitFrame).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }.also { root = it }
        val container = window.content
        container.removeAllViews()
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; background = box(2)
            val horizontal = dp(if (failure) 10.5f else 6f)
            val vertical = dp(if (failure) 7.5f else 6f)
            setPadding(horizontal + dp(2), vertical + dp(2), horizontal + dp(2), vertical + dp(2))
        }
        content(card)
        container.addView(card, FrameLayout.LayoutParams(-2, -2))
        if (appearance == null) appearance = Appearance.observe(context, Appearance.Surface.APP) { value ->
            root?.content?.setBackgroundColor(value.paperArgb)
            colours.forEach { it() }
            root?.update()
        }
        window.contentDescription = announcement
        window.suppress(captures > 0)
        try {
            // Registration precedes dispatchAttachedToWindow. A second result in the
            // same main-loop turn must reuse the already-registered window.
            if (window.parent == null) {
                val params = WindowManager.LayoutParams().apply {
                    type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY; format = PixelFormat.TRANSLUCENT
                    width = -2; height = -2; gravity = Gravity.BOTTOM or Gravity.LEFT
                    flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    x = dp(INSET_DP - HALO_DP); y = dp(INSET_DP - HALO_DP)
                    setFitInsetsTypes(0); setTitle(context.getString(R.string.screenshot_preview))
                }
                wm.addView(window, params)
            }
            announcementPending = true
            announce()
            handler.removeCallbacks(expire); handler.postDelayed(expire, DURATION_MS)
        } catch (error: RuntimeException) {
            android.util.Log.w("ScreenshotPreview", "Could not show preview", error); hide()
        }
    }

    /** Keep the preview suppressed until each issued capture has acquired its pixels. */
    fun beforeCapture(action: (Boolean) -> Unit) {
        captures++
        root?.suppress(true)
        if (root?.excluded != false) {
            action(true)
        } else {
            val timeout = object : Runnable {
                override fun run() { waiting.remove(this)?.invoke(false) }
            }
            waiting[timeout] = action
            // Exceptional acknowledged failure, never a fallback to a possibly contaminated capture.
            main.postDelayed(timeout, EXCLUSION_TIMEOUT_MS)
        }
    }

    private fun excluded() {
        if (removing) removeExcludedWindow()
        val acknowledgements = waiting.toMap()
        waiting.clear()
        for ((timeout, action) in acknowledgements) {
            main.removeCallbacks(timeout)
            action(true)
        }
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
        if (captures == 0 && !removing) { root?.suppress(false); announce() }
    }

    fun hide() {
        appearance?.close(); appearance = null
        colours.clear()
        handler.removeCallbacks(expire)
        announcementPending = false
        removing = true
        root?.suppress(true)
        if (root?.excluded != false) removeExcludedWindow()
    }

    private fun removeExcludedWindow() {
        val view = root
        if (view?.parent != null) try { wm.removeViewImmediate(view) }
        catch (error: RuntimeException) { android.util.Log.w("ScreenshotPreview", "Could not remove preview", error) }
        if (view?.parent != null) view.visibility = View.GONE else root = null
    }

    override fun close() { hide() }
}
