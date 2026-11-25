package com.gezimos.katapult

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.KeyEvent
import android.widget.TextView
import android.widget.ImageView
import androidx.graphics.surface.SurfaceControlCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.lockscreen.LockscreenWidgetService
import com.gezimos.katapult.recent.HomeKeyFilterState
import com.gezimos.katapult.screenshot.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.roundToInt

class ScreenshotInstrumentedTest {
    @get:org.junit.Rule val nativeState = ConnectedUiStateRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val ui = PlatformUiDriver(instrumentation)
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun shell(command: String) = automation.acknowledgedShell(command)
    private fun read(command: String) = automation.readShell(command)
    private fun capture(name: String) {
        val bitmap = automation.takeScreenshot()
        png(bitmap, name)
        bitmap.recycle()
    }
    private fun png(bitmap: Bitmap, name: String) {
        File(context.getExternalFilesDir(null), name).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
    private fun committedFrame(view: View) {
        val committed = CountDownLatch(1)
        main {
            assertTrue(view.isHardwareAccelerated)
            view.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
            view.invalidate()
        }
        assertTrue("Preview frame was not committed", committed.await(5, TimeUnit.SECONDS))
    }

    private fun capturePreviewPixels(view: View, name: String, paper: Int, ink: Int) {
        val pos = IntArray(2)
        main { view.getLocationOnScreen(pos) }
        val halo = (3 * view.resources.displayMetrics.density).roundToInt()
        ui.await("Preview did not reach the display with the expected halo and frame") {
            val image = automation.takeScreenshot()
            withFixtureCleanup({ image.recycle() }) {
                val x = pos[0] + view.width / 2
                val y = pos[1]
                if (image.getPixel(x, y) != paper || image.getPixel(x, y + halo) != ink) false
                else { png(image, name); true }
            }
        }
    }

    private fun withService(test: (LockscreenWidgetService, MainActivity) -> Unit) = ConnectedUiStateFixture().use {
        var accessibility: ActionServiceSettingsFixture? = null
        val raw = context.getSharedPreferences("katapult_prefs", 0)
        val before = raw.all
        val animationScales = listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")
            .associateWith { read("settings get global $it") }
        var activity: MainActivity? = null
        withFixtureCleanup(
            { ConnectedUiStateFixture.closeOwnedActivities() },
            { accessibility?.close() },
            { restoreFixturePreferences(raw, before) },
            *animationScales.map { (key, value) -> {
                if (value == "null") shell("settings delete global $key") else shell("settings put global $key $value")
                Unit
            } }.toTypedArray(),
        ) {
            ConnectedUiStateFixture.prepareForegroundService()
            // Compare captures of a stable app, not two different phases of Android's entry animation.
            animationScales.keys.forEach { shell("settings put global $it 0") }
            raw.edit().putBoolean("onboarding_complete", true).putBoolean("collect_usage_stats", true)
                .putBoolean("home_button_recent_apps", true).putBoolean("home_button_screenshot", true)
                .putBoolean("screensaver_enabled", false).putBoolean("screensaver_on_power", false).commit()
            val home = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            activity = home
            main { home.viewModel.screen = Screen.HOME }
            accessibility = ActionServiceSettingsFixture.on()
            val service = accessibility.service
            assertTrue(service.serviceInfo.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT != 0)
            test(service, home)
        }
    }

    private class RecordingWindowManager(private val actual: WindowManager) : WindowManager by actual {
        var adds = 0; var removes = 0
        var failNextRemoval = false
        var view: View? = null
        val content get() = (view as? ScreenshotPreviewPixels)?.content ?: view
        var params: WindowManager.LayoutParams? = null
        override fun getMaximumWindowMetrics() = actual.maximumWindowMetrics
        override fun addView(view: View, params: ViewGroup.LayoutParams) {
            adds++; this.view = view; this.params = params as WindowManager.LayoutParams
            actual.addView(view, params)
        }
        override fun removeViewImmediate(view: View) {
            removes++
            if (failNextRemoval) {
                failNextRemoval = false
                throw IllegalStateException("Injected WindowManager removal failure")
            }
            actual.removeViewImmediate(view)
        }
    }

    private fun textView(view: View?, text: String): TextView? = when {
        view is TextView && view.text.toString() == text -> view
        view is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { textView(view.getChildAt(it), text) }
        else -> null
    }
    private fun hasText(view: View?, text: String) = textView(view, text) != null

    private fun image(view: View?): ImageView? = when (view) {
        is ImageView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { image(view.getChildAt(it)) }
        else -> null
    }

    private fun capturePreview(service: LockscreenWidgetService, wm: WindowManager) =
        ScreenshotPreviewWindow(service, ControlledPreviewExpiry(), wm)

    @Test fun retainedPreviewRendersNextResultAfterRemovalFails() = withService { service, _ ->
        val wm = RecordingWindowManager(service.getSystemService(WindowManager::class.java))
        val expiry = ControlledPreviewExpiry()
        val window = ScreenshotPreviewWindow(service, expiry, wm)
        val sample = Bitmap.createBitmap(480, 800, Bitmap.Config.ARGB_8888)
        val palette = com.gezimos.katapult.ui.Appearance.palette(service, com.gezimos.katapult.ui.Appearance.Surface.APP)
        withFixtureCleanup({ main { window.close(); expiry.close() } }, { sample.recycle() }) {
            main { window.saved(sample) }
            ui.await("Preview not attached") { wm.view?.isAttachedToWindow == true }
            val retained = checkNotNull(wm.view)
            capturePreviewPixels(retained, "before-preview-removal-fault.png", palette.paperArgb, palette.inkArgb)
            main { wm.failNextRemoval = true; expiry.expireNow() }
            ui.await("Failed removal did not leave a parented GONE window") {
                fixtureMain { wm.removes == 1 && retained.parent != null && retained.visibility == View.GONE }
            }

            main { window.failed(ScreenshotFailure.STORAGE_FULL) }
            assertSame(retained, wm.view)
            assertEquals("Result should reuse the registered window", 1, wm.adds)
            assertEquals("Reused preview is still hidden", View.VISIBLE, fixtureMain { retained.visibility })
            assertTrue(hasText(wm.content, service.getString(R.string.screenshot_storage_full)))
            committedFrame(retained)
            capturePreviewPixels(retained, "restored-preview-after-removal-fault.png", palette.paperArgb, palette.inkArgb)
        }
    }

    @Test fun removedPreviewReleaseCannotStartCaptureWaitingForAnotherPreview() = withService { service, _ ->
        val shown = Bitmap.createBitmap(480, 800, Bitmap.Config.ARGB_8888)
        val wm = RecordingWindowManager(service.getSystemService(WindowManager::class.java))
        val expiry = ControlledPreviewExpiry()
        val releases = java.util.concurrent.LinkedBlockingQueue<() -> Unit>()
        var first = true
        val handler = Handler(Looper.getMainLooper())
        lateinit var window: ScreenshotPreviewWindow
        window = ScreenshotPreviewWindow(service, expiry, wm, commitFrame = {
            if (first) handler.post { window.hide() }
            it.commit()
            if (first) { first = false; throw IllegalStateException("Uncertain old preview commit") }
        }, deliverFrameRelease = { releases.add(it) }) { SurfaceControlCompat.Transaction() }
        val requests = AtomicInteger()
        val saved = CountDownLatch(1)
        val pipeline = ScreenshotCapture(service, Handler(Looper.getMainLooper()), { true }, window, request = {
            requests.incrementAndGet()
            service.takeScreenshot(android.view.Display.DEFAULT_DISPLAY, service.mainExecutor, it)
        }) { saved.countDown() }
        withFixtureCleanup({ main { pipeline.close(); expiry.close() } }, { shown.recycle() }) {
            main { window.saved(shown) }
            val late = checkNotNull(releases.poll(5, TimeUnit.SECONDS)) { "No release from the old frame" }
            val old = wm.view!!
            main { window.hide() }
            ui.await("Old preview did not detach") { !old.isAttachedToWindow }
            main { window.saved(shown) }
            ui.await("New preview not attached") { wm.view !== old && wm.view?.isAttachedToWindow == true }
            val palette = com.gezimos.katapult.ui.Appearance.palette(service, com.gezimos.katapult.ui.Appearance.Surface.APP)
            capturePreviewPixels(wm.view!!, "current-preview-before-late-release.png", palette.paperArgb, palette.inkArgb)
            main { pipeline.capture(); late(); assertEquals("Old lease started the new capture", 0, requests.get()) }
            val current = checkNotNull(releases.poll(5, TimeUnit.SECONDS)) { "No release from the current frame" }
            main { current() }
            assertTrue("Current lease did not release the capture", saved.await(5, TimeUnit.SECONDS))
            assertEquals(1, requests.get())
        }
    }

    @Test fun previewOwnerRecoversPublicationWithoutAnotherInput() = previewOwnerRecoversTransaction(failShow = true)

    @Test fun expiryOwnerRecoversFailedHideWithoutAnotherCapture() = previewOwnerRecoversTransaction(failShow = false)

    private fun previewOwnerRecoversTransaction(failShow: Boolean) = withService { service, _ ->
        val wm = RecordingWindowManager(service.getSystemService(WindowManager::class.java))
        val expiry = ControlledPreviewExpiry()
        val sample = Bitmap.createBitmap(480, 800, Bitmap.Config.ARGB_8888)
        val attempts = AtomicInteger()
        val window = ScreenshotPreviewWindow(service, expiry, wm) { showing ->
            if (showing == failShow && attempts.incrementAndGet() == 1) throw IllegalStateException("Injected preview fault")
            SurfaceControlCompat.Transaction()
        }
        withFixtureCleanup({ main { window.close(); expiry.close() } }, { sample.recycle() }) {
            main { window.saved(sample) }
            ui.await("Preview not attached") { wm.view?.isAttachedToWindow == true }
            committedFrame(wm.view!!)
            val palette = com.gezimos.katapult.ui.Appearance.palette(service, com.gezimos.katapult.ui.Appearance.Surface.APP)
            capturePreviewPixels(wm.view!!, "owner-recovered-preview.png", palette.paperArgb, palette.inkArgb)
            if (!failShow) {
                main { expiry.expireNow() }
                ui.await("Expiry stranded the preview after a failed hide") { wm.view?.isAttachedToWindow == false }
                assertEquals(1, wm.removes)
            }
            assertEquals(2, attempts.get())
        }
    }

    @Test fun nextCaptureWorksAfterExclusionTransactionFails() = captureAfterTransactionFailure(failShow = false)

    @Test fun nextCaptureWorksAfterPreviewBufferIsNeverSubmitted() = captureAfterTransactionFailure(failShow = true)

    @Test fun nextCaptureWorksWhenCommitFailsBeforeSendingThePreview() =
        captureAfterTransactionFailure(failShow = true, failAtCommit = true)

    @Test fun nextCaptureWorksWhenCommitFailsAfterSendingThePreview() =
        captureAfterTransactionFailure(failShow = true, failAtCommit = true, sendBeforeThrow = true)

    private fun captureAfterTransactionFailure(failShow: Boolean, failAtCommit: Boolean = false, sendBeforeThrow: Boolean = false) = withService { service, _ ->
        main { context.startActivity(Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) }
        ui.await("Origin app missing") { ui.has("Brightness level") }
        val wm = RecordingWindowManager(service.getSystemService(WindowManager::class.java))
        val expiry = ControlledPreviewExpiry()
        val injected = CountDownLatch(1)
        var keepFailingHide = !failShow
        val saved = CountDownLatch(1)
        val before = pictures()
        val clean = automation.takeScreenshot()
        val window = ScreenshotPreviewWindow(service, expiry, wm, commitFrame = { transaction ->
            if (failAtCommit && injected.count > 0) {
                if (sendBeforeThrow) transaction.commit()
                injected.countDown()
                throw IllegalStateException("Injected preview commit failure")
            }
            transaction.commit()
        }) { showing ->
            if (!failAtCommit && showing == failShow && (keepFailingHide || injected.count > 0)) {
                injected.countDown()
                throw IllegalStateException("Injected preview transaction preparation failure")
            }
            SurfaceControlCompat.Transaction()
        }
        val pipeline = ScreenshotCapture(service, Handler(Looper.getMainLooper()), { true }, window) { bitmap ->
            ScreenshotStore(context.contentResolver).save(bitmap)
            saved.countDown()
        }
        withFixtureCleanup(
            { main { fixtureCleanup({ pipeline.close() }, { expiry.close() }) } },
            { clean.recycle() },
            { (pictures() - before).forEach { context.contentResolver.delete(imageUri(it), null, null) } },
        ) {
            main { window.saved(clean) }
            if (!failShow) {
                ui.await("Preview was not attached") { wm.view?.isAttachedToWindow == true }
                committedFrame(wm.view!!)
                val palette = com.gezimos.katapult.ui.Appearance.palette(service, com.gezimos.katapult.ui.Appearance.Surface.APP)
                capturePreviewPixels(wm.view!!, "before-failed-preview-exclusion.png", palette.paperArgb, palette.inkArgb)
                main { pipeline.capture() }
                ui.await("A failed exclusion must report that the screenshot was not saved") { hasText(wm.view, "Screenshot not saved") }
                assertEquals("Do not save a capture without exclusion", before, pictures())
            }
            assertTrue("The intended transaction did not fail", injected.await(5, TimeUnit.SECONDS))
            main { keepFailingHide = false; pipeline.capture() }
            assertTrue("The next explicit capture did not save after the transaction failure", saved.await(8, TimeUnit.SECONDS))
            val second = context.contentResolver.openInputStream(imageUri((pictures() - before).single())).use { BitmapFactory.decodeStream(it) }!!
            withFixtureCleanup({ second.recycle() }) {
                val width = clean.width / 3 + 32; val height = clean.height / 3 + 80
                val a = IntArray(width * height); val b = IntArray(a.size)
                clean.getPixels(a, 0, width, 0, clean.height - height, width, height)
                second.getPixels(b, 0, width, 0, second.height - height, width, height)
                assertArrayEquals("A failed transaction left preview pixels in the next capture", a, b)
            }
        }
    }

    @Test fun captureExcludesPreviewEvenWhenWindowManagerKeepsTheWindow() = withService { service, _ ->
        main { context.startActivity(Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) }
        ui.await("Origin app missing") { ui.has("Brightness level") }
        shell("settings put global window_animation_scale 1")
        val real = service.getSystemService(WindowManager::class.java)
        val runner = instrumentation.context
        // On the old implementation INVISIBLE starts this real WindowManager exit animation,
        // so its last opaque buffer can still be captured. The fixed implementation must not
        // rely on WindowManager retiring either the window or its last buffer.
        lateinit var overlay: View
        val wm = object : WindowManager by real {
            override fun getMaximumWindowMetrics() = real.maximumWindowMetrics
            override fun addView(view: View, params: ViewGroup.LayoutParams) {
                val lp = params as WindowManager.LayoutParams
                lp.packageName = runner.packageName
                lp.windowAnimations = runner.resources.getIdentifier("ScreenshotPreviewProbe", "style", runner.packageName)
                assertNotEquals(0, lp.windowAnimations)
                overlay = view
                real.addView(view, lp)
            }
        }
        val expiry = ControlledPreviewExpiry()
        val window = ScreenshotPreviewWindow(service, expiry, wm)
        val clean = automation.takeScreenshot()
        val saved = CountDownLatch(1)
        val resolver = context.contentResolver
        var uri: Uri? = null
        lateinit var pipeline: ScreenshotCapture
        withFixtureCleanup(body = {
            main {
                pipeline = ScreenshotCapture(service, Handler(Looper.getMainLooper()), { true }, window) { bitmap ->
                    uri = ScreenshotStore(resolver).save(bitmap)
                    saved.countDown()
                }
                window.saved(clean)
            }
            ui.await("Previous preview never became visible") { previewWindow()?.contains("Surface: shown=true") == true }
            val palette = com.gezimos.katapult.ui.Appearance.palette(service, com.gezimos.katapult.ui.Appearance.Surface.APP)
            capturePreviewPixels(overlay, "retained-preview-visible.png", palette.paperArgb, palette.inkArgb)
            png(clean, "retained-preview-first.png")
            main { pipeline.capture() }
            assertTrue("Capture did not publish", saved.await(10, TimeUnit.SECONDS))
            val second = resolver.openInputStream(checkNotNull(uri)).use { BitmapFactory.decodeStream(it) }!!
            withFixtureCleanup({ second.recycle() }) {
                png(second, "retained-preview-second.png")
                val width = clean.width / 3 + 32; val height = clean.height / 3 + 80
                val a = IntArray(width * height); val b = IntArray(a.size)
                clean.getPixels(a, 0, width, 0, clean.height - height, width, height)
                second.getPixels(b, 0, width, 0, second.height - height, width, height)
                assertArrayEquals("The previous preview was included in the next screenshot", a, b)
            }
        }, actions = arrayOf<() -> Unit>(
            { main { pipeline.close(); expiry.close() } },
            { uri?.let { resolver.delete(it, null, null) }; clean.recycle() },
        ))
    }

    @Test fun lockScreenNotificationWidgetStaysLightInBothThemes() = withService { service, activity ->
        val prefs = com.gezimos.katapult.util.PrefsManager(context)
        val build = LockscreenWidgetService::class.java.getDeclaredMethod("buildWidget", List::class.java, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
        main { com.gezimos.katapult.util.SystemChrome.apply(activity.window, com.gezimos.katapult.ui.Appearance.Surface.LOCK_SCREEN, false, prefs) }
        for (dark in listOf(false, true)) {
            lateinit var frame: android.widget.FrameLayout
            main {
                prefs.themeMode = if (dark) com.gezimos.katapult.util.PrefsManager.THEME_DARK else com.gezimos.katapult.util.PrefsManager.THEME_LIGHT
                val widget = build.invoke(service, listOf(context.packageName to 3), 3) as View
                frame = android.widget.FrameLayout(activity).apply {
                    setBackgroundColor(com.gezimos.katapult.ui.Appearance.palette(activity, com.gezimos.katapult.ui.Appearance.Surface.LOCK_SCREEN).paperArgb)
                    addView(widget, android.widget.FrameLayout.LayoutParams(-1, -2, android.view.Gravity.CENTER))
                }
                activity.setContentView(frame)
                assertEquals(com.gezimos.katapult.ui.PaperPalette.Light(context).inkArgb, textView(widget, "3")!!.currentTextColor)
            }
            committedFrame(frame)
            val prefix = InstrumentationRegistry.getArguments().getString("appearanceCapturePrefix", "appearance")
            capture("$prefix-${if (dark) "dark" else "light"}-lockscreen-notifications.png")
        }
    }

    @Test fun previewFollowsLiveThemeOverAnotherApp() = withService { service, _ ->
        val prefs = com.gezimos.katapult.util.PrefsManager(context)
        main {
            prefs.themeMode = com.gezimos.katapult.util.PrefsManager.THEME_LIGHT
            context.startActivity(Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        ui.await("Origin app missing") { ui.has("Brightness level") }
        val wm = RecordingWindowManager(service.getSystemService(WindowManager::class.java))
        val expiry = ControlledPreviewExpiry()
        val window = ScreenshotPreviewWindow(service, expiry, wm)
        val sample = Bitmap.createBitmap(48, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        withFixtureCleanup(body = {
            main { window.saved(sample) }
            // The appearance/capture fixture deliberately outlives the real production deadline.
            SystemClock.sleep(ScreenshotPreviewWindow.DURATION_MS + 100)
            assertTrue("Capture fixture must still own its preview", wm.view!!.isAttachedToWindow)
            for (dark in listOf(false, true)) {
                main { prefs.themeMode = if (dark) com.gezimos.katapult.util.PrefsManager.THEME_DARK else com.gezimos.katapult.util.PrefsManager.THEME_LIGHT }
                val palette = if (dark) com.gezimos.katapult.ui.PaperPalette.Dark(context) else com.gezimos.katapult.ui.PaperPalette.Light(context)
                ui.await("Preview text did not observe theme") { textView(wm.view, context.getString(R.string.screenshot_saved))?.currentTextColor == palette.inkArgb }
                committedFrame(checkNotNull(wm.view))
                assertEquals(palette.paperArgb, (wm.content!!.background as android.graphics.drawable.ColorDrawable).color)
                assertEquals("Do not recreate the open preview", 1, wm.adds)
                val prefix = InstrumentationRegistry.getArguments().getString("appearanceCapturePrefix", "appearance")
                capturePreviewPixels(wm.view!!, "$prefix-${if (dark) "dark" else "light"}-screenshot-preview.png", palette.paperArgb, palette.inkArgb)
            }
            // Keep visual evidence independent of the real expiry deadline tested below.
            main {
                prefs.themeMode = com.gezimos.katapult.util.PrefsManager.THEME_LIGHT
                window.failed(ScreenshotFailure.STORAGE_FULL)
            }
            committedFrame(wm.view!!)
            val title = textView(wm.view, "Screenshot not saved")!!
            assertEquals("The whole failure title must fit on its first line", title.text.length, title.layout.getLineEnd(0))
            val light = com.gezimos.katapult.ui.PaperPalette.Light(context)
            capturePreviewPixels(wm.view!!, "screenshot-preview-failure.png", light.paperArgb, light.inkArgb)
            main { expiry.expireNow() }
            ui.await("Controlled expiry did not remove the preview") { !wm.view!!.isAttachedToWindow }
        }, actions = arrayOf<() -> Unit>(
            { main { fixtureCleanup({ window.close() }, { expiry.close() }) } }, { sample.recycle() },
        ))
    }

    @Test fun previewHasOneWindowReplacesInPlaceAndExpiresThreeSecondsAfterLatestResult() = withService { service, _ ->
        main { context.startActivity(Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) }
        ui.await("Origin app missing") { ui.has("Brightness level") }
        val wm = RecordingWindowManager(service.getSystemService(WindowManager::class.java))
        val expired = CountDownLatch(1)
        var firstExpiryDeadline = 0L
        val expiry = object : Handler(Looper.getMainLooper()) {
            override fun dispatchMessage(msg: android.os.Message) {
                if (firstExpiryDeadline == 0L) firstExpiryDeadline = msg.`when`
                super.dispatchMessage(msg)
                expired.countDown()
            }
        }
        lateinit var window: ScreenshotPreviewWindow
        var cleanup: (() -> Unit)? = null
        val bitmap = Bitmap.createBitmap(480, 800, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        withFixtureCleanup(body = {
            main { window = ScreenshotPreviewWindow(service, expiry, wm); cleanup = window::close; window.saved(bitmap) }
            ui.await("Saved preview was not attached") { wm.view?.isAttachedToWindow == true && hasText(wm.view, "Saved") }
            val original = wm.view
            assertEquals(1, wm.adds)
            assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, wm.params!!.type)
            assertTrue(wm.params!!.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
            assertTrue(wm.params!!.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
            assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, wm.view!!.accessibilityLiveRegion)
            ui.await("Preview image has not been measured") { (image(wm.view)?.width ?: 0) > 0 }
            assertEquals(wm.maximumWindowMetrics.bounds.width() / 4, image(wm.view)!!.width)
            assertEquals(image(wm.view)!!.width * 800 / 480, image(wm.view)!!.height)
            main {
                val card = (wm.content as ViewGroup).getChildAt(0)
                val position = IntArray(2); card.getLocationOnScreen(position)
                val inset = (16 * context.resources.displayMetrics.density).toInt()
                assertTrue(kotlin.math.abs(position[0] - inset) <= 1)
                assertTrue(kotlin.math.abs(wm.maximumWindowMetrics.bounds.bottom - position[1] - card.height - inset) <= 1)
            }
            repeat(10) { main { window.saved(bitmap) }; SystemClock.sleep(20) }
            assertSame(original, wm.view); assertEquals(1, wm.adds)
            SystemClock.sleep(1500)
            var replaced = 0L
            var replacementFinished = 0L
            main {
                replaced = SystemClock.uptimeMillis()
                window.failed(ScreenshotFailure.STORAGE_FULL)
                replacementFinished = SystemClock.uptimeMillis()
            }
            ui.await("Storage-full failure card missing") { hasText(wm.view, "Phone storage is full") }
            assertSame(original, wm.view); assertEquals(1, wm.adds); assertEquals(0, wm.removes)
            assertTrue("Latest expiry did not run", expired.await(5, TimeUnit.SECONDS))
            val duration = ScreenshotPreviewWindow.DURATION_MS
            assertTrue("Expiry must be scheduled three seconds from the replacement",
                firstExpiryDeadline in (replaced + duration)..(replacementFinished + duration))
            ui.await("Latest result did not expire", 2500) { !wm.view!!.isAttachedToWindow }
            assertEquals(1, wm.removes)
            main { window.failed(ScreenshotFailure.UNAVAILABLE) }
            ui.await("Capture-unavailable failure card missing") { hasText(wm.view, "The screen can't be captured right now") }
            main { window.close() }
            ui.await("Closing did not remove the preview") { !wm.view!!.isAttachedToWindow }
            assertEquals(2, wm.removes)
            // WM registration exists before the first attach/draw callback. Reuse and
            // cleanup in that same turn must not double-add or leave a pending window.
            main { window.saved(bitmap); window.saved(bitmap); window.hide() }
            assertEquals(3, wm.adds); assertEquals(3, wm.removes)
            assertNull(wm.view!!.parent); assertFalse(wm.view!!.isAttachedToWindow)
        }, actions = arrayOf<() -> Unit>({ main { cleanup?.invoke() } }, { bitmap.recycle() }))
    }

    @Test fun anotherCaptureIsAcceptedWhileTheFirstSaveIsBlockedAndClearPreventsLatePreview() = withService { service, _ ->
        val firstSave = CountDownLatch(1); val release = CountDownLatch(1); val savedTwice = CountDownLatch(2)
        val calls = AtomicInteger()
        val wm = RecordingWindowManager(service.getSystemService(WindowManager::class.java))
        lateinit var pipeline: ScreenshotCapture
        var cleanup: (() -> Unit)? = null
        withFixtureCleanup(body = {
            main {
                pipeline = ScreenshotCapture(service, Handler(Looper.getMainLooper()), { true },
                    capturePreview(service, wm)) { bitmap ->
                    assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
                    assertNotEquals(Bitmap.Config.HARDWARE, bitmap.config)
                    if (calls.incrementAndGet() == 1) { firstSave.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)) }
                    savedTwice.countDown()
                }
                cleanup = pipeline::close
                pipeline.capture()
            }
            assertTrue(firstSave.await(10, TimeUnit.SECONDS))
            SystemClock.sleep(400)
            main { pipeline.capture() }
            SystemClock.sleep(400)
            release.countDown()
            assertTrue("A second accepted capture was dropped while saving", savedTwice.await(10, TimeUnit.SECONDS))
            ui.await("Saved results did not reach the preview") { hasText(wm.view, "Saved") }
            assertEquals(1, wm.adds)
            main { pipeline.clear() }
            ui.await("Clearing did not remove the preview") { !wm.view!!.isAttachedToWindow }
            assertEquals(1, wm.removes)
        }, actions = arrayOf<() -> Unit>({ release.countDown() }, { main { cleanup?.invoke() } }))

        val started = CountDownLatch(1); val finish = CountDownLatch(1); val completed = CountDownLatch(1)
        val late = RecordingWindowManager(service.getSystemService(WindowManager::class.java))
        withFixtureCleanup(body = {
            SystemClock.sleep(400)
            main {
                pipeline = ScreenshotCapture(service, Handler(Looper.getMainLooper()), { true },
                    capturePreview(service, late)) {
                    started.countDown(); assertTrue(finish.await(10, TimeUnit.SECONDS)); completed.countDown()
                }
                pipeline.capture()
            }
            assertTrue(started.await(10, TimeUnit.SECONDS))
            main { pipeline.clear() }; finish.countDown()
            assertTrue(completed.await(10, TimeUnit.SECONDS)); instrumentation.waitForIdleSync()
            main { pipeline.close() }
            assertEquals("Clearing during a save must not resurrect the window", 0, late.adds)
        }, actions = arrayOf<() -> Unit>({ finish.countDown() }, { main { pipeline.close() } }))
    }

    @Test fun olderSaveCannotReplaceNewerCaptureFailureOrResurrectExpiredPreview() = withService { service, _ ->
      for (expireFirst in listOf(false, true)) {
        SystemClock.sleep(400) // Respect the native API's interval between the real first captures.
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        val delivered = CountDownLatch(2)
        val handler = object : Handler(Looper.getMainLooper()) {
            override fun dispatchMessage(message: android.os.Message) {
                withFixtureCleanup({ delivered.countDown() }) { super.dispatchMessage(message) }
            }
        }
        val wm = RecordingWindowManager(service.getSystemService(WindowManager::class.java))
        val expiry = ControlledPreviewExpiry()
        lateinit var pipeline: ScreenshotCapture
        var cleanup: (() -> Unit)? = null
        var requests = 0
        withFixtureCleanup(body = {
            main {
                pipeline = ScreenshotCapture(service, handler, { true }, ScreenshotPreviewWindow(service, expiry, wm),
                    request = { callback ->
                        if (++requests == 1) service.takeScreenshot(android.view.Display.DEFAULT_DISPLAY, service.mainExecutor, callback)
                        else callback.onFailure(android.accessibilityservice.AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT)
                    }) {
                    started.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS))
                }
                cleanup = pipeline::close
                pipeline.capture()
            }
            assertTrue(started.await(10, TimeUnit.SECONDS))
            main { pipeline.capture() }
            ui.await("Newer failure card missing") { hasText(wm.view, "Screenshot not saved") }
            assertEquals(2, requests)
            if (expireFirst) {
                main { expiry.expireNow() }
                ui.await("Failure did not expire") { wm.view?.isAttachedToWindow == false }
            }
            release.countDown()
            assertTrue("Old save result was not delivered", delivered.await(10, TimeUnit.SECONDS))
            main {
                if (expireFirst) assertFalse("Old save resurrected an expired newer failure", wm.view!!.isAttachedToWindow)
                else {
                    assertTrue("Newer failure card was replaced", hasText(wm.view, "Screenshot not saved"))
                    assertTrue(wm.view!!.isAttachedToWindow)
                }
                assertEquals(1, wm.adds)
                assertEquals(if (expireFirst) 1 else 0, wm.removes)
            }
        }, actions = arrayOf<() -> Unit>(
            { release.countDown() }, { main { fixtureCleanup({ cleanup?.invoke() }, { expiry.close() }) } },
        ))
      }
    }

    @Test fun saveFailureShowsTheReasonWithoutRetrying() = withService { service, _ ->
        val calls = AtomicInteger()
        val wm = RecordingWindowManager(service.getSystemService(WindowManager::class.java))
        lateinit var pipeline: ScreenshotCapture
        var cleanup: (() -> Unit)? = null
        withFixtureCleanup(body = {
            main {
                pipeline = ScreenshotCapture(service, Handler(Looper.getMainLooper()), { true },
                    capturePreview(service, wm)) {
                    calls.incrementAndGet(); throw FileNotFoundException("ENOSPC")
                }
                cleanup = pipeline::close
                pipeline.capture()
            }
            ui.await("Save failure was not reported", 10000) { hasText(wm.view, "Phone storage is full") }
            assertEquals(1, calls.get()); assertEquals(1, wm.adds)
        }, actions = arrayOf<() -> Unit>({ main { cleanup?.invoke() } }))
    }

    private fun pictures(): Set<Long> {
        val rows = mutableSetOf<Long>()
        context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.OWNER_PACKAGE_NAME} = ? AND ${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?",
            arrayOf(context.packageName, "Screenshot_%"), null)?.use { while (it.moveToNext()) rows += it.getLong(0) }
        return rows
    }
    private fun imageUri(id: Long) = android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

    private fun previewWindow(): String? = Regex("Window #[0-9]+ Window\\{[^\\n]* Screenshot preview\\}:([\\s\\S]*?)(?=\\n  Window #|\\z)")
        .find(read("dumpsys window windows"))?.value

    @Test fun screenOffRemovesTheServiceOwnedPreviewAndPassesKeysThrough() = withService { service, _ ->
        val before = pictures()
        val key = LockscreenWidgetService::class.java.getDeclaredMethod("onKeyEvent", KeyEvent::class.java).apply { isAccessible = true }
        withFixtureCleanup(body = {
            main {
                assertEquals(true, key.invoke(service, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HOME)))
                assertEquals(true, key.invoke(service, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN)))
                assertEquals(true, key.invoke(service, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HOME)))
                assertEquals(true, key.invoke(service, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_DOWN)))
            }
            ui.await("Service preview not shown", 10000) { previewWindow()?.contains("Surface: shown=true") == true }
            shell("input keyevent KEYCODE_SLEEP")
            ui.await("Screen-off left the preview window attached") { previewWindow() == null }
            main {
                assertEquals(false, key.invoke(service, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HOME)))
                assertEquals(false, key.invoke(service, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN)))
                assertEquals(false, key.invoke(service, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HOME)))
            }
        }, actions = arrayOf<() -> Unit>(
            { NativeKeyguardFixture.wakeAndUnlock() },
            { fixtureCleanup(*(pictures() - before).map { id -> {
                context.contentResolver.delete(imageUri(id), null, null)
                Unit
            } }.toTypedArray()) },
        ))
    }

    @Test fun hardwareChordSavesOnePictureWithoutChangingVolumeOrHomeAndNeverCapturesThePreviousPreview() = withService { service, activity ->
        val before = pictures()
        val audio = service.getSystemService(AudioManager::class.java)
        val streams = listOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_RING, AudioManager.STREAM_ALARM,
            AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM, AudioManager.STREAM_VOICE_CALL)
        val volume = streams.associateWith { audio.getStreamVolume(it) }
        val announcements = CopyOnWriteArrayList<String>()
        automation.setOnAccessibilityEventListener { event ->
            if (event.eventType == android.view.accessibility.AccessibilityEvent.TYPE_ANNOUNCEMENT)
                announcements.addAll(event.text.map { it.toString() })
        }
        withFixtureCleanup(body = {
            main { context.startActivity(Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) }
            ui.await("Origin app missing") { ui.has("Brightness level") }
            val keyboard = Regex("/dev/input/event[0-9]+").find(read("su 0 getevent -pl").split("add device ").single { it.contains("KEY_HOMEPAGE") })!!.value
            fun key(code: Int, value: Int) {
                assertEquals("", shell("su 0 sendevent $keyboard 1 $code $value"))
                assertEquals("", shell("su 0 sendevent $keyboard 0 0 0"))
            }
            fun chord(homeFirst: Boolean) {
                key(172, 1); key(114, 1); key(114, 2)
                if (homeFirst) { key(172, 0); key(114, 0) } else { key(114, 0); key(172, 0) }
            }
            chord(false)
            ui.await("First picture not published", 10000) { pictures().size == before.size + 1 }
            fun previewVisible(): Boolean {
                // Android excludes non-touchable accessibility overlays from the interactive tree.
                // Observe the actual compositor-backed window, not a nonexistent interactive node.
                val window = previewWindow() ?: return false
                return window.contains("isVisible=true") && window.contains("Surface: shown=true")
            }
            ui.await("Saved preview not visible", 10000) { previewVisible() }
            capture("screenshot-preview-saved.png")
            ui.await("Saved was not announced") { announcements.contains("Screenshot saved") }
            assertTrue(ui.has("Brightness level")); assertFalse(PlatformUiDriver(instrumentation, context.getString(R.string.recent_apps)).windowVisible())
            val firstId = (pictures() - before).single()
            val first = context.contentResolver.openInputStream(imageUri(firstId)).use { BitmapFactory.decodeStream(it) }!!
            png(first, "screenshot-capture-first.png")
            withFixtureCleanup(body = {
                SystemClock.sleep(400)
                chord(true)
                ui.await("Second chord not published", 10000) { pictures().size == before.size + 2 }
                val secondId = (pictures() - before - firstId).single()
                val second = context.contentResolver.openInputStream(imageUri(secondId)).use { BitmapFactory.decodeStream(it) }!!
                png(second, "screenshot-capture-second.png")
                withFixtureCleanup(body = {
                    assertEquals(first.width, second.width); assertEquals(first.height, second.height)
                    // Static app content underneath the former preview must be identical, not a nested card.
                    val width = first.width / 3 + 32; val height = first.height / 3 + 80
                    val a = IntArray(width * height); val b = IntArray(a.size)
                    first.getPixels(a, 0, width, 0, first.height - height, width, height)
                    second.getPixels(b, 0, width, 0, second.height - height, width, height)
                    assertArrayEquals("The previous preview was included in the next screenshot", a, b)
                }, actions = arrayOf<() -> Unit>({ second.recycle() }))
            }, actions = arrayOf<() -> Unit>({ first.recycle() }))
            for (id in pictures() - before) context.contentResolver.query(imageUri(id), arrayOf(
                MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.RELATIVE_PATH, MediaStore.Images.Media.IS_PENDING), null, null, null)!!.use {
                assertTrue(it.moveToFirst()); assertTrue(it.getString(0).startsWith("Screenshot_")); assertTrue(it.getString(0).endsWith(".png"))
                assertEquals("Pictures/Screenshots/", it.getString(1)); assertEquals(0, it.getInt(2))
            }
            assertEquals(volume, streams.associateWith { audio.getStreamVolume(it) })
            assertTrue(ui.has("Brightness level")); assertFalse(PlatformUiDriver(instrumentation, context.getString(R.string.recent_apps)).windowVisible())
            // Interrupt cleanup must remove the current result, not resurrect it after its save.
            main { service.onInterrupt() }
            ui.await("Interrupt did not remove preview") { !previewVisible() }
        }, actions = arrayOf<() -> Unit>(
            { automation.setOnAccessibilityEventListener(null) },
            { fixtureCleanup(*(pictures() - before).map { id -> {
                context.contentResolver.delete(imageUri(id), null, null)
                Unit
            } }.toTypedArray()) },
        ))
    }

    @Test fun mediaStorePublishesPngAndDeletesPendingRowsOnOpenOrPublishFailure() {
        val bitmap = Bitmap.createBitmap(40, 60, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        withFixtureCleanup(body = {
            val uri = ScreenshotStore(context.contentResolver).save(bitmap)
            withFixtureCleanup(body = {
                context.contentResolver.openInputStream(uri).use {
                    val decoded = BitmapFactory.decodeStream(it)!!
                    assertEquals(40, decoded.width); assertEquals(60, decoded.height); assertEquals(Color.BLACK, decoded.getPixel(10, 10)); decoded.recycle()
                }
            }, actions = arrayOf<() -> Unit>({ context.contentResolver.delete(uri, null, null) }))
            for (failOpen in listOf(true, false)) {
                val fixture = File(context.filesDir, "screenshot-store-failure.png")
                var deleted = 0
                var pending: Int? = null
                val provider = object : ContentProvider() {
                    override fun onCreate() = true
                    override fun getType(uri: Uri) = "image/png"
                    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? = null
                    override fun insert(uri: Uri, values: ContentValues?): Uri { pending = values?.getAsInteger(MediaStore.Images.Media.IS_PENDING); return Uri.parse("content://screenshot-test/images/1") }
                    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = throw IllegalStateException("publish failed")
                    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int { deleted++; return 1 }
                    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
                        if (failOpen) throw FileNotFoundException("ENOSPC")
                        return ParcelFileDescriptor.open(fixture, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE)
                    }
                }
                withFixtureCleanup(body = {
                    try {
                        ScreenshotStore(ContentResolver.wrap(provider)).save(bitmap)
                        fail("The storage failure was swallowed")
                    } catch (error: Exception) {
                        assertEquals(if (failOpen) ScreenshotFailure.STORAGE_FULL else ScreenshotFailure.UNAVAILABLE, screenshotFailure(error))
                    }
                }, actions = arrayOf<() -> Unit>({ fixture.delete() }))
                assertEquals(1, pending); assertEquals("Pending row was not deleted", 1, deleted)
            }
        }, actions = arrayOf<() -> Unit>({ bitmap.recycle() }))
    }
}
