package com.gezimos.katapult

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import com.gezimos.katapult.setPaperContent as setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.lockscreen.*
import com.gezimos.katapult.ui.NoIndication
import com.gezimos.katapult.ui.SettingsScreen
import com.gezimos.katapult.ui.settings.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class LockscreenWidgetCardsInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val ui = PlatformUiDriver(instrumentation)
    private fun main(work: () -> Unit) = instrumentation.runOnMainSync(work)

    @Test fun widgetFixtureOwnsLightThemeAndRestoresMissingOrDark() {
        val raw = context.getSharedPreferences("katapult_prefs", 0)
        val before = raw.all["theme_mode"]
        try {
            for (theme in listOf(null, com.gezimos.katapult.util.PrefsManager.THEME_DARK)) {
                raw.edit().apply { if (theme == null) remove("theme_mode") else putInt("theme_mode", theme) }.commit()
                WidgetHostTestFixture(context, applicationPreferences = true).use {
                    assertEquals(com.gezimos.katapult.util.PrefsManager.THEME_LIGHT, raw.getInt("theme_mode", -1))
                }
                assertEquals(theme, raw.all["theme_mode"])
            }
        } finally {
            raw.edit().apply { if (before == null) remove("theme_mode") else putInt("theme_mode", before as Int) }.commit()
        }
    }

    private fun withWidgets(work: (MainActivity, WidgetHostTestFixture, List<LockscreenWidget>, List<String>) -> Unit) {
        val fixture = WidgetHostTestFixture(context, applicationPreferences = true)
        val providers = fixture.manager.installedProviders.filter { it.provider.packageName == "com.alexindigo.kotatsu.notes.debug" }
        assertEquals(2, providers.size)
        val selected = providers.sortedBy { it.loadLabel(context.packageManager) }.map {
            LockscreenWidget(fixture.bind(it), LockscreenWidgetSize.M)
        }
        assertEquals(LockscreenWidgetStore.Result.APPLIED, fixture.prefs.lockscreenWidgetStore.replaceImportedSelection(LockscreenWidgetList.encode(selected)))
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            main {
                val settings = activity.viewModel.lockscreenWidgets
                settings.refresh()
                activity.viewModel.settingsPageRequest = "widgets"
                activity.setContent {
                    CompositionLocalProvider(androidx.compose.foundation.LocalIndication provides NoIndication) {
                        SettingsScreen(activity.viewModel, activity.configExporter, activity.configImporter, activity.screensaverWallpaperPicker)
                    }
                }
            }
            ui.await("Widget page must render") { ui.has(context.getString(R.string.lockscreen_widgets_show)) }
            work(activity, fixture, selected, providers.sortedBy { it.loadLabel(context.packageManager) }.map { it.loadLabel(context.packageManager) })
        } finally {
            main { activity.finish() }
            instrumentation.waitForIdleSync()
            fixture.close()
        }
    }

    private fun showCards(labels: List<String>) {
        if (!ui.has(labels.first())) ui.click(context.getString(R.string.paper_next_page))
        ui.await("Both cards must be visible") { labels.all(ui::has) }
    }

    @Test fun captureTwoWidgets() = withWidgets { activity, _, _, labels ->
        showCards(labels)
        assertFalse(ui.has("Size"))
        assertFalse(ui.has(context.getString(R.string.lockscreen_widgets_remove)))
        assertTrue(ui.has(context.getString(R.string.paper_widget_card_help)))
        assertTrue(ui.has(context.getString(R.string.lockscreen_widgets_medium)))
        val card = cardBounds(labels.first())
        val density = context.resources.displayMetrics.density
        assertTrue("Three compact lines, not three action rows", card.height() <= 75 * density)
        capture(activity, "compact-normal")
        val down = SystemClock.uptimeMillis()
        fun event(action: Int) {
            val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, card.centerX().toFloat(), card.centerY().toFloat(), 0)
            e.source = InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(instrumentation.platformAutomation.injectInputEvent(e, true)); e.recycle()
        }
        event(MotionEvent.ACTION_DOWN)
        try {
            SystemClock.sleep(750)
            capture(activity, "compact-ready")
            val file = File(context.getExternalFilesDir(null), "widget-card-renders/compact-ready.png")
            val image = android.graphics.BitmapFactory.decodeFile(file.path)
            val normal = android.graphics.BitmapFactory.decodeFile(
                File(context.getExternalFilesDir(null), "widget-card-renders/compact-normal.png").path)
            val content = Rect(card).apply {
                inset(kotlin.math.round(14 * density).toInt(), kotlin.math.round(6 * density).toInt())
            }
            for (y in card.top until card.bottom) for (x in card.left until card.right) {
                // The normal frame identifies actual text/leader raster ink, excluding
                // the structural bracket lanes and all padding. No other white is legal.
                val textInk = content.contains(x, y) && normal.getPixel(x, y) != android.graphics.Color.WHITE
                if (!textInk) assertEquals("Solid ready rectangle at ($x,$y)", android.graphics.Color.BLACK, image.getPixel(x, y))
            }
            normal.recycle()
            image.recycle()
        } finally { event(MotionEvent.ACTION_UP) }
        ui.await("A stationary long-press release must leave the card page open") {
            ui.has(context.getString(R.string.lockscreen_widgets_add)) && !ui.has(context.getString(R.string.lockscreen_widgets_remove))
        }
        capture(activity, "compact-dropped")
    }

    @Test fun openLaunchesProviderSettingsAndSizeRemoveAddRetainTheirRealFlows() = withWidgets { activity, fixture, selected, labels ->
        showCards(labels)
        assertTrue(ui.has(context.getString(R.string.lockscreen_widgets_add)))
        clickOpen(labels.first())
        ui.await("Provider configure activity must be the focused consumer") { ui.packageVisible("com.alexindigo.kotatsu.notes.debug") }
        assertTrue("Quote provider must open its own settings", ui.has("Quote of the day"))
        ui.back()
        ui.await("Settings must regain focus") { ui.packageVisible(context.packageName) && ui.has(labels.last()) }
        val edge = cardBounds(labels.first())
        val time = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, edge.left + 2f, edge.centerY().toFloat(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(instrumentation.platformAutomation.injectInputEvent(event, true)); event.recycle()
        }
        ui.await("Card tap opens Kotatsu's size/move/remove page") { ui.has(context.getString(R.string.lockscreen_widgets_small)) }
        assertEquals(true, ui.checked(context.getString(R.string.lockscreen_widgets_medium)))
        ui.click(context.getString(R.string.lockscreen_widgets_small))
        ui.await("Size must persist") { fixture.prefs.lockscreenWidgetStore.read().first().size == LockscreenWidgetSize.S }
        ui.click(labels.first())
        if (!ui.has(context.getString(R.string.lockscreen_widgets_remove))) ui.click(context.getString(R.string.paper_next_page))
        ui.click(context.getString(R.string.lockscreen_widgets_remove))
        ui.await("Removal must release the binding and update the visible cards") {
            fixture.prefs.lockscreenWidgetStore.read().map { it.id } == listOf(selected.last().id) && !ui.has(labels.first())
        }
        assertFalse(selected.first().id in fixture.host.appWidgetIds)
        // One card fits with the top controls; Add must be on the same first page.
        if (ui.has(context.getString(R.string.paper_previous_page))) ui.click(context.getString(R.string.paper_previous_page))
        assertTrue(ui.has(context.getString(R.string.lockscreen_widgets_add)))
        val providerLabel = activity.viewModel.lockscreenWidgets.providers().single {
            it.info.provider == fixture.manager.getAppWidgetInfo(selected.first().id)?.provider ||
                it.info.loadLabel(context.packageManager) == labels.first()
        }.label
        ui.click(context.getString(R.string.lockscreen_widgets_add))
        ui.await("Provider list must open") { ui.find(context.getString(R.string.lockscreen_widgets_add))?.isHeading == true }
        repeat(6) {
            if (!ui.has(providerLabel) && ui.has(context.getString(R.string.paper_next_page))) ui.click(context.getString(R.string.paper_next_page))
        }
        ui.await("Provider list must offer the removed widget") { ui.has(providerLabel) }
        instrumentation.platformAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
        try {
            ui.click(providerLabel)
            ui.await("New widget configure must launch") { ui.packageVisible("com.alexindigo.kotatsu.notes.debug") }
        } finally { instrumentation.platformAutomation.dropShellPermissionIdentity() }
        ui.back()
        ui.await("Configured widget must be added exactly once") { fixture.prefs.lockscreenWidgetStore.read().size == 2 }
        showCards(labels)
        assertTrue(ui.has(context.getString(R.string.lockscreen_widgets_add)))
    }

    @Test fun longPressDragPreviewsWholeCardsAndOnlyDropCommitsWhileCancelRestores() = withWidgets { _, fixture, selected, labels ->
        showCards(labels)
        val first = cardBounds(labels.first())
        val second = cardBounds(labels.last())
        var writes = 0
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == LockscreenWidgetStore.KEY) writes++
        }
        fixture.raw.registerOnSharedPreferenceChangeListener(listener)
        try {
            drag(first.centerX().toFloat(), first.centerY().toFloat(), second.centerY() + 2f, cancel = true) {
                ui.await("Whole-card preview must reverse visible order") { bounds(labels.first()).top > bounds(labels.last()).top }
                assertEquals(selected, fixture.prefs.lockscreenWidgetStore.read())
                assertEquals(0, writes)
            }
            ui.await("Cancellation must restore the visible persisted order") { bounds(labels.first()).top < bounds(labels.last()).top }
            assertEquals(selected, fixture.prefs.lockscreenWidgetStore.read())
            drag(first.centerX().toFloat(), first.centerY().toFloat(), second.centerY() + 2f) {
                ui.await("Card must move before drop") { bounds(labels.first()).top > bounds(labels.last()).top }
                assertEquals(0, writes)
            }
            ui.await("Drop must persist the permutation") { fixture.prefs.lockscreenWidgetStore.read() == selected.reversed() }
            assertEquals(1, writes)
            assertTrue(bounds(labels.first()).top > bounds(labels.last()).top)
        } finally { fixture.raw.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    @Test fun providerWithoutReconfigurationOpensItsAppEntry() = withWidgets { activity, fixture, _, _ ->
        val info = fixture.manager.installedProviders.first { it.provider.packageName == "com.android.deskclock" && it.configure == null }
        val id = fixture.bind(info)
        main {
            assertEquals(LockscreenWidgetStore.Result.APPLIED, fixture.prefs.lockscreenWidgetStore.replaceImportedSelection(
                LockscreenWidgetList.encode(listOf(LockscreenWidget(id, LockscreenWidgetSize.S)))))
        }
        val label = info.loadLabel(context.packageManager)
        ui.await("Replacement card must render") { ui.has(label) }
        val row = activity.viewModel.lockscreenWidgets.rows.single()
        assertFalse(row.canEdit)
        assertTrue(row.canOpen)
        clickOpen(label)
        ui.await("The widget's own app must receive the handoff") { ui.packageVisible(info.provider.packageName) }
        ui.back()
        ui.await("The widget card must remain configured on return") { ui.packageVisible(context.packageName) && ui.has(label) }
        assertEquals(listOf(id), fixture.prefs.lockscreenWidgetStore.read().map { it.id })
    }

    @Test fun accessibleMoveActionsOmitUnavailableDirectionsAndCommitTheVisibleOrder() = withWidgets { _, fixture, selected, labels ->
        showCards(labels)
        fun card(label: String) = checkNotNull(ui.findAncestor(label) { node -> node.actionList.any {
            it.label?.toString() in listOf(context.getString(R.string.lockscreen_widgets_up), context.getString(R.string.lockscreen_widgets_down))
        } })
        val first = card(labels.first())
        assertFalse(first.actionList.any { it.label?.toString() == context.getString(R.string.lockscreen_widgets_up) })
        val down = first.actionList.single { it.label?.toString() == context.getString(R.string.lockscreen_widgets_down) }
        assertTrue(first.performAction(down.id))
        ui.await("Accessible move must update the persisted and visible order") {
            fixture.prefs.lockscreenWidgetStore.read() == selected.reversed() && bounds(labels.first()).top > bounds(labels.last()).top
        }
        assertFalse(card(labels.first()).actionList.any { it.label?.toString() == context.getString(R.string.lockscreen_widgets_down) })
    }

    @Test fun bracketStrokesSpanOneAndThreeLineContentAtItsMeasuredHeight() {
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val boxes = mutableMapOf<Int, Rect>()
        try {
            main { activity.setContent {
                Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp)) {
                    for (lines in listOf(1, 3)) {
                        PaperCard(Modifier.onGloballyPositioned {
                            val origin = it.positionInWindow()
                            boxes[lines] = Rect(origin.x.toInt(), origin.y.toInt(), origin.x.toInt() + it.size.width, origin.y.toInt() + it.size.height)
                        }) { repeat(lines) { SheetAction("Line $it", {}) } }
                        Spacer(Modifier.height(9.dp))
                    }
                }
            } }
            ui.await("Both content-sized cards must measure") { boxes.size == 2 }
            capture(activity, "bracket-geometry")
            val image = android.graphics.BitmapFactory.decodeFile(File(context.getExternalFilesDir(null), "widget-card-renders/bracket-geometry.png").path)
            val density = context.resources.displayMetrics.density
            val padding = 2 * kotlin.math.round(6 * density).toInt()
            assertEquals((boxes.getValue(1).height() - padding) * 3 + padding, boxes.getValue(3).height())
            boxes.values.forEach { box ->
                val stroke = (density * 2).toInt()
                for (y in box.top + stroke until box.bottom - stroke) {
                    assertEquals("Left bracket continuous across the entire block", android.graphics.Color.BLACK, image.getPixel(box.left + 1, y))
                    assertEquals("Right bracket continuous across the entire block", android.graphics.Color.BLACK, image.getPixel(box.right - 2, y))
                }
                assertEquals("Left top cap", android.graphics.Color.BLACK, image.getPixel(box.left + (density * 5).toInt(), box.top + 1))
                assertEquals("Right bottom cap", android.graphics.Color.BLACK, image.getPixel(box.right - (density * 5).toInt(), box.bottom - 2))
            }
            image.recycle()
        } finally { main { activity.finish() } }
    }

    private fun bounds(label: String) = Rect().also { checkNotNull(ui.find(label)).getBoundsInScreen(it) }

    private fun clickOpen(label: String) {
        val card = cardBounds(label)
        val target = checkNotNull(ui.find { node ->
            val rect = Rect().also(node::getBoundsInScreen)
            node.isClickable && node.actionList.any { it.label?.toString() == context.getString(R.string.paper_external) } && card.contains(rect)
        })
        assertTrue(target.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }

    private fun cardBounds(label: String) = Rect().also { rectangle ->
        checkNotNull(ui.findAncestor(label) { node -> node.actionList.any {
            it.label?.toString() in listOf(context.getString(R.string.lockscreen_widgets_up), context.getString(R.string.lockscreen_widgets_down))
        } } ?: ui.findAncestor(label) { it.isClickable }).getBoundsInScreen(rectangle)
    }

    private fun drag(x: Float, start: Float, end: Float, cancel: Boolean = false, beforeDrop: () -> Unit) {
        val down = SystemClock.uptimeMillis()
        fun event(action: Int, y: Float) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(instrumentation.platformAutomation.injectInputEvent(event, true)); event.recycle()
        }
        event(MotionEvent.ACTION_DOWN, start)
        SystemClock.sleep(700)
        for (step in 1..12) { event(MotionEvent.ACTION_MOVE, start + (end - start) * step / 12); SystemClock.sleep(30) }
        try { beforeDrop() } finally { event(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, end) }
        instrumentation.waitForIdleSync()
    }

    private fun capture(activity: MainActivity, name: String) {
        instrumentation.waitForIdleSync()
        val committed = CountDownLatch(1)
        main {
            activity.window.decorView.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
            activity.window.decorView.invalidate()
        }
        assertTrue("Frame committed", committed.await(10, TimeUnit.SECONDS))
        val decor = activity.window.decorView
        val window = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        val copied = CountDownLatch(1)
        var status = -1
        android.view.PixelCopy.request(activity.window, window, { status = it; copied.countDown() },
            android.os.Handler(android.os.Looper.getMainLooper()))
        assertTrue(copied.await(10, TimeUnit.SECONDS))
        assertEquals(android.view.PixelCopy.SUCCESS, status)
        val content = activity.findViewById<android.view.View>(android.R.id.content)
        val origin = IntArray(2).also { content.getLocationOnScreen(it) }
        val decorOrigin = IntArray(2).also { decor.getLocationOnScreen(it) }
        // MainActivity is edge-to-edge: system bars are composited outside the app's window buffer.
        val insets = decor.rootWindowInsets.getInsets(android.view.WindowInsets.Type.systemBars())
        val top = maxOf(origin[1], insets.top)
        val bottom = minOf(origin[1] + content.height, decor.height - insets.bottom)
        val height = bottom - top
        val expected = IntArray(content.width * height)
        window.getPixels(expected, 0, content.width, origin[0] - decorOrigin[0], top - decorOrigin[1], content.width, height)
        var frame: Bitmap? = null
        ui.await("Displayed frame must equal committed window") {
            val candidate = instrumentation.platformAutomation.takeScreenshot()
            val pixels = IntArray(expected.size)
            candidate.getPixels(pixels, 0, content.width, origin[0], top, content.width, height)
            if (expected.contentEquals(pixels)) { frame = candidate; true } else { candidate.recycle(); false }
        }
        window.recycle()
        val directory = File(context.getExternalFilesDir(null), "widget-card-renders").apply { mkdirs() }
        checkNotNull(frame).let { bitmap ->
            File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        }
    }
}
