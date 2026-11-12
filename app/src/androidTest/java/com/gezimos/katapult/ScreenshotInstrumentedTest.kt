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

class ScreenshotInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val ui = PlatformUiDriver(instrumentation)
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun shell(command: String) = automation.boundedShell(command)
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

    private fun withService(test: (LockscreenWidgetService, MainActivity) -> Unit) {
        var accessibility: ActionServiceSettingsFixture? = null
        val raw = context.getSharedPreferences("katapult_prefs", 0)
        val before = raw.all
        val animationScales = listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")
            .associateWith { shell("settings get global $it") }
        var activity: MainActivity? = null
        try {
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
        } finally {
          try { main { activity?.finish() } } finally {
           try { accessibility?.close() } finally {
            try { raw.edit().clear().apply { before.forEach { (key, value) -> when (value) {
                is Boolean -> putBoolean(key, value)
                is Int -> putInt(key, value)
                is Long -> putLong(key, value)
                is Float -> putFloat(key, value)
                is String -> putString(key, value)
                is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
            } } }.commit() } finally {
            animationScales.forEach { (key, value) ->
                if (value == "null") shell("settings delete global $key") else shell("settings put global $key $value")
            }
            }
           }
          }
        }
    }

    private class RecordingWindowManager(private val actual: WindowManager) : WindowManager by actual {
        var adds = 0; var removes = 0
        var view: View? = null
        var params: WindowManager.LayoutParams? = null
        override fun getMaximumWindowMetrics() = actual.maximumWindowMetrics
        override fun addView(view: View, params: ViewGroup.LayoutParams) {
            adds++; this.view = view; this.params = params as WindowManager.LayoutParams
            actual.addView(view, params)
        }
        override fun removeViewImmediate(view: View) { removes++; actual.removeViewImmediate(view) }
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
        val window = ScreenshotPreviewWindow(service, Handler(Looper.getMainLooper()), wm)
        val sample = Bitmap.createBitmap(48, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        try {
            main { window.saved(sample) }
            for (dark in listOf(false, true)) {
                main { prefs.themeMode = if (dark) com.gezimos.katapult.util.PrefsManager.THEME_DARK else com.gezimos.katapult.util.PrefsManager.THEME_LIGHT }
                val palette = if (dark) com.gezimos.katapult.ui.PaperPalette.Dark(context) else com.gezimos.katapult.ui.PaperPalette.Light(context)
                ui.await("Preview text did not observe theme") { textView(wm.view, context.getString(R.string.screenshot_saved))?.currentTextColor == palette.inkArgb }
                committedFrame(checkNotNull(wm.view))
                assertEquals(palette.paperArgb, (wm.view!!.background as android.graphics.drawable.ColorDrawable).color)
                assertEquals("Do not recreate the open preview", 1, wm.adds)
                val prefix = InstrumentationRegistry.getArguments().getString("appearanceCapturePrefix", "appearance")
                capture("$prefix-${if (dark) "dark" else "light"}-screenshot-preview.png")
            }
        } finally { main { window.close() }; sample.recycle() }
    }

    @Test fun previewHasOneWindowReplacesInPlaceAndExpiresThreeSecondsAfterLatestResult() = withService { service, _ ->
        main { context.startActivity(Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) }
        ui.await("Origin app missing") { ui.has("Brightness level") }
        val wm = RecordingWindowManager(service.getSystemService(WindowManager::class.java))
        lateinit var window: ScreenshotPreviewWindow
        var cleanup: (() -> Unit)? = null
        val bitmap = Bitmap.createBitmap(480, 800, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        try {
            main { window = ScreenshotPreviewWindow(service, Handler(Looper.getMainLooper()), wm); cleanup = window::close; window.saved(bitmap) }
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
                val card = (wm.view as ViewGroup).getChildAt(0)
                val position = IntArray(2); card.getLocationOnScreen(position)
                val inset = (16 * context.resources.displayMetrics.density).toInt()
                assertTrue(kotlin.math.abs(position[0] - inset) <= 1)
                assertTrue(kotlin.math.abs(wm.maximumWindowMetrics.bounds.bottom - position[1] - card.height - inset) <= 1)
            }
            repeat(10) { main { window.saved(bitmap) }; SystemClock.sleep(20) }
            assertSame(original, wm.view); assertEquals(1, wm.adds)
            SystemClock.sleep(1500)
            val replaced = SystemClock.uptimeMillis()
            main { window.failed(ScreenshotFailure.STORAGE_FULL) }
            ui.await("Storage-full failure card missing") { hasText(wm.view, "Phone storage is full") }
            assertSame(original, wm.view); assertEquals(1, wm.adds); assertEquals(0, wm.removes)
            committedFrame(wm.view!!)
            val title = textView(wm.view, "Screenshot not saved")!!
            assertEquals("The whole failure title must fit on its first line", title.text.length, title.layout.getLineEnd(0))
            capture("screenshot-preview-failure.png")
            SystemClock.sleep(1700)
            assertTrue("The old expiry must be cancelled", wm.view!!.isAttachedToWindow)
            ui.await("Latest result did not expire", 2500) { !wm.view!!.isAttachedToWindow }
            assertTrue(SystemClock.uptimeMillis() - replaced >= 2900)
            assertEquals(1, wm.removes)
            main { window.failed(ScreenshotFailure.UNAVAILABLE) }
            ui.await("Capture-unavailable failure card missing") { hasText(wm.view, "The screen can't be captured right now") }
            main { window.close() }
            assertEquals(2, wm.removes)
            // WM registration exists before the first attach/draw callback. Reuse and
            // cleanup in that same turn must not double-add or leave a pending window.
            main { window.saved(bitmap); window.saved(bitmap); window.hide() }
            assertEquals(3, wm.adds); assertEquals(3, wm.removes)
            assertNull(wm.view!!.parent); assertFalse(wm.view!!.isAttachedToWindow)
        } finally { main { cleanup?.invoke() }; bitmap.recycle() }
    }

    @Test fun anotherCaptureIsAcceptedWhileTheFirstSaveIsBlockedAndClearPreventsLatePreview() = withService { service, _ ->
        val firstSave = CountDownLatch(1); val release = CountDownLatch(1); val savedTwice = CountDownLatch(2)
        val calls = AtomicInteger()
        val wm = RecordingWindowManager(service.getSystemService(WindowManager::class.java))
        lateinit var pipeline: ScreenshotCapture
        var cleanup: (() -> Unit)? = null
        try {
            main {
                pipeline = ScreenshotCapture(service, Handler(Looper.getMainLooper()), { true },
                    ScreenshotPreviewWindow(service, Handler(Looper.getMainLooper()), wm)) { bitmap ->
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
            assertFalse(wm.view!!.isAttachedToWindow)
            assertEquals(1, wm.removes)
        } finally { release.countDown(); main { cleanup?.invoke() } }

        val started = CountDownLatch(1); val finish = CountDownLatch(1); val completed = CountDownLatch(1)
        val late = RecordingWindowManager(service.getSystemService(WindowManager::class.java))
        try {
            SystemClock.sleep(400)
            main {
                pipeline = ScreenshotCapture(service, Handler(Looper.getMainLooper()), { true },
                    ScreenshotPreviewWindow(service, Handler(Looper.getMainLooper()), late)) {
                    started.countDown(); assertTrue(finish.await(10, TimeUnit.SECONDS)); completed.countDown()
                }
                pipeline.capture()
            }
            assertTrue(started.await(10, TimeUnit.SECONDS))
            main { pipeline.clear() }; finish.countDown()
            assertTrue(completed.await(10, TimeUnit.SECONDS)); instrumentation.waitForIdleSync()
            main { pipeline.close() }
            assertEquals("Clearing during a save must not resurrect the window", 0, late.adds)
        } finally { finish.countDown(); main { pipeline.close() } }
    }

    @Test fun saveFailureShowsTheReasonWithoutRetrying() = withService { service, _ ->
        val calls = AtomicInteger()
        val wm = RecordingWindowManager(service.getSystemService(WindowManager::class.java))
        lateinit var pipeline: ScreenshotCapture
        var cleanup: (() -> Unit)? = null
        try {
            main {
                pipeline = ScreenshotCapture(service, Handler(Looper.getMainLooper()), { true },
                    ScreenshotPreviewWindow(service, Handler(Looper.getMainLooper()), wm)) {
                    calls.incrementAndGet(); throw FileNotFoundException("ENOSPC")
                }
                cleanup = pipeline::close
                pipeline.capture()
            }
            ui.await("Save failure was not reported", 10000) { hasText(wm.view, "Phone storage is full") }
            assertEquals(1, calls.get()); assertEquals(1, wm.adds)
        } finally { main { cleanup?.invoke() } }
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
        .find(shell("dumpsys window windows"))?.value

    @Test fun screenOffRemovesTheServiceOwnedPreviewAndPassesKeysThrough() = withService { service, _ ->
        val before = pictures()
        val key = LockscreenWidgetService::class.java.getDeclaredMethod("onKeyEvent", KeyEvent::class.java).apply { isAccessible = true }
        try {
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
        } finally {
            NativeKeyguardFixture.wakeAndUnlock()
            (pictures() - before).forEach { context.contentResolver.delete(imageUri(it), null, null) }
        }
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
        try {
            main { context.startActivity(Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) }
            ui.await("Origin app missing") { ui.has("Brightness level") }
            val keyboard = Regex("/dev/input/event[0-9]+").find(shell("su 0 getevent -pl").split("add device ").single { it.contains("KEY_HOMEPAGE") })!!.value
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
            try {
                SystemClock.sleep(400)
                chord(true)
                ui.await("Second chord not published", 10000) { pictures().size == before.size + 2 }
                val secondId = (pictures() - before - firstId).single()
                val second = context.contentResolver.openInputStream(imageUri(secondId)).use { BitmapFactory.decodeStream(it) }!!
                png(second, "screenshot-capture-second.png")
                try {
                    assertEquals(first.width, second.width); assertEquals(first.height, second.height)
                    // Static app content underneath the former preview must be identical, not a nested card.
                    val width = first.width / 3 + 32; val height = first.height / 3 + 80
                    val a = IntArray(width * height); val b = IntArray(a.size)
                    first.getPixels(a, 0, width, 0, first.height - height, width, height)
                    second.getPixels(b, 0, width, 0, second.height - height, width, height)
                    assertArrayEquals("The previous preview was included in the next screenshot", a, b)
                } finally { second.recycle() }
            } finally { first.recycle() }
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
        } finally {
            automation.setOnAccessibilityEventListener(null)
            (pictures() - before).forEach { context.contentResolver.delete(imageUri(it), null, null) }
        }
    }

    @Test fun mediaStorePublishesPngAndDeletesPendingRowsOnOpenOrPublishFailure() {
        val bitmap = Bitmap.createBitmap(40, 60, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        try {
            val uri = ScreenshotStore(context.contentResolver).save(bitmap)
            try {
                context.contentResolver.openInputStream(uri).use {
                    val decoded = BitmapFactory.decodeStream(it)!!
                    assertEquals(40, decoded.width); assertEquals(60, decoded.height); assertEquals(Color.BLACK, decoded.getPixel(10, 10)); decoded.recycle()
                }
            } finally { context.contentResolver.delete(uri, null, null) }
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
                try {
                    ScreenshotStore(ContentResolver.wrap(provider)).save(bitmap)
                    fail("The storage failure was swallowed")
                } catch (error: Exception) {
                    assertEquals(if (failOpen) ScreenshotFailure.STORAGE_FULL else ScreenshotFailure.UNAVAILABLE, screenshotFailure(error))
                } finally { fixture.delete() }
                assertEquals(1, pending); assertEquals("Pending row was not deleted", 1, deleted)
            }
        } finally { bitmap.recycle() }
    }
}
