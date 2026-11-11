package com.gezimos.katapult.lockscreen

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/** The one surface/settle/close implementation. Content owns its own data and lifetime. */
internal class EdgePageWindow(private val context: Context, private val handler: Handler,
                               private val eligible: () -> Boolean, private val changed: () -> Unit,
                               private val title: String,
                               private val focusable: Boolean = true,
                               private val invertOnOpen: Boolean = true,
                               private val swipeClose: Boolean = true,
                               private val fitSystemBars: Boolean = false,
                               private val content: (() -> Unit, (Float, Float) -> Boolean, (Float, Float) -> Boolean) -> EdgePageView) : AutoCloseable {
    private val wm = context.getSystemService(WindowManager::class.java)
    private var view: EdgePageView? = null
    private var params: WindowManager.LayoutParams? = null
    private var removalFailed = false
    private var edge = ScreenEdge.LEFT
    private var flash: View? = null
    private val clean = Runnable { flash?.let { view?.removeView(it) }; flash = null }
    var opened = false
        private set

    private fun create(from: ScreenEdge): Boolean {
        if (view != null) return !removalFailed && edge == from
        if (!eligible()) return false
        edge = from
        return try {
            val root = content(::hide,
                { dx, dy -> swipeClose && EdgeSwipe.capturesPageClose(edge, dx, dy, EdgeSwipe.SLOP_DP * context.resources.displayMetrics.density) },
                { dx, dy -> swipeClose && EdgeSwipe.closesPage(edge, dx, dy, wm.maximumWindowMetrics.bounds.width(), EdgeSwipe.SLOP_DP * context.resources.displayMetrics.density) })
            // Own the content before any fallible setup/attachment, so hide always releases it.
            view = root
            root.setViewTreeLifecycleOwner(context as LifecycleOwner)
            root.setViewTreeViewModelStoreOwner(context as ViewModelStoreOwner)
            root.setViewTreeSavedStateRegistryOwner(context as SavedStateRegistryOwner)
            val p = WindowManager.LayoutParams().apply {
                type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY; format = PixelFormat.TRANSLUCENT
                width = -1; height = -1; gravity = Gravity.TOP or Gravity.LEFT
                flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                setFitInsetsTypes(0); setTitle(title)
            }
            params = p
            if (fitSystemBars) {
                fitToSystemBars(p)
                root.setOnApplyWindowInsetsListener { _, insets ->
                    if (fitToSystemBars(p) && root.isAttachedToWindow) wm.updateViewLayout(root, p)
                    insets
                }
            }
            root.translationX = if (from == ScreenEdge.LEFT) -wm.maximumWindowMetrics.bounds.width().toFloat() else wm.maximumWindowMetrics.bounds.width().toFloat()
            wm.addView(root, p)
            true
        } catch (error: RuntimeException) {
            android.util.Log.w("EdgePageWindow", "Could not attach page", error); hide(); false
        }
    }

    /** Accessibility overlays sit above the bars, so their own insets omit those sources.
     * Window metrics include all sources regardless of z-order; fit the window, not its content. */
    private fun fitToSystemBars(p: WindowManager.LayoutParams): Boolean {
        val metrics = wm.currentWindowMetrics
        val bars = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
        val width = metrics.bounds.width() - bars.left - bars.right
        val height = metrics.bounds.height() - bars.top - bars.bottom
        if (p.x == bars.left && p.y == bars.top && p.width == width && p.height == height) return false
        p.x = bars.left; p.y = bars.top; p.width = width; p.height = height
        return true
    }

    fun preview(from: ScreenEdge, step: Int) {
        if (opened) return
        if (step == 0) { hide(); return }
        if (!create(from)) return
        val remaining = wm.maximumWindowMetrics.bounds.width() * (1 - step.coerceIn(0, 6) / 6f)
        view?.translationX = if (from == ScreenEdge.LEFT) -remaining else remaining
    }

    fun settle(from: ScreenEdge) {
        if (opened) return
        if (!create(from)) return
        val root = view ?: return
        val p = params ?: return
        root.translationX = 0f
        p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        if (focusable) p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        try { wm.updateViewLayout(root, p) } catch (_: RuntimeException) { hide(); return }
        opened = true
        try { if (focusable) root.requestFocus(); root.settled() }
        catch (error: RuntimeException) { android.util.Log.w("EdgePageWindow", "Could not settle page", error); hide(); return }
        if (invertOnOpen) flashOnOpen()
        changed()
    }

    /** Full page without an edge preview; attachment/removal has the same owner. */
    fun show() = settle(ScreenEdge.LEFT)

    /** Clear the previous keyguard clock once on entry; scrolling never requests an inversion. */
    private fun flashOnOpen() {
        if (!opened) return
        val root = view ?: return
        handler.removeCallbacks(clean); clean.run()
        flash = View(context).apply { setBackgroundColor(com.gezimos.katapult.ui.Appearance.palette(context,
            com.gezimos.katapult.ui.Appearance.Surface.LOCK_SCREEN).inkArgb) }.also { root.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        handler.postDelayed(clean, 80)
    }

    fun cancelPreview() { if (!opened) hide() }
    fun hide() {
        val hadPage = view?.visibility == View.VISIBLE
        handler.removeCallbacks(clean); clean.run()
        val root = view
        root?.let {
            root.dispose()
            if (root.parent != null) try { wm.removeViewImmediate(root) }
            catch (error: RuntimeException) { android.util.Log.w("EdgePageWindow", "Could not remove page", error) }
        }
        removalFailed = root?.isAttachedToWindow == true
        if (removalFailed) root?.visibility = View.GONE else { view = null; params = null }
        opened = false
        if (hadPage) changed()
    }

    override fun close() = hide()
}
