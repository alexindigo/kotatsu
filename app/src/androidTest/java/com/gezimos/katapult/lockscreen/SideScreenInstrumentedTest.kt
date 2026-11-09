package com.gezimos.katapult.lockscreen

import com.gezimos.katapult.platformAutomation
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.*
import org.junit.Assert.*
import org.junit.Test

class SideScreenInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val ui = PlatformUiDriver(instrumentation)
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun rows(id: Int, count: Int) = context.sendBroadcast(Intent(instrumentation.context.packageName + ".SIDE_ROWS")
        .setComponent(android.content.ComponentName(instrumentation.context.packageName, SideTestWidget::class.java.name))
        .putExtra("id", id).putExtra("count", count))
    private fun drag(x: Float, from: Float, to: Float) {
        val time = android.os.SystemClock.uptimeMillis()
        for (step in 0..20) {
            android.view.MotionEvent.obtain(time, android.os.SystemClock.uptimeMillis(), when (step) {
                0 -> android.view.MotionEvent.ACTION_DOWN; 20 -> android.view.MotionEvent.ACTION_UP; else -> android.view.MotionEvent.ACTION_MOVE
            }, x, from + (to - from) * step / 20f, 0).also {
                it.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                instrumentation.sendPointerSync(it); it.recycle()
            }
            android.os.SystemClock.sleep(20)
        }
    }
    private fun activity() = instrumentation.startActivitySync(Intent(Intent.ACTION_MAIN).setClassName(context.packageName,
        MainActivity::class.java.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    @Test fun settingsUiEditsNameAndLayoutAndPublishesOnlyAfterConsentAndConfiguration() {
        WidgetHostTestFixture(context, applicationPreferences = true).use { fixture ->
            val previous = fixture.raw.all[LockscreenWidgetStore.SCREENS]
            assertTrue(fixture.raw.edit().remove(LockscreenWidgetStore.SCREENS).commit())
            try {
                androidx.test.core.app.ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                    lateinit var providerLabel: String
                    scenario.onActivity {
                        it.viewModel.navigateTo(Screen.SETTINGS)
                        it.viewModel.settingsPageRequest = "side-screen"
                        providerLabel = it.viewModel.lockscreenWidgets.providers().single { provider ->
                            provider.info.provider.packageName == instrumentation.context.packageName
                        }.label
                    }
                    ui.click(context.getString(R.string.side_name))
                    ui.await("No screen name editor") { ui.find { it.isEditable } != null }
                    assertTrue(ui.find { it.isEditable }!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,
                        Bundle().apply { putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "My side screen") }))
                    scenario.recreate()
                    ui.await("Name draft lost on recreation") { ui.has("My side screen") }
                    ui.click(context.getString(R.string.save))
                    ui.await("Name not persisted/rendered") { fixture.prefs.lockscreenWidgetStore.screens().first().name == "My side screen" && ui.has("My side screen") }
                    fun indexValue(title: String, value: String) {
                        ui.await("$title index value not rendered: $value") {
                            ui.find { node -> node.text?.toString() == title } != null &&
                                ui.find { node -> node.text?.toString() == value } != null
                        }
                        val nameBounds = android.graphics.Rect().also {
                            ui.find { node -> node.text?.toString() == title }!!.getBoundsInScreen(it)
                        }
                        val valueBounds = android.graphics.Rect().also {
                            ui.find { node -> node.text?.toString() == value }!!.getBoundsInScreen(it)
                        }
                        assertEquals("$title value must share its index line", nameBounds.exactCenterY(), valueBounds.exactCenterY(), 1f)
                        assertTrue("$title value must be to the right", valueBounds.left > nameBounds.right)
                    }
                    indexValue(context.getString(R.string.side_name), "My side screen")
                    indexValue(context.getString(R.string.side_layout), context.getString(R.string.side_full))
                    indexValue(context.getString(R.string.side_widget), context.getString(R.string.side_choose))
                    assertTrue(ui.has(context.getString(R.string.side_layout_help)))
                    assertFalse("Values must not be replaced by Open", ui.has(context.getString(R.string.paper_open)))
                    ui.click(context.getString(R.string.side_layout))
                    ui.click(context.getString(R.string.side_split))
                    ui.await("Split cells not rendered") { ui.has(context.getString(R.string.side_top)) && ui.has(context.getString(R.string.side_bottom)) }
                    assertEquals(SideLayout.SPLIT, fixture.prefs.lockscreenWidgetStore.screens().first().layout)
                    indexValue(context.getString(R.string.side_layout), context.getString(R.string.side_split))
                    ui.click(context.getString(R.string.side_top))
                    fun choose() {
                        ui.click(context.getString(R.string.side_choose_widget))
                        var pages = 0
                        while (!ui.has(providerLabel)) {
                            assertTrue("Provider absent from Settings pages", ++pages < 8)
                            ui.click(context.getString(R.string.paper_next_page))
                        }
                        ui.click(providerLabel)
                    }
                    fun systemButton(label: String) {
                        ui.await("Missing system $label") { ui.find { it.text?.toString()?.equals(label, true) == true } != null }
                        ui.click(ui.find { it.text?.toString()?.equals(label, true) == true }!!.text.toString())
                    }
                    val before = fixture.host.appWidgetIds.toSet()
                    choose()
                    systemButton("Cancel")
                    ui.await("Bind cancellation leaked allocation") { fixture.host.appWidgetIds.toSet() == before }
                    assertNull(fixture.prefs.lockscreenWidgetStore.screens().first().slots[0])
                    choose()
                    systemButton("Create")
                    ui.await("Configure not opened") { ui.find { it.text?.toString()?.equals("Use fixture", true) == true } != null }
                    assertNull(fixture.prefs.lockscreenWidgetStore.screens().first().slots[0])
                    ui.back()
                    ui.await("Configure cancellation leaked allocation") { fixture.host.appWidgetIds.toSet() == before }
                    choose()
                    systemButton("Create")
                    systemButton("Use fixture")
                    ui.await("Configured ID not published") { fixture.prefs.lockscreenWidgetStore.screens().first().slots[0] != null }
                    ui.await("Configured slot management did not return") { ui.has(context.getString(R.string.lockscreen_widgets_remove)) }
                    ui.back()
                    indexValue(context.getString(R.string.side_top), fixture.manager.getAppWidgetInfo(
                        fixture.prefs.lockscreenWidgetStore.screens().first().slots[0]!!).loadLabel(context.packageManager))
                    scenario.recreate()
                    scenario.onActivity {
                        assertEquals("My side screen", it.viewModel.lockscreenWidgets.screenName())
                        assertNotNull(it.viewModel.lockscreenWidgets.screens.first().slots[0])
                    }
                }
            } finally {
                val edit = fixture.raw.edit()
                if (previous == null) edit.remove(LockscreenWidgetStore.SCREENS) else edit.putString(LockscreenWidgetStore.SCREENS, previous as String)
                assertTrue(edit.commit())
            }
        }
    }

    @Test fun pendingSideConfigurationRestoresItsCellAndCancellationNeverRemovesPublishedContent() {
        WidgetHostTestFixture(context).use { fixture ->
            val info = fixture.providers.singleProvider(instrumentation.context.packageName)
            val original = fixture.bind(info)
            val store = fixture.prefs.lockscreenWidgetStore
            main { store.placeAfterConfigure(0, original) }
            lateinit var first: LockscreenWidgetSettings
            lateinit var restored: LockscreenWidgetSettings
            var replacement = -1
            main { first = LockscreenWidgetSettings(context, fixture.prefs); restored = LockscreenWidgetSettings(context, fixture.prefs) }
            try {
                instrumentation.platformAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
                try { main { first.add(info, { fail("Bind permitted") }, { replacement = it }, 0) } }
                finally { instrumentation.platformAutomation.dropShellPermissionIdentity() }
                main {
                    val saved = Bundle(); first.saveState(saved); restored.restoreState(saved)
                    assertEquals(original, store.screens().first().slots[0])
                    restored.configured(true)
                    first.close()
                    assertEquals(replacement, store.screens().first().slots[0])
                    assertFalse(original in fixture.host.appWidgetIds)
                    restored.edit(replacement, {}, 0); restored.configured(false)
                    assertEquals(replacement, store.screens().first().slots[0])
                    assertTrue(replacement in fixture.host.appWidgetIds)
                }
            } finally { main { first.close(); restored.close() } }
        }
    }

    @Test fun everyBoxMutationRespectsSideReservationsIncludingDormantCells() {
        WidgetHostTestFixture(context).use { fixture ->
            val info = fixture.providers.singleProvider(instrumentation.context.packageName)
            val ids = List(3) { fixture.bind(info) }
            val store = fixture.prefs.lockscreenWidgetStore
            main {
                assertEquals(LockscreenWidgetStore.Result.APPLIED, store.placeAfterConfigure(0, ids[0]))
                assertEquals(LockscreenWidgetStore.Result.APPLIED, store.placeAfterConfigure(1, ids[1]))
                ids.take(2).forEach { id ->
                    assertEquals(LockscreenWidgetStore.Result.INVALID, store.addAfterConfigure(LockscreenWidget(id, LockscreenWidgetSize.S)))
                }
                assertEquals(LockscreenWidgetStore.Result.APPLIED, store.replaceImportedSelection(
                    LockscreenWidgetList.encode(ids.map { LockscreenWidget(it, LockscreenWidgetSize.S) })))
                assertEquals(listOf(ids[2]), store.read().map { it.id })
                assertEquals(listOf<Int?>(ids[0], ids[1]), store.screens().first().slots)
                store.discardPending(ids[1])
                assertTrue(ids.all { it in fixture.host.appWidgetIds })
                assertEquals(LockscreenWidgetStore.Result.INVALID, store.placeAfterConfigure(0, ids[2]))
            }
        }
    }

    @Test fun screenReconciliationReturnsTheCurrentReentrantPreferenceTransaction() {
        WidgetHostTestFixture(context).use { fixture ->
            val id = fixture.bind(fixture.providers.singleProvider(instrumentation.context.packageName))
            val ghost = fixture.host.allocateAppWidgetId()
            assertTrue(fixture.raw.edit().putString(LockscreenWidgetStore.SCREENS,
                SideScreenList.encode(listOf(SideScreen(slots = listOf(id, ghost))))).commit())
            val store = fixture.prefs.lockscreenWidgetStore
            val armed = java.util.concurrent.atomic.AtomicBoolean(true)
            val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == LockscreenWidgetStore.SCREENS && armed.compareAndSet(true, false)) store.nameScreen("Reentrant screen")
            }
            fixture.raw.registerOnSharedPreferenceChangeListener(listener)
            try { main {
                assertEquals("Reentrant screen", store.screens().first().name)
                assertEquals(listOf<Int?>(id, null), store.screens().first().slots)
            } } finally { fixture.raw.unregisterOnSharedPreferenceChangeListener(listener) }
        }
    }

    @Test fun settingsPublishOnlyConfiguredSlotsAndRetainThemAcrossLayoutAndImport() {
        WidgetHostTestFixture(context).use { fixture ->
            val info = fixture.providers.singleProvider(instrumentation.context.packageName)
            lateinit var settings: LockscreenWidgetSettings
            var pending = -1
            main { settings = LockscreenWidgetSettings(context, fixture.prefs) }
            try {
                instrumentation.platformAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
                try { main { settings.add(info, { fail("Expected allowed bind") }, { pending = it }, 0) } }
                finally { instrumentation.platformAutomation.dropShellPermissionIdentity() }
                assertTrue(pending > 0)
                assertEquals(listOf<Int?>(null, null), fixture.prefs.lockscreenWidgetStore.screens().first().slots)
                val state = Bundle()
                main { settings.saveState(state); settings.configured(true) }
                assertEquals(pending, fixture.prefs.lockscreenWidgetStore.screens().first().slots[0])
                val options = fixture.manager.getAppWidgetOptions(pending)
                assertTrue(options.getBoolean(LockscreenWidgetHost.OPTION_HOST_TITLE))
                assertEquals(android.appwidget.AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN,
                    options.getInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY))
                assertEquals("Side test list", settings.screenName())
                main { settings.nameScreen("My screen"); settings.layoutScreen(SideLayout.SPLIT) }
                val second = fixture.bind(info)
                main { assertEquals(LockscreenWidgetStore.Result.APPLIED, fixture.prefs.lockscreenWidgetStore.placeAfterConfigure(1, second)) }
                main { settings.layoutScreen(SideLayout.FULL); settings.layoutScreen(SideLayout.SPLIT) }
                assertEquals(listOf(pending, second), fixture.prefs.lockscreenWidgetStore.screens().first().placements)
                val imported = org.json.JSONObject(fixture.prefs.exportToJson(context))
                    .put(LockscreenWidgetStore.SCREENS, "foreign widget IDs")
                assertTrue(fixture.prefs.importFromJson(imported.toString()))
                assertEquals("My screen", fixture.prefs.lockscreenWidgetStore.screens().first().name)
                assertEquals(listOf(pending, second), fixture.prefs.lockscreenWidgetStore.screens().first().placements)
                val cancelled = fixture.bind(info)
                main { fixture.prefs.lockscreenWidgetStore.discardPending(cancelled) }
                assertFalse(cancelled in fixture.host.appWidgetIds)
                main { settings.removeSlot(1) }
                assertFalse(second in fixture.host.appWidgetIds)
                assertEquals(pending, fixture.prefs.lockscreenWidgetStore.screens().first().slots[0])
            } finally { main { settings.close() } }
        }
    }

    @Test fun railDrivesCrossUidHostedListHidesItsScrollbarAndCloseUsesSharedOwner() {
        WidgetHostTestFixture(context).use { fixture ->
            val info = fixture.providers.singleProvider(instrumentation.context.packageName)
            val id = fixture.bind(info)
            val activity = activity()
            lateinit var root: SideScreenView
            lateinit var slot: HostedWidgetSlot
            lateinit var list: AbsListView
            var closes = 0
            var created = false
            try {
                main {
                    root = SideScreenView(activity, SideScreen(slots = listOf(id, null)), { closes++ },
                        { _, _ -> false }, { _, _ -> false }, {}, {})
                    created = true
                    activity.setContentView(root)
                    root.settled()
                }
                ui.await("Hosted collection did not arrive") {
                    var ready = false
                    main {
                        slot = descendants(root).filterIsInstance<HostedWidgetSlot>().single()
                        val found = descendants(slot).filterIsInstance<AbsListView>().firstOrNull()
                        if (found != null) { list = found; ready = found.childCount > 0 && slot.rail != null }
                    }
                    ready
                }
                main {
                    assertFalse(list.isVerticalScrollBarEnabled)
                    assertTrue(descendants(root).filterIsInstance<TextView>().none { it.text.toString() == "Fixture heading" && it.visibility == View.VISIBLE })
                    assertEquals(0, list.firstVisiblePosition)
                    // A provider's new adapter/selection can have populated children while its
                    // pending native layout still owns their position. The rail must wait for it.
                    list.setSelectionFromTop(0, 0)
                    assertTrue(list.isLayoutRequested)
                    slot.step(1)
                }
                ui.await("Rail arrow did not scroll the hosted list") { var moved = false; main {
                    moved = list.firstVisiblePosition > 0 && !list.isLayoutRequested && !slot.isLayoutRequested
                }; moved }
                main {
                    list.setSelectionFromTop(list.firstVisiblePosition, list.getChildAt(0).top)
                    assertTrue(list.isLayoutRequested)
                    slot.moveTo(0)
                }
                ui.await("Rail progress did not return to the first row") { var top = false; main { top = list.firstVisiblePosition == 0 && list.getChildAt(0).top == list.paddingTop }; top }
                ui.click(context.getString(R.string.edge_task_scroll_down))
                ui.await("Rendered rail arrow did not move the hosted list") {
                    var moved = false; main { moved = list.firstVisiblePosition > 0 && !list.isLayoutRequested }; moved
                }
                fun progress(value: Float) {
                    assertTrue(ui.find(context.getString(R.string.edge_task_scroll))!!.performAction(
                        android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                        Bundle().apply { putFloat(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value) }))
                }
                progress(Float.MAX_VALUE)
                ui.await("Rail did not reach native end") { var end = false; main { end = !list.canScrollList(1) && !list.isLayoutRequested }; end }
                try { ui.await("Native end not reflected in rail semantics") {
                    ui.find(context.getString(R.string.edge_task_scroll_down))?.isEnabled == false
                } } catch (error: AssertionError) { main {
                    throw AssertionError("Native end: rail=${slot.rail}, first=${list.firstVisiblePosition}/${list.getChildAt(0).top}/${list.getChildAt(0).height}, last=${list.lastVisiblePosition}/${list.getChildAt(list.childCount - 1).bottom}, viewport=${list.height}", error)
                } }
                assertTrue(ui.find(context.getString(R.string.edge_task_scroll_up))!!.isEnabled)
                progress(0f)
                ui.await("Progress did not restore native top") { var top = false; main { top = list.firstVisiblePosition == 0 && list.getChildAt(0).top == list.paddingTop }; top }
                val track = android.graphics.Rect().also { ui.find(context.getString(R.string.edge_task_scroll))!!.getBoundsInScreen(it) }
                drag(track.exactCenterX(), track.top + 2f, track.bottom - 2f)
                ui.await("Rendered thumb drag did not reach native bottom") { var end = false; main { end = !list.canScrollList(1) && !list.isLayoutRequested }; end }
                drag(track.exactCenterX(), track.bottom - 2f, track.top + 2f)
                ui.await("Reverse thumb drag did not reach native top") { var top = false; main { top = !list.canScrollList(-1) && !list.isLayoutRequested }; top }
                val visible = fixture.manager.getAppWidgetOptions(id)
                assertTrue(visible.getBoolean(LockscreenWidgetHost.OPTION_HOST_VISIBLE))
                main { root.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)); assertEquals(1, closes) }
                ui.click(context.getString(R.string.side_close))
                assertEquals(2, closes)
                rows(id, 1)
                try { ui.await("Non-overflow collection retained its rail") { var short = false; main {
                    short = descendants(slot).filterIsInstance<AbsListView>().singleOrNull()?.count == 1 && slot.rail == null
                }; short } } catch (error: AssertionError) { main {
                    throw AssertionError("Short collection: rail=${slot.rail}, slot=${slot.width}x${slot.height}/${slot.isLayoutRequested}, lists=${descendants(slot).filterIsInstance<AbsListView>().map { "${it.count}/${it.width}x${it.height}/${it.isLayoutRequested}/${it.isAttachedToWindow}" }}", error)
                } }
                assertFalse(ui.has(context.getString(R.string.edge_task_scroll)))
                main { root.dispose() }
                assertFalse(fixture.manager.getAppWidgetOptions(id).getBoolean(LockscreenWidgetHost.OPTION_HOST_VISIBLE))
            } finally { main { if (created) root.dispose(); activity.finish(); fixture.host.releaseViews() } }
        }
    }

    @Test fun fullAndSplitMeasureNativeCellsAndUnionVisibilityRetainsOtherSurfaceListening() {
        WidgetHostTestFixture(context).use { fixture ->
            val info = fixture.providers.singleProvider(instrumentation.context.packageName)
            val ids = List(3) { fixture.bind(info) }
            val activity = activity()
            var root: SideScreenView? = null
            val box = LockscreenWidget(ids[2], LockscreenWidgetSize.S)
            try {
                main {
                    fixture.host.listen(LockscreenWidgetHost.Surface.BOX, true)
                    fixture.host.showSurface(context, LockscreenWidgetHost.Surface.BOX, listOf(box))
                    root = SideScreenView(activity, SideScreen(layout = SideLayout.SPLIT, slots = ids.take(2)), {}, { _, _ -> false }, { _, _ -> false }, {}, {})
                    activity.setContentView(root); root!!.settled()
                }
                try { ui.await("Split native cells not measured") { var ready = false; main {
                    val slots = descendants(root!!).filterIsInstance<HostedWidgetSlot>()
                    ready = slots.size == 2 && slots.all { it.height > 0 && it.rail != null && !it.isLayoutRequested }
                }; ready } } catch (error: AssertionError) { main {
                    throw AssertionError("Split cells: ${descendants(root!!).filterIsInstance<HostedWidgetSlot>().map { slot -> "${slot.width}x${slot.height}/${slot.isLayoutRequested}/${slot.rail}, lists=${descendants(slot).filterIsInstance<AbsListView>().map { "${it.count}/${it.width}x${it.height}/${it.isLayoutRequested}/${it.isAttachedToWindow}" }}" }}", error)
                } }
                var splitHeight = 0
                main {
                    val cells = descendants(root!!).filterIsInstance<HostedWidgetSlot>()
                    assertEquals(cells[0].height.toFloat(), cells[1].height.toFloat(), 1f)
                    val a = IntArray(2).also(cells[0]::getLocationOnScreen)
                    val b = IntArray(2).also(cells[1]::getLocationOnScreen)
                    assertEquals(a[0], b[0])
                    assertEquals(2 * context.resources.displayMetrics.density, (b[1] - a[1] - cells[0].height).toFloat(), 1f)
                    splitHeight = cells[0].height
                    ids.forEach { assertTrue(fixture.manager.getAppWidgetOptions(it).getBoolean(LockscreenWidgetHost.OPTION_HOST_VISIBLE)) }
                    fixture.host.hideWidgets(context)
                    assertFalse(fixture.manager.getAppWidgetOptions(box.id).getBoolean(LockscreenWidgetHost.OPTION_HOST_VISIBLE))
                    ids.take(2).forEach { assertTrue(fixture.manager.getAppWidgetOptions(it).getBoolean(LockscreenWidgetHost.OPTION_HOST_VISIBLE)) }
                    // Stopping BOX must not stop the SIDE callback owner.
                    fixture.host.listen(LockscreenWidgetHost.Surface.BOX, false)
                }
                rows(ids[0], 7)
                ui.await("Stopping BOX also stopped SIDE updates") { var updated = false; main {
                    updated = descendants(root!!).filterIsInstance<AbsListView>().first().count == 7
                }; updated }
                main {
                    root!!.dispose()
                    ids.take(2).forEach { assertFalse(fixture.manager.getAppWidgetOptions(it).getBoolean(LockscreenWidgetHost.OPTION_HOST_VISIBLE)) }
                    root = SideScreenView(activity, SideScreen(slots = ids.take(2)), {}, { _, _ -> false }, { _, _ -> false }, {}, {})
                    activity.setContentView(root); root!!.settled()
                }
                ui.await("Full cell not measured") { var ready = false; main {
                    val cells = descendants(root!!).filterIsInstance<HostedWidgetSlot>()
                    ready = cells.size == 1 && cells.single().height > splitHeight * 1.9f
                }; ready }
                assertFalse("Dormant Split cell is not visible", fixture.manager.getAppWidgetOptions(ids[1]).getBoolean(LockscreenWidgetHost.OPTION_HOST_VISIBLE))
                // Conversely, closing SIDE must retain BOX delivery and its visible hint.
                main {
                    fixture.host.listen(LockscreenWidgetHost.Surface.BOX, true)
                    fixture.host.showSurface(context, LockscreenWidgetHost.Surface.BOX, listOf(box))
                    root!!.dispose()
                    assertTrue(fixture.manager.getAppWidgetOptions(box.id).getBoolean(LockscreenWidgetHost.OPTION_HOST_VISIBLE))
                }
            } finally { main {
                root?.dispose(); fixture.host.hideWidgets(context); fixture.host.listen(false)
                activity.finish(); fixture.host.releaseViews()
            } }
        }
    }

    @Test fun sharedWindowOwnsPreviewFocusOpenOnlyInversionCloseAndFailedAttachmentCleanup() {
        WidgetHostTestFixture(context).use { fixture ->
            val id = fixture.bind(fixture.providers.singleProvider(instrumentation.context.packageName))
            val activity = activity()
            lateinit var page: EdgePageWindow
            lateinit var root: SideScreenView
            var created = false
            var failAttachment = false
            var inspections = 0
            var inversions = 0
            val roots = mutableListOf<SideScreenView>()
            try {
                main {
                    val manager = activity.getSystemService(android.view.WindowManager::class.java)
                    val port = object : android.view.WindowManager by manager {
                        override fun getMaximumWindowMetrics() = manager.maximumWindowMetrics
                        override fun addView(view: View, params: ViewGroup.LayoutParams) {
                            if (failAttachment) throw android.view.WindowManager.BadTokenException("test-owned attachment failure")
                            (params as android.view.WindowManager.LayoutParams).apply {
                                type = android.view.WindowManager.LayoutParams.TYPE_APPLICATION_PANEL
                                token = activity.window.decorView.windowToken
                            }
                            manager.addView(view, params)
                        }
                    }
                    val owner = object : android.content.ContextWrapper(context),
                        androidx.lifecycle.LifecycleOwner by activity,
                        androidx.lifecycle.ViewModelStoreOwner by activity,
                        androidx.savedstate.SavedStateRegistryOwner by activity {
                        override val lifecycle get() = activity.lifecycle
                        override fun getSystemService(name: String): Any? = if (name == android.content.Context.WINDOW_SERVICE) port else super.getSystemService(name)
                    }
                    page = EdgePageWindow(owner, android.os.Handler(android.os.Looper.getMainLooper()), { inspections++; true }, {}, "Test side page") { back, capture, close ->
                        SideScreenView(owner, SideScreen(slots = listOf(id, null)), back, capture, close, {}, {}).also {
                            root = it; roots += it
                            it.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
                                override fun onChildViewRemoved(parent: View, child: View) = Unit
                                override fun onChildViewAdded(parent: View, child: View) {
                                    if ((child.background as? android.graphics.drawable.ColorDrawable)?.color == android.graphics.Color.BLACK &&
                                        child.layoutParams.width == -1 && child.layoutParams.height == -1) inversions++
                                }
                            })
                        }
                    }
                    created = true
                    page.preview(ScreenEdge.LEFT, 1)
                    assertEquals(1, inspections)
                    repeat(50) { page.preview(ScreenEdge.LEFT, it % 6 + 1) }
                    assertEquals(1, inspections)
                    assertEquals(0, inversions)
                    val flags = (root.layoutParams as android.view.WindowManager.LayoutParams).flags
                    assertTrue(flags and android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
                    assertTrue(flags and android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
                    page.cancelPreview()
                    assertFalse(root.isAttachedToWindow)
                    page.settle(ScreenEdge.RIGHT)
                }
                ui.await("Settled side window did not get real input focus") { var focused = false; main { focused = root.hasWindowFocus() && root.hasFocus() }; focused }
                ui.await("Side window did not render rail") { ui.has(context.getString(R.string.edge_task_scroll_down)) }
                main {
                    assertTrue(page.opened); assertEquals(0f, root.translationX)
                    assertEquals(1, inversions)
                    page.settle(ScreenEdge.RIGHT); assertEquals(1, inversions)
                }
                ui.click(context.getString(R.string.edge_task_scroll_down))
                ui.await("Native rail consumer did not move") { var moved = false; main { moved = descendants(root).filterIsInstance<AbsListView>().single().firstVisiblePosition > 0 }; moved }
                main { assertEquals(1, inversions) }
                ui.back()
                ui.await("Back did not remove the actual side window") { var closed = false; main { closed = !page.opened && !root.isAttachedToWindow }; closed }
                main { page.settle(ScreenEdge.LEFT) }
                ui.click(context.getString(R.string.side_close))
                main {
                    assertFalse(page.opened); assertFalse(root.isAttachedToWindow); assertEquals(2, inversions)
                    failAttachment = true
                    page.settle(ScreenEdge.LEFT)
                    assertFalse(page.opened)
                    assertTrue(roots.none { it.isAttachedToWindow })
                    assertTrue(descendants(root).filterIsInstance<HostedWidgetSlot>().all { it.childCount == 0 })
                    assertFalse(fixture.manager.getAppWidgetOptions(id).getBoolean(LockscreenWidgetHost.OPTION_HOST_VISIBLE))
                }
            } finally { main { if (created) page.close(); activity.finish(); fixture.host.releaseViews() } }
        }
    }

    @Test fun originalRemoteViewsDispatchUnlocksItsActivityAndCancelsBackOrScreenOffWithoutResending() {
        fun automation() = instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(automation().executeShellCommand(command))
            .bufferedReader().use { it.readText().trim() }
        val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
        val power = context.getSystemService(android.os.PowerManager::class.java)
        assertFalse("Requires the isolated emulator's credential-free starting state", keyguard.isDeviceSecure)
        val enabled = shell("settings get secure enabled_accessibility_services")
        val accessibility = shell("settings get secure accessibility_enabled")
        val nativeKeyguard = com.gezimos.katapult.NativeKeyguardFixture()
        val instance = LockscreenWidgetService::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val launchField = LockscreenWidgetService::class.java.getDeclaredField("widgetLaunch").apply { isAccessible = true }
        var service: LockscreenWidgetService? = null
        var original: WidgetActivityLaunch? = null
        var launch: WidgetActivityLaunch? = null
        var page: EdgePageWindow? = null
        var inspection: AndroidLockscreenInspection? = null
        var sends = 0
        var pageRemoved = false
        var failure: Throwable? = null
        val focusedGates = java.util.concurrent.atomic.AtomicInteger()
        val prematureRequests = java.util.concurrent.atomic.AtomicInteger()
        val requested = WidgetUnlockActivity::class.java.getDeclaredField("requested").apply { isAccessible = true }
        val lifecycle = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
        val focusProbe = androidx.test.runner.lifecycle.ActivityLifecycleCallback { activity, stage ->
            if (activity is WidgetUnlockActivity && stage == androidx.test.runner.lifecycle.Stage.CREATED) {
                val callback = activity.window.callback
                var observed = false
                activity.window.callback = object : android.view.Window.Callback by callback {
                    override fun onWindowFocusChanged(hasFocus: Boolean) {
                        if (hasFocus && !observed) {
                            observed = true
                            focusedGates.incrementAndGet()
                            if (requested.getBoolean(activity)) prematureRequests.incrementAndGet()
                        }
                        callback.onWindowFocusChanged(hasFocus)
                    }
                }
            }
        }
        main { lifecycle.addLifecycleCallback(focusProbe) }
        fun restore(action: () -> Unit) { try { action() } catch (error: Throwable) {
            if (failure == null) failure = error else failure!!.addSuppressed(error)
        } }
        fun pinVisible() = nativeKeyguard.pinVisible()
        fun gateAlive(): Boolean {
            var alive = false
            main { alive = listOf(androidx.test.runner.lifecycle.Stage.RESUMED, androidx.test.runner.lifecycle.Stage.STARTED,
                androidx.test.runner.lifecycle.Stage.CREATED, androidx.test.runner.lifecycle.Stage.PAUSED, androidx.test.runner.lifecycle.Stage.STOPPED)
                .any { stage -> androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage)
                    .any { it is WidgetUnlockActivity } } }
            return alive
        }
        WidgetHostTestFixture(context).use { fixture ->
            val id = fixture.bind(fixture.providers.singleProvider(instrumentation.context.packageName))
            try {
                nativeKeyguard.setPin()
                val component = "${context.packageName}/${LockscreenWidgetService::class.java.name}"
                shell("settings put secure enabled_accessibility_services ${if (enabled == "null" || enabled.isBlank()) component else "$enabled:$component"}")
                shell("settings put secure accessibility_enabled 1")
                ui.await("Action Service did not connect", 15000) { instance.get(null) != null }
                awaitActionServiceSettingsPersisted(component, ::shell)
                service = instance.get(null) as LockscreenWidgetService
                lateinit var baseline: android.accessibilityservice.AccessibilityServiceInfo
                main {
                    baseline = AndroidEventDelivery(service!!).currentInfo()
                    original = launchField.get(service) as WidgetActivityLaunch
                    inspection = AndroidLockscreenInspection(service!!)
                    val eligible = { keyguard.isKeyguardLocked && power.isInteractive && !inspection!!.covered() }
                    // Native SystemUI can disappear from the snapshot after our focused page is
                    // removed. Deterministically invalidate that ownership at the same boundary:
                    // the already-validated event must complete synchronously, not re-query it later.
                    launch = WidgetActivityLaunch(service!!, { !pageRemoved && eligible() }, { page?.hide(); pageRemoved = true })
                    // The fixture changes only the device eligibility input. Events still enter the
                    // real connected service and its production observer, dispatch and dismiss owners.
                    launchField.set(service, launch)
                    page = EdgePageWindow(service!!, android.os.Handler(android.os.Looper.getMainLooper()), eligible, {}, "Test side launch") { back, capture, close ->
                        SideScreenView(service!!, SideScreen(slots = listOf(id, null)), back, capture, close,
                            { sends++; launch!!.dispatched() }, { launch!!.pageVisible(it) })
                    }
                }
                fun open() {
                    main { pageRemoved = false }
                    nativeKeyguard.sleep()
                    ui.await("Fixture screen did not sleep") { !power.isInteractive }
                    nativeKeyguard.wake()
                    ui.await("Fixture keyguard not locked/awake") { power.isInteractive && keyguard.isKeyguardLocked }
                    ui.await("Fixture keyguard not observed bare") { var bare = false; main { bare = !inspection!!.covered() }; bare }
                    main { page!!.settle(ScreenEdge.LEFT) }
                    ui.await("Hosted collection not ready for touch") { ui.has("Hosted item 0") }
                    main {
                        val info = service!!.serviceInfo
                        assertNull(info.packageNames); assertEquals(0L, info.notificationTimeout)
                        assertEquals(baseline.eventTypes, info.eventTypes); assertEquals(baseline.flags, info.flags)
                        assertEquals(baseline.feedbackType, info.feedbackType); assertEquals(baseline.capabilities, info.capabilities)
                    }
                }
                fun routed() {
                    ui.await("Widget activity did not request native PIN") { pinVisible() }
                    main {
                        assertEquals("Dismiss requested before its real window gained focus", 0, prematureRequests.get())
                        assertEquals("Every gate must acquire its own focused window before requesting PIN", sends - 1, focusedGates.get())
                        assertFalse(page!!.opened)
                        val info = service!!.serviceInfo
                        assertArrayEquals(baseline.packageNames, info.packageNames)
                        assertEquals(baseline.notificationTimeout, info.notificationTimeout)
                        assertEquals(baseline.flags, info.flags)
                    }
                }
                open()
                ui.click("Broadcast 0")
                ui.await("In-place broadcast did not reach native widget") { ui.has("Broadcast 1") }
                main { assertEquals(1, sends); assertTrue(page!!.opened) }
                assertTrue(keyguard.isKeyguardLocked); assertFalse(pinVisible()); assertFalse(gateAlive())
                ui.click("Hosted item 0")
                routed(); nativeKeyguard.unlock()
                ui.await("Original collection fill-in did not reach visible target") { ui.has("Payload: row-0") && ui.has("Deliveries: 1") }
                ui.await("Successful gate retained its Activity") { !gateAlive() }
                main { assertEquals(2, sends) }
                ui.click(ui.find { it.text?.toString()?.equals("Finish fixture", true) == true }!!.text.toString())
                shell("input keyevent KEYCODE_HOME")
                val home = context.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)!!.activityInfo.packageName
                open()
                ui.click("Hosted item 0"); routed(); ui.back()
                ui.await("Back cancellation did not finish the gate") { !gateAlive() && !pinVisible() }
                assertTrue(keyguard.isKeyguardLocked)
                main { assertEquals(3, sends) }
                open()
                ui.await("Cancelled launch was resent or not delivered") { ui.has("Launches 2") }
                ui.click("Hosted item 0"); routed()
                nativeKeyguard.sleep()
                ui.await("Screen-off cancellation did not finish gate") { !power.isInteractive && !gateAlive() }
                main { assertFalse(page!!.opened); assertEquals(4, sends) }
                nativeKeyguard.unlock()
                ui.await("Cancellation did not return HOME underneath keyguard") { ui.packageVisible(home) }
            } catch (error: Throwable) { failure = error }
            finally {
                restore { main { lifecycle.removeLifecycleCallback(focusProbe) } }
                restore { main { page?.close(); launch?.close(); inspection?.close(); if (service != null) launchField.set(service, original) } }
                restore { nativeKeyguard.close(); assertFalse(keyguard.isDeviceSecure) }
                restore {
                    // Remove the test counterpart's retained task after cancellation, from unlocked UI.
                    context.startActivity(Intent().setComponent(android.content.ComponentName(instrumentation.context.packageName, SideTestTarget::class.java.name))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("widget", id).putExtra("payload", "cleanup"))
                    ui.await("Counterpart cleanup UI absent") { ui.find { it.text?.toString()?.equals("Finish fixture", true) == true } != null }
                    ui.click(ui.find { it.text?.toString()?.equals("Finish fixture", true) == true }!!.text.toString())
                }
                for ((key, value) in listOf("enabled_accessibility_services" to enabled, "accessibility_enabled" to accessibility)) restore {
                    shell(if (value == "null") "settings delete secure $key" else "settings put secure $key $value")
                    assertEquals(value, shell("settings get secure $key"))
                }
                restore { ui.await("Action Service did not disconnect") { instance.get(null) == null }; awaitActionServiceSettingsPersisted(enabled, ::shell) }
            }
        }
        failure?.let { throw it }
    }
}
