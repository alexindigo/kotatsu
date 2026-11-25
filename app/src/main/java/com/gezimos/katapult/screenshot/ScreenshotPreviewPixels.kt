package com.gezimos.katapult.screenshot

import android.content.Context
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.os.Handler
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.graphics.CanvasBufferedRenderer
import androidx.graphics.surface.SurfaceControlCompat
import java.util.concurrent.Executor

/** The overlay hierarchy owns layout and accessibility. Only the child layer owns visible pixels. */
internal class ScreenshotPreviewPixels(
    context: Context,
    private val main: Handler,
    val content: FrameLayout,
    private val onReleased: () -> Unit,
) : FrameLayout(context), SurfaceHolder.Callback, ViewTreeObserver.OnPreDrawListener {
    private val host = SurfaceView(context).apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        setZOrderOnTop(true)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        holder.addCallback(this@ScreenshotPreviewPixels)
    }
    private val executor = Executor { main.post(it) }
    private class Layer(val surface: SurfaceControlCompat) {
        var submitted = 0
        var suppressed = false
        var retired = false
    }
    private var layer: Layer? = null
    private var pending = 0
    val excluded get() = pending == 0
    private var blocked = false
    private var dirty = false
    private var rendering = false

    init {
        addView(content, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(host, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Size the window from the actual card. Measuring a MATCH_PARENT SurfaceView as
        // a peer in a wrap-content FrameLayout can otherwise expand the overlay to the display.
        content.measure(widthMeasureSpec, heightMeasureSpec)
        setMeasuredDimension(content.measuredWidthAndState, content.measuredHeightAndState)
        host.measure(MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY))
    }

    // Never put the card in WindowManager's own buffer. It may continue to be composed even
    // after WindowManager considers the window hidden or gone (exit animations, queued work).
    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean =
        child !== content && super.drawChild(canvas, child, drawingTime)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(this)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(this)
        super.onDetachedFromWindow()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        layer = try {
            Layer(SurfaceControlCompat.Builder().setName("Kotatsu screenshot preview pixels").setParent(host).build())
        } catch (error: RuntimeException) {
            Log.w(TAG, "Could not create preview layer", error)
            null
        }
        update()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = update()

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        val previous = layer ?: return
        layer = null
        previous.retired = true
        if (previous.submitted == 0) dispose(previous) else suppress(previous)
    }

    fun update() {
        dirty = true
        invalidate()
    }

    fun suppress(value: Boolean) {
        blocked = value
        importantForAccessibility = if (value) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            else View.IMPORTANT_FOR_ACCESSIBILITY_YES
        if (value) layer?.let(::suppress) else update()
    }

    private fun suppress(target: Layer) {
        if (target.suppressed || target.submitted == 0) return
        target.suppressed = true
        try {
            // An explicit replacement is necessary: on 30/31 AndroidX registers the
            // transaction-completion listener only for a non-null buffer. The replacement
            // is never displayed: the same atomic transaction hides this layer.
            HardwareBuffer.create(1, 1, HardwareBuffer.RGBA_8888, 1, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE).use { blank ->
                SurfaceControlCompat.Transaction().use {
                    it.setVisibility(target.surface, false).setBuffer(target.surface, blank).commit()
                }
            }
        } catch (error: RuntimeException) {
            // Keep the submitted buffer outstanding. The capture owner fails closed.
            Log.w(TAG, "Could not exclude preview layer", error)
        }
    }

    override fun onPreDraw(): Boolean {
        val target = layer ?: return true
        if (blocked || !dirty || rendering || target.submitted >= 2 || width == 0 || height == 0) return true
        val w = content.width
        val h = content.height
        if (w == 0 || h == 0) return true
        dirty = false
        try {
            val node = RenderNode("Screenshot preview").apply {
                setPosition(0, 0, w, h)
                val canvas = beginRecording()
                try { content.draw(canvas) } finally { endRecording() }
            }
            val renderer = CanvasBufferedRenderer.Builder(w, h).build()
            try {
                rendering = true
                renderer.setContentRoot(node)
                renderer.obtainRenderRequest().drawAsync(executor) { result ->
                    rendering = false
                    if (result.status == CanvasBufferedRenderer.RenderResult.SUCCESS && !blocked && layer === target &&
                        content.width == w && content.height == h && !dirty) {
                        publish(target, renderer, result)
                    } else {
                        renderFinished(renderer, result)
                    }
                    if (dirty) invalidate()
                }
            } catch (error: RuntimeException) {
                rendering = false
                renderer.close()
                throw error
            }
        } catch (error: RuntimeException) {
            Log.w(TAG, "Could not render preview layer", error)
        }
        return true
    }

    private fun publish(target: Layer, renderer: CanvasBufferedRenderer, result: CanvasBufferedRenderer.RenderResult) {
        target.submitted++
        pending++
        try {
            SurfaceControlCompat.Transaction().use { transaction ->
                transaction.setVisibility(target.surface, true).setBuffer(target.surface, result.hardwareBuffer, result.fence) { fence ->
                    // Release on the UI thread, after the producer's commit has returned. We
                    // never reuse this renderer's buffer; the compositor holds its own handle.
                    fence.close()
                    main.post {
                        renderFinished(renderer, result)
                        target.submitted--
                        pending--
                        if (target.retired && target.submitted == 0) dispose(target)
                        if (dirty) invalidate()
                        if (excluded) onReleased()
                    }
                }.commit()
            }
            target.suppressed = false
        } catch (error: RuntimeException) {
            // Commit may have crossed Binder. Do not claim the buffer is excluded on error.
            Log.w(TAG, "Could not publish preview layer", error)
            suppress(target)
        }
    }

    private fun renderFinished(renderer: CanvasBufferedRenderer, result: CanvasBufferedRenderer.RenderResult) {
        renderer.releaseBuffer(result.hardwareBuffer)
        result.fence?.close()
        renderer.close()
    }

    private fun dispose(target: Layer) {
        try {
            SurfaceControlCompat.Transaction().use { it.reparent(target.surface, null as SurfaceControlCompat?).commit() }
        } catch (error: RuntimeException) {
            Log.w(TAG, "Could not detach excluded preview layer", error)
        } finally {
            target.surface.release()
        }
    }

    private companion object { const val TAG = "ScreenshotPreview" }
}
