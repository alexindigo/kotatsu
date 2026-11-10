package com.gezimos.katapult.screenshot

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Handler
import android.view.Display
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** Each accepted chord captures once; saving is queued off main, never a gate on the next chord. */
class ScreenshotCapture(private val service: AccessibilityService, private val handler: Handler,
                        private val eligible: () -> Boolean,
                        private val preview: ScreenshotPreviewWindow = ScreenshotPreviewWindow(service, handler),
                        private val save: (Bitmap) -> Unit = { ScreenshotStore(service.contentResolver).save(it); Unit }) : AutoCloseable {
    private val worker = Executors.newSingleThreadExecutor()
    private var generation = 0L
    private var closed = false

    fun capture() {
        if (closed || !eligible()) return
        val token = generation
        preview.beforeCapture {
            if (closed || token != generation || !eligible()) { preview.captured(); return@beforeCapture }
            try {
                service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        preview.captured()
                        val buffer = result.hardwareBuffer
                        try {
                            worker.execute {
                                var hardware: Bitmap? = null
                                var bitmap: Bitmap? = null
                                try {
                                    hardware = checkNotNull(Bitmap.wrapHardwareBuffer(buffer, result.colorSpace))
                                    val software = checkNotNull(hardware.copy(Bitmap.Config.ARGB_8888, false))
                                    bitmap = software
                                    save(software)
                                    bitmap = null
                                    if (!handler.post {
                                        try { if (!closed && token == generation && eligible()) preview.saved(software) }
                                        finally { software.recycle() }
                                    }) software.recycle()
                                } catch (error: Exception) {
                                    report(token, screenshotFailure(error))
                                } finally { bitmap?.recycle(); hardware?.recycle(); buffer.close() }
                            }
                        } catch (_: RejectedExecutionException) { buffer.close() }
                    }

                    override fun onFailure(errorCode: Int) { preview.captured(); report(token, ScreenshotFailure.UNAVAILABLE) }
                })
            } catch (_: RuntimeException) { preview.captured(); report(token, ScreenshotFailure.UNAVAILABLE) }
        }
    }

    private fun report(token: Long, reason: ScreenshotFailure) {
        handler.post { if (!closed && token == generation && eligible()) preview.failed(reason) }
    }

    fun clear() { generation++; preview.hide() }
    override fun close() { closed = true; clear(); worker.shutdown() }
}
