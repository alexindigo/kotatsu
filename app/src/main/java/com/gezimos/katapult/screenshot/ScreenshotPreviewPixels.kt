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
    private val onExcluded: (ScreenshotPreviewPixels) -> Unit,
    private val transaction: (show: Boolean) -> SurfaceControlCompat.Transaction,
    private val commitFrame: (SurfaceControlCompat.Transaction) -> Unit,
    private val deliverFrameRelease: (() -> Unit) -> Unit,
    private val buildLayer: (SurfaceControlCompat.Builder) -> SurfaceControlCompat = SurfaceControlCompat.Builder::build,
) : FrameLayout(context), SurfaceHolder.Callback, ViewTreeObserver.OnPreDrawListener {
    private val host = SurfaceView(context).apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        setZOrderOnTop(true)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        holder.addCallback(this@ScreenshotPreviewPixels)
    }
    private val executor = Executor { main.post(it) }
    // A Layer instance is a generation. An acknowledgement resolves a specific lease within it.
    private class Layer(val surface: SurfaceControlCompat) {
        val frames = mutableSetOf<FrameLease>()
        var hideQueued = false
        var uncertain: Lease? = null
        var retired = false
    }
    private open class Lease(val generation: Layer)
    private class FrameLease(generation: Layer, val frame: RenderedFrame) : Lease(generation)
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
    private val waiters = mutableMapOf<() -> Unit, MutableSet<Lease>>()
    val excluded get() = layers.none { it.frames.isNotEmpty() || it.uncertain != null }
    private var blocked = false
    private var dirty = false
    private var rendering = false
    private var creationAttemptsLeft = 0
    private var retryPublication = false
    private val retry = Runnable { drive() }
    private val needsLayer get() = !blocked && dirty && layer == null && creationAttemptsLeft > 0

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
        creationAttemptsLeft = MAX_CREATION_ATTEMPTS
        update()
        drive()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = update()

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        creationAttemptsLeft = 0
        val previous = layer
        layer = null
        retryPublication = false
        if (previous != null) {
            previous.retired = true
            if (previous.frames.isEmpty() && previous.uncertain == null) dispose(previous)
        }
        drive()
    }

    fun update() {
        dirty = true
        retryPublication = false
        invalidate()
    }

    fun awaitExclusion(action: () -> Unit): AutoCloseable {
        val dependencies = mutableSetOf<Lease>()
        for (generation in layers) {
            dependencies.addAll(generation.frames)
            generation.uncertain?.let(dependencies::add)
        }
        if (dependencies.isEmpty()) action() else waiters[action] = dependencies
        return AutoCloseable { waiters.remove(action) }
    }

    fun suppress(value: Boolean) {
        blocked = value
        importantForAccessibility = if (value) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            else View.IMPORTANT_FOR_ACCESSIBILITY_YES
        if (!value) update() else retryPublication = false
        drive()
    }

    private fun drive() {
        main.removeCallbacks(retry)
        val needsHide = layers.filter {
            !it.hideQueued && (it.uncertain != null || it.frames.isNotEmpty() && (blocked || it.retired))
        }
        for (target in needsHide) suppress(target)
        if (needsLayer) tryCreateLayer()
        if (retryPublication && !blocked && layer?.uncertain == null) {
            retryPublication = false
            invalidate()
        }
        if (needsLayer || needsHide.any { !it.hideQueued }) scheduleRetry()
    }

    private fun tryCreateLayer() {
        // Creation is optional: an uncreated layer has no pixels or exclusion leases.
        // Only a new host surface replenishes the budget, not draws or later results.
        creationAttemptsLeft--
        try {
            layer = Layer(buildLayer(SurfaceControlCompat.Builder().setName("Kotatsu screenshot preview pixels").setParent(host)))
                .also { layers.add(it) }
            invalidate()
        } catch (error: RuntimeException) {
            Log.w(TAG, "Could not create preview layer", error)
        }
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
            // The owner still wants this hide. drive() will revisit it without user input.
            Log.w(TAG, "Could not exclude preview layer", error)
        }
    }

    private fun replaceWithHiddenBuffer(
        target: Layer,
        onRelease: ((SyncFenceCompat) -> Unit)? = null,
    ) {
        // AndroidX on 30/31 registers completion for a non-null buffer only.
        allocateHiddenPreviewBuffer().use { blank ->
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

    private fun acknowledgeHide(target: Layer, uncertain: Lease) {
        if (target.uncertain !== uncertain) return
        target.uncertain = null
        val completed = target.frames.toSet() + uncertain
        target.frames.toList().forEach(::release)
        settled(target, completed)
        if (retryPublication) drive()
    }

    private fun release(lease: FrameLease): Boolean {
        if (!lease.generation.frames.remove(lease)) return false
        closeFrame(lease.frame)
        return true
    }

    private fun closeFrame(frame: RenderedFrame) {
        try { frame.close() }
        catch (error: RuntimeException) { Log.w(TAG, "Could not close preview renderer", error) }
    }

    private fun settled(target: Layer, completed: Set<Lease>) {
        if (target.retired && target.frames.isEmpty() && target.uncertain == null && layers.contains(target)) dispose(target)
        val ready = waiters.filterValues { dependencies ->
            dependencies.removeAll(completed)
            dependencies.isEmpty()
        }.keys.toList()
        ready.forEach { waiters.remove(it) }
        if (dirty && !retryPublication) invalidate()
        if (excluded) onExcluded(this)
        ready.forEach { it() }
    }
    override fun onPreDraw(): Boolean {
        val target = layer ?: return true
        if (blocked || !dirty || retryPublication || rendering || target.uncertain != null || target.frames.size >= 2 || width == 0 || height == 0) return true
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
                        if (result.status != CanvasBufferedRenderer.RenderResult.SUCCESS && !dirty) retryRender(target)
                    }
                    if (dirty && !retryPublication) invalidate()
                }
            } catch (error: RuntimeException) {
                rendering = false
                renderer.close()
                throw error
            }
        } catch (error: RuntimeException) {
            Log.w(TAG, "Could not render preview layer", error)
            retryRender(target)
        }
        return true
    }

    private fun publish(target: Layer, frame: RenderedFrame) {
        var handedOff = false
        val lease = FrameLease(target, frame)
        try {
            transaction(true).use { update ->
                update.setVisibility(target.surface, true)
                    .setBuffer(target.surface, frame.result.hardwareBuffer, frame.result.fence) { fence ->
                        finishOnMain(fence) {
                            deliverFrameRelease {
                                if (release(lease)) settled(lease.generation, setOf(lease))
                            }
                        }
                    }
                // The scope owns the frame until commit can transfer it to the compositor.
                // Register before commit: the release callback may arrive immediately.
                target.frames.add(lease)
                handedOff = true
                target.hideQueued = false
                try { commitFrame(update) }
                catch (error: RuntimeException) {
                    target.uncertain = Lease(target)
                    throw error
                }
            }
        } catch (error: RuntimeException) {
            Log.w(TAG, "Could not publish preview layer", error)
            retryRender(target)
            if (target.uncertain != null) drive()
        } finally {
            if (!handedOff) closeFrame(frame)
        }
    }

    private fun retryRender(target: Layer) {
        if (blocked || layer !== target) return
        dirty = true
        retryPublication = true
        scheduleRetry()
    }

    private fun scheduleRetry() {
        if (!main.hasCallbacks(retry)) main.postDelayed(retry, RETRY_MS)
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

    private companion object {
        const val TAG = "ScreenshotPreview"
        const val RETRY_MS = 100L
        const val MAX_CREATION_ATTEMPTS = 3
    }
}

// SurfaceControlCompat.setBuffer requires both usages, including for hidden replacements.
internal fun allocateHiddenPreviewBuffer(): HardwareBuffer = HardwareBuffer.create(
    1, 1, HardwareBuffer.RGBA_8888, 1,
    HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_COMPOSER_OVERLAY,
)
