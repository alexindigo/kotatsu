package com.gezimos.katapult

import android.app.UiAutomation
import android.content.Intent
import android.graphics.Color
import android.hardware.HardwareBuffer
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.FrameLayout
import androidx.graphics.surface.SurfaceControlCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.screenshot.ScreenshotPreviewPixels
import com.gezimos.katapult.screenshot.allocateHiddenPreviewBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// The activity-backed probe also runs on API 30 without the service settings fixture.
class ScreenshotPreviewCompositionInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun hiddenReplacementBufferHasBothCompositorUsageBits() {
        val required = HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_COMPOSER_OVERLAY
        allocateHiddenPreviewBuffer().use { buffer ->
            assertEquals("Hidden replacement buffer usage", required, buffer.usage and required)
        }
    }

    @Test fun hiddenReplacementReleasesPreviewAndExcludesItsPixels() {
        val ui = PlatformUiDriver(instrumentation)
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val backdropColor = Color.rgb(0, 32, 64)
        val previewColor = Color.MAGENTA
        val released = CountDownLatch(1)
        val (backdrop, preview) = fixtureMain {
            val background = FrameLayout(activity).apply { setBackgroundColor(backdropColor) }
            val content = FrameLayout(activity).apply { setBackgroundColor(previewColor) }
            val pixels = ScreenshotPreviewPixels(activity, Handler(Looper.getMainLooper()), content,
                onExcluded = {}, transaction = { SurfaceControlCompat.Transaction() },
                commitFrame = SurfaceControlCompat.Transaction::commit, deliverFrameRelease = { it() })
            activity.setContentView(background)
            background to pixels
        }
        var waiter: AutoCloseable? = null
        withFixtureCleanup({ fixtureMain { waiter?.close(); preview.suppress(true); activity.finish() } }) {
            val point = IntArray(2)
            val size = 120
            fixtureMain {
                backdrop.addView(preview, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
                preview.suppress(true)
            }
            ui.await("Preview bounds were not laid out") {
                fixtureMain {
                    preview.getLocationOnScreen(point)
                    preview.width == size && preview.height == size
                }
            }
            fun pixel(): Int {
                val frame = checkNotNull(automation.takeScreenshot())
                return withFixtureCleanup({ frame.recycle() }) {
                    frame.getPixel(point[0] + size / 2, point[1] + size / 2)
                }
            }
            ui.await("Backdrop did not reach the display") { pixel() == backdropColor }
            fixtureMain { preview.suppress(false) }
            ui.await("Preview did not reach the display") { pixel() == previewColor }
            fixtureMain {
                assertFalse("Displayed frame has no outstanding lease", preview.excluded)
                preview.suppress(true)
                waiter = preview.awaitExclusion { released.countDown() }
                assertNotEquals("Exclusion was reported before the frame release", 0L, released.count)
            }
            assertTrue("Compositor did not release the visible preview buffer", released.await(5, TimeUnit.SECONDS))
            assertEquals("Preview pixels remained after exclusion", backdropColor, pixel())
        }
    }
}
