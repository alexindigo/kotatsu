package com.gezimos.katapult.screenshot

import android.content.Context
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Handler
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.graphics.CanvasBufferedRenderer
import androidx.graphics.surface.SurfaceControlCompat
import androidx.hardware.SyncFenceCompat
import java.util.concurrent.Executor

/** The overlay hierarchy owns layout and accessibility. Only the child layer owns visible pixels. */
internal class ScreenshotPreviewPixels(
    context: Context,
    private val main: Handler,
    val content: FrameLayout,
    private val onReleased: () -> Unit,
    private val transaction: (show: Boolean) -> SurfaceControlCompat.Transaction,
    private val commitFrame: (SurfaceControlCompat.Transaction) -> Unit,
) : FrameLayout(context), SurfaceHolder.Callback, ViewTreeObserver.OnPreDrawListener {
    private val host = SurfaceView(context).apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        setZOrderOnTop(true)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        holder.addCallback(this@ScreenshotPreviewPixels)
    }
    private val executor = Executor { main.post(it) }
    private class Layer(val surface: SurfaceControlCompat) {
        val frames = mutableSetOf<RenderedFrame>()
        var hideQueued = false
        var uncertain: Any? = null
        var retired = false
    }
    private class RenderedFrame(
        val renderer: CanvasBufferedRenderer,
        val result: CanvasBufferedRenderer.RenderResult,
    ) : AutoCloseable {
        override fun close() {
            renderer.use { result.fence.use { renderer.releaseBuffer(result.hardwareBuffer) } }
        }
    }
    private var layer: Layer? = null
    private val layers = mutableSetOf<Layer>()
    val excluded get() = layers.none { it.frames.isNotEmpty() || it.uncertain != null }
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
                .also { layers.add(it) }
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
        if (previous.frames.isEmpty() && previous.uncertain == null) dispose(previous) else suppress(previous)
    }

    fun update() {
        dirty = true
        invalidate()
    }

    fun suppress(value: Boolean) {
        blocked = value
        importantForAccessibility = if (value) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            else View.IMPORTANT_FOR_ACCESSIBILITY_YES
        if (value) layers.forEach(::suppress) else update()
    }

    private fun suppress(target: Layer) {
        val uncertain = target.uncertain
        if (target.hideQueued || (target.frames.isEmpty() && uncertain == null)) return
        try {
            if (uncertain == null) {
                replaceWithHiddenBuffer(target)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // A superseded buffer can be released before it latches on the Java backend.
                // Use the hide transaction's own acknowledgement there.
                transaction(false).use {
                    it.setVisibility(target.surface, false).setBuffer(target.surface, null)
                        .addTransactionCommittedListener(executor, object : SurfaceControlCompat.TransactionCommittedListener {
                            override fun onTransactionCommitted() = acknowledgeHide(target, uncertain)
                        })
                    it.commit()
                    target.hideQueued = true
                }
            } else {
                // If commit threw, the frame may never get a release callback. Release a
                // hidden marker instead, which acknowledges this layer is no longer visible
                // regardless of whether the preceding publication reached the compositor.
                replaceWithHiddenBuffer(target, onRelease = { fence ->
                    finishOnMain(fence) { acknowledgeHide(target, uncertain) }
                })
                replaceWithHiddenBuffer(target)
            }
        } catch (error: RuntimeException) {
            // No acknowledged hide: a later capture or expiry can make a fresh attempt.
            Log.w(TAG, "Could not exclude preview layer", error)
        }
    }

    private fun replaceWithHiddenBuffer(
        target: Layer,
        onRelease: ((SyncFenceCompat) -> Unit)? = null,
    ) {
        // AndroidX on 30/31 registers completion for a non-null buffer only.
        HardwareBuffer.create(1, 1, HardwareBuffer.RGBA_8888, 1, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE).use { blank ->
            transaction(false).use {
                it.setVisibility(target.surface, false).setBuffer(target.surface, blank, releaseCallback = onRelease)
                it.commit()
                if (onRelease == null) target.hideQueued = true
            }
        }
    }

    private fun finishOnMain(fence: SyncFenceCompat, finish: () -> Unit) {
        try { fence.close() } finally { main.post(finish) }
    }

    private fun acknowledgeHide(target: Layer, uncertain: Any) {
        if (target.uncertain !== uncertain) return
        target.uncertain = null
        target.frames.toList().forEach { release(target, it) }
        settled(target)
    }

    private fun release(target: Layer, frame: RenderedFrame) {
        if (target.frames.remove(frame)) closeFrame(frame)
    }

    private fun closeFrame(frame: RenderedFrame) {
        try { frame.close() }
        catch (error: RuntimeException) { Log.w(TAG, "Could not close preview renderer", error) }
    }

    private fun settled(target: Layer) {
        if (target.retired && target.frames.isEmpty() && target.uncertain == null && layers.contains(target)) dispose(target)
        if (dirty) invalidate()
        if (excluded) onReleased()
    }
    override fun onPreDraw(): Boolean {
        val target = layer ?: return true
        if (blocked || !dirty || rendering || target.uncertain != null || target.frames.size >= 2 || width == 0 || height == 0) return true
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
                    val frame = RenderedFrame(renderer, result)
                    if (result.status == CanvasBufferedRenderer.RenderResult.SUCCESS && !blocked && layer === target &&
                        target.uncertain == null && content.width == w && content.height == h && !dirty) {
                        publish(target, frame)
                    } else {
                        closeFrame(frame)
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

    private fun publish(target: Layer, frame: RenderedFrame) {
        var handedOff = false
        try {
            transaction(true).use { update ->
                update.setVisibility(target.surface, true)
                    .setBuffer(target.surface, frame.result.hardwareBuffer, frame.result.fence) { fence ->
                        finishOnMain(fence) {
                            release(target, frame)
                            settled(target)
                        }
                    }
                // The scope owns the frame until commit can transfer it to the compositor.
                // Register before commit: the release callback may arrive immediately.
                target.frames.add(frame)
                handedOff = true
                target.hideQueued = false
                try { commitFrame(update) }
                catch (error: RuntimeException) {
                    target.uncertain = Any()
                    throw error
                }
            }
        } catch (error: RuntimeException) {
            Log.w(TAG, "Could not publish preview layer", error)
            if (target.uncertain != null) suppress(target)
        } finally {
            if (!handedOff) closeFrame(frame)
        }
    }

    private fun dispose(target: Layer) {
        layers.remove(target)
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
