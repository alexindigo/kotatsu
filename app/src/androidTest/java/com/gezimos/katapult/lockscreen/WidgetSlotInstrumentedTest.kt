package com.gezimos.katapult.lockscreen

import android.appwidget.AppWidgetHostView
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.MotionEvent
import android.graphics.PathMeasure
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.MainActivity
import com.gezimos.katapult.WidgetHostTestFixture
import com.gezimos.katapult.PlatformUiDriver
import com.gezimos.katapult.Screen
import com.gezimos.katapult.R
import org.junit.Assert.*
import org.junit.Test

class WidgetSlotInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    @Test fun paperReservesTheFullStockDateViewInsteadOfOnlyItsInk() {
        // Native staging ViewHierarchyEncoder: id/date, TextClock, [81,234..400,271).
        val date = android.graphics.Rect(81, 234, 400, 271)
        val geometry = LockscreenWidgetBoxGeometry.from(android.graphics.Rect(0, 0, 480, 800),
            context.resources.displayMetrics.density)
        assertTrue("Paper must not cover date descenders", geometry.paper.top >= date.bottom)
        assertEquals("Reserve exactly the full date view", date.bottom, geometry.paper.top)
        assertEquals("No extra top inset", geometry.paper.top, geometry.content.top)
    }

    @Test fun edgeExclusionIntersectsTheDisplayRatherThanInsettingContentAgain() {
        val display = android.graphics.Rect(0, 0, 480, 800)
        val content = LockscreenWidgetBoxGeometry.from(display, context.resources.displayMetrics.density).content
        assertEquals("Already inset content keeps its complete input area", content, EdgeGestures.slotArea(content, display, 21))
        assertEquals(android.graphics.Rect(21, 271, 459, 779),
            EdgeGestures.slotArea(android.graphics.Rect(0, 271, 480, 779), display, 21))
        val narrower = android.graphics.Rect(30, 271, 450, 779)
        assertEquals(narrower, EdgeGestures.slotArea(narrower, display, 21))
        assertEquals(content, EdgeGestures.slotArea(content, display, 0))
        assertTrue(EdgeGestures.slotArea(android.graphics.Rect(500, 271, 600, 779), display, 21).isEmpty)
    }

    @Test fun weatherAndQuoteInEverySizeEndAtThePreSlotBoxContentBottom() {
        WidgetHostTestFixture(context).use { fixture ->
            val ui = PlatformUiDriver(instrumentation)
            context.contentResolver.call(android.net.Uri.parse("content://com.mudita.notes/notes"), "reset", null, null)
            val providers = listOf(fixture.providers.singleProvider("com.alexindigo.kotatsu.weather.debug"),
                fixture.manager.installedProviders.single {
                    it.provider.packageName == "com.alexindigo.kotatsu.notes.debug" && it.provider.className.endsWith(".QuoteWidgetProvider")
                })
            val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val density = context.resources.displayMetrics.density
            val window = android.graphics.Rect(0, 0, 480, 800)
            val visibility = LockscreenVisibility().apply { beginEpoch(); recheck(true) { false } }
            var slot: LockscreenWidgetSlot? = null
            try {
                for (provider in providers) for (size in LockscreenWidgetSize.entries) {
                    val widget = LockscreenWidget(fixture.bind(provider), size)
                    if (provider.provider.className.endsWith(".QuoteWidgetProvider")) {
                        context.startActivity(Intent().setComponent(android.content.ComponentName("com.alexindigo.kotatsu.notes.debug",
                            "com.alexindigo.kotatsu.notes.QuoteConfigureActivity"))
                            .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, widget.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        ui.click("Note"); ui.click("Kotatsu quotes"); ui.back()
                    }
                    var expected = 0
                    val geometry = LockscreenWidgetBoxGeometry.from(window, density)
                    lateinit var content: LockscreenWidgetSlot
                    lateinit var box: LockscreenWidgetBox
                    main {
                        slot?.stop()
                        val requestedHeight = (size.heightDp * density).toInt()
                        // Independent native oracle: the pre-slot box's actual bottom gravity/padding.
                        val legacy = android.widget.LinearLayout(activity).apply {
                            orientation = android.widget.LinearLayout.VERTICAL
                            gravity = android.view.Gravity.BOTTOM
                            setPadding((8 * density).toInt(), 0, (8 * density).toInt(), (16 * density).toInt())
                            addView(View(activity), android.widget.LinearLayout.LayoutParams(-1, requestedHeight))
                            measure(View.MeasureSpec.makeMeasureSpec(window.width(), View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(window.height(), View.MeasureSpec.EXACTLY))
                            layout(0, 0, window.width(), window.height())
                        }
                        expected = legacy.getChildAt(0).bottom
                        content = LockscreenWidgetSlot(activity, Handler(Looper.getMainLooper()), {
                            content.show(listOf(widget), WidgetRotation.WAKE, true, visibility)
                        }).also { slot = it }
                        content.show(listOf(widget), WidgetRotation.WAKE, true, visibility)
                        box = LockscreenWidgetBox(activity, content, geometry)
                        activity.setContentView(box)
                    }
                    ui.await("Nonempty provider must reach the slot before geometry is measured") {
                        var ready = false
                        main { ready = content.childCount == 1 }
                        ready
                    }
                    main {
                        val requestedHeight = (size.heightDp * density).toInt()
                        box.measure(View.MeasureSpec.makeMeasureSpec(geometry.paper.width(), View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(geometry.paper.height(), View.MeasureSpec.EXACTLY))
                        box.layout(geometry.paper.left, geometry.paper.top, geometry.paper.right, geometry.paper.bottom)
                        val child = content.getChildAt(0) as AppWidgetHostView
                        val actual = box.top + content.top + child.bottom
                        android.util.Log.i("WidgetSlotGeometry", "${provider.provider.className} $size widgetBottom=$actual preSlotBottom=$expected slot=${geometry.content}")
                        assertEquals("${provider.provider.className} $size pre-slot content bottom", expected.toFloat(), actual.toFloat(), 1f)
                        assertEquals(expected, geometry.content.bottom)
                        val inset = (16 * density).toInt()
                        assertEquals(inset, geometry.content.left - geometry.paper.left)
                        assertEquals(inset, geometry.paper.right - geometry.content.right)
                        assertEquals(inset, geometry.bottomPadding)
                        assertEquals("The slot adds no second margin", 0, content.paddingLeft)
                        assertEquals("The slot adds no second margin", 0, content.paddingRight)
                        assertEquals(geometry.content.width(), child.width)
                        assertEquals(inset, box.left + content.left + child.left)
                        assertEquals(geometry.content.height(), content.height)
                        assertEquals(requestedHeight, child.height)
                        assertEquals(widget.id, child.appWidgetId)
                        assertEquals(window.bottom, box.bottom)
                    }
                }
            } finally { main { slot?.stop(); activity.finish(); fixture.host.releaseViews() } }
        }
    }

    @Test fun oneBottomAlignedChildRotatesOnWakeAndClockAndPublishesExclusiveVisibility() {
        WidgetHostTestFixture(context).use { fixture ->
            val info = fixture.providers.singleProvider(instrumentation.context.packageName)
            val widgets = LockscreenWidgetSize.entries.map { LockscreenWidget(fixture.bind(info), it) }
            val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val visibility = LockscreenVisibility().apply { beginEpoch(); recheck(true) { false } }
            lateinit var slot: LockscreenWidgetSlot
            var timer: (() -> Unit)? = null
            var mode = WidgetRotation.WAKE
            fun render() = slot.show(widgets, mode, true, visibility)
            fun checkShown(index: Int) {
                instrumentation.waitForIdleSync()
                main {
                    slot.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY))
                    slot.layout(0, 0, 480, 400)
                    assertEquals(1, slot.childCount)
                    val child = slot.getChildAt(0) as AppWidgetHostView
                    assertEquals(widgets[index].id, child.appWidgetId)
                    assertEquals(slot.height.toFloat(), child.bottom.toFloat(), 1f)
                    assertEquals((widgets[index].size.heightDp * context.resources.displayMetrics.density).toInt(), child.height)
                    assertEquals(HostedWidgetWake(context).current(), fixture.manager.getAppWidgetOptions(widgets[index].id)
                        .getLong(LockscreenWidgetHost.OPTION_HOST_WAKE))
                    widgets.forEachIndexed { i, widget ->
                        val options = fixture.manager.getAppWidgetOptions(widget.id)
                        assertTrue(options.containsKey(LockscreenWidgetHost.OPTION_HOST_VISIBLE))
                        assertEquals(i == index, options.getBoolean(LockscreenWidgetHost.OPTION_HOST_VISIBLE))
                    }
                }
            }
            try {
                main {
                    slot = LockscreenWidgetSlot(activity, Handler(Looper.getMainLooper()), { render() }, { delay, action ->
                        assertEquals(30_000L, delay)
                        timer = action
                        AutoCloseable { if (timer === action) timer = null }
                    })
                    activity.setContentView(slot)
                    render()
                }
                checkShown(0)
                repeat(3) { i ->
                    val previousTokens = widgets.associate { it.id to fixture.manager.getAppWidgetOptions(it.id)
                        .getLong(LockscreenWidgetHost.OPTION_HOST_WAKE, -1L) }
                    main {
                        slot.hide(); HostedWidgetWake(context).begin(); visibility.beginEpoch(); visibility.recheck(true) { false }
                        slot.rotation.wake(); render()
                    }
                    checkShown((i + 1) % 3)
                    widgets.filterIndexed { index, _ -> index != (i + 1) % 3 }.forEach { widget ->
                        assertEquals("Hidden widgets must not consume the new wake", previousTokens[widget.id],
                            fixture.manager.getAppWidgetOptions(widget.id).getLong(LockscreenWidgetHost.OPTION_HOST_WAKE, -1L))
                    }
                }
                main { mode = WidgetRotation.SECONDS_30; render() }
                checkShown(0)
                repeat(3) { i -> main { val tick = timer!!; timer = null; tick() }; checkShown((i + 1) % 3) }
                main { slot.rotation.swipe(-1) }; checkShown(2)
                main { slot.rotation.swipe(1) }; checkShown(0)
                main { slot.hide(); assertNull(timer) }
                widgets.forEach { assertFalse(fixture.manager.getAppWidgetOptions(it.id).getBoolean(LockscreenWidgetHost.OPTION_HOST_VISIBLE)) }
                main { slot.rotation.wake(); render() }; checkShown(0)
            } finally { main { slot.stop(); activity.finish(); fixture.host.releaseViews() } }
        }
    }

    @Test fun longListFitsOneSlotAndRotationPreferencesHaveStrictPortableTypes() {
        WidgetHostTestFixture(context).use { fixture ->
            val prefs = fixture.prefs
            val store = prefs.lockscreenWidgetStore
            val info = fixture.providers.singleProvider(instrumentation.context.packageName)
            val widgets = List(5) { LockscreenWidget(fixture.bind(info), LockscreenWidgetSize.L) }
            main { widgets.forEach { assertEquals(LockscreenWidgetStore.Result.APPLIED, store.addAfterConfigure(it)) } }
            assertEquals(widgets, store.read())
            assertEquals(WidgetRotation.WAKE, prefs.widgetRotation)
            assertTrue(prefs.widgetSwipeToSwitch)
            prefs.widgetRotation = WidgetRotation.MINUTES_5
            prefs.widgetSwipeToSwitch = false
            val backup = prefs.exportToJson(context)
            prefs.widgetRotation = WidgetRotation.WAKE
            prefs.widgetSwipeToSwitch = true
            assertTrue(prefs.importFromJson(backup))
            assertEquals(WidgetRotation.MINUTES_5, prefs.widgetRotation)
            assertFalse(prefs.widgetSwipeToSwitch)
            for ((key, value) in listOf("lockscreen_widget_rotation" to "bogus", "lockscreen_widget_rotation" to 30,
                "lockscreen_widget_swipe" to "true")) {
                assertFalse(prefs.importFromJson(org.json.JSONObject(backup).put(key, value).toString()))
                assertEquals(WidgetRotation.MINUTES_5, prefs.widgetRotation)
                assertFalse(prefs.widgetSwipeToSwitch)
            }
        }
    }

    @Test fun nativeSlotStreamSwitchesOnShortHorizontalSwipesAndHandsTapAndUpPathOffOnlyAfterLift() {
        main {
            val view = View(context)
            val switches = mutableListOf<Int>()
            val replayed = mutableListOf<List<Float>>()
            var tracking = false
            var allowed = true
            view.setOnTouchListener(SlotGestureCapture(480, 800, 8f, { allowed }, switches::add,
                { path, duration ->
                    assertFalse(tracking)
                    assertEquals(100L, duration)
                    val measure = PathMeasure(path, false)
                    val start = FloatArray(2); val end = FloatArray(2)
                    measure.getPosTan(0f, start, null); measure.getPosTan(measure.length, end, null)
                    replayed += listOf(start[0], start[1], end[0], end[1])
                }, { tracking = it }))
            fun send(action: Int, x: Float, y: Float, time: Long) {
                MotionEvent.obtain(1000L, time, action, x, y, 0).also { view.dispatchTouchEvent(it); it.recycle() }
            }
            fun gesture(dx: Float, dy: Float) {
                val before = replayed.size
                send(MotionEvent.ACTION_DOWN, 240f, 550f, 1000L)
                send(MotionEvent.ACTION_MOVE, 240f + dx / 2, 550f + dy / 2, 1050L)
                assertEquals("No replay under the finger", before, replayed.size)
                send(MotionEvent.ACTION_UP, 240f + dx, 550f + dy, 1100L)
            }
            gesture(-9f, 0f); gesture(9f, 0f)
            assertEquals(listOf(1, -1), switches)
            assertTrue(replayed.isEmpty())
            gesture(1f, 1f)
            gesture(0f, -300f)
            assertEquals(listOf(listOf(240f, 550f, 241f, 551f), listOf(240f, 550f, 240f, 250f)), replayed)
            send(MotionEvent.ACTION_DOWN, 240f, 550f, 1000L)
            send(MotionEvent.ACTION_POINTER_DOWN, 240f, 550f, 1050L)
            send(MotionEvent.ACTION_UP, 240f, 550f, 1100L)
            assertEquals(2, replayed.size)
            send(MotionEvent.ACTION_DOWN, 240f, 550f, 1000L)
            allowed = false
            send(MotionEvent.ACTION_UP, 240f, 250f, 1100L)
            assertEquals(2, replayed.size)
        }
    }

    @Test fun rotationAndSwipeSettingsUseRightHandValuesAndSurviveRecreation() {
        WidgetHostTestFixture(context, applicationPreferences = true).use { fixture ->
            val previous = fixture.raw.all.filterKeys { it in setOf("lockscreen_widget_rotation", "lockscreen_widget_swipe") }
            val ui = PlatformUiDriver(instrumentation)
            try {
                fixture.prefs.widgetRotation = WidgetRotation.WAKE
                fixture.prefs.widgetSwipeToSwitch = true
                androidx.test.core.app.ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                    scenario.onActivity {
                        it.viewModel.navigateTo(Screen.SETTINGS)
                        it.viewModel.settingsPageRequest = "widgets"
                    }
                    fun aligned(title: Int, value: Int) {
                        ui.await("Missing current rotation value") { ui.has(context.getString(value)) }
                        val nameBounds = android.graphics.Rect().also {
                            ui.find { n -> n.text?.toString() == context.getString(title) }!!.getBoundsInScreen(it)
                        }
                        val valueBounds = android.graphics.Rect().also {
                            ui.find { n -> n.text?.toString() == context.getString(value) }!!.getBoundsInScreen(it)
                        }
                        assertEquals(nameBounds.exactCenterY(), valueBounds.exactCenterY(), 1f)
                        assertTrue(valueBounds.left > nameBounds.right)
                    }
                    aligned(R.string.widget_rotation, R.string.widget_rotation_wake)
                    ui.click(context.getString(R.string.widget_rotation))
                    ui.click(context.getString(R.string.widget_rotation_30s))
                    ui.await("Rotation not persisted") { fixture.prefs.widgetRotation == WidgetRotation.SECONDS_30 }
                    aligned(R.string.widget_rotation, R.string.widget_rotation_value_30s)
                    ui.click(context.getString(R.string.widget_swipe))
                    ui.await("Swipe preference not persisted") { !fixture.prefs.widgetSwipeToSwitch }
                    scenario.recreate()
                    aligned(R.string.widget_rotation, R.string.widget_rotation_value_30s)
                    assertFalse(fixture.prefs.widgetSwipeToSwitch)
                    ui.click(context.getString(R.string.widget_rotation))
                    ui.back()
                    aligned(R.string.widget_rotation, R.string.widget_rotation_value_30s)
                }
            } finally {
                val edit = fixture.raw.edit().remove("lockscreen_widget_rotation").remove("lockscreen_widget_swipe")
                previous.forEach { (key, value) -> when (value) {
                    is String -> edit.putString(key, value)
                    is Boolean -> edit.putBoolean(key, value)
                } }
                assertTrue(edit.commit())
            }
        }
    }
}
