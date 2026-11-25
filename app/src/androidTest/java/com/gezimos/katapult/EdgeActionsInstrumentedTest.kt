package com.gezimos.katapult

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import com.gezimos.katapult.setPaperContent as setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.Modifier
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.lockscreen.EdgeAction
import com.gezimos.katapult.lockscreen.EdgeActionStore
import com.gezimos.katapult.lockscreen.ScreenEdge
import com.gezimos.katapult.lockscreen.SecureCamera
import com.gezimos.katapult.ui.settings.ChoicePage
import com.gezimos.katapult.ui.settings.PaperIndexRenderer
import com.gezimos.katapult.ui.settings.SettingsEntry
import com.gezimos.katapult.ui.settings.edgeSwipesCategory
import com.gezimos.katapult.util.PrefsManager
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class EdgeActionsInstrumentedTest {
    @get:org.junit.Rule val nativeState = ConnectedUiStateRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun serviceKeepsNarrowStaticDeliveryAndCanReplayGestures() {
        val manager = context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
        val info = manager.installedAccessibilityServiceList.single {
            it.resolveInfo.serviceInfo.packageName == context.packageName &&
                it.resolveInfo.serviceInfo.name == "com.gezimos.katapult.lockscreen.LockscreenWidgetService"
        }
        assertTrue(info.capabilities and android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES != 0)
        assertTrue(info.flags and android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS != 0)
        assertTrue(info.flags and android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS != 0)
        assertTrue(info.eventTypes and android.view.accessibility.AccessibilityEvent.TYPE_WINDOWS_CHANGED != 0)
        assertArrayEquals(arrayOf("com.android.systemui"), info.packageNames)
        assertEquals("Only a replay handoff may request uncoalesced delivery", 100L, info.notificationTimeout)
    }

    private fun withStore(test: (PrefsManager, EdgeActionStore) -> Unit) {
        val name = "edges-${UUID.randomUUID()}"
        val raw = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String?, mode: Int) = raw
        }
        withFixtureCleanup({ deleteTestPreferences(context, name) }) { val prefs = PrefsManager(isolated); test(prefs, prefs.edgeActions) }
    }

    @Test fun storeSurvivesRecreationExportImportAndRejectsInvalidImportsAtomically() = withStore { prefs, store ->
        assertFalse(store.enabled)
        assertTrue(store.homeEnabled)
        assertEquals(EdgeAction.SIDE_SCREEN, store.action(ScreenEdge.LEFT))
        assertEquals(EdgeAction.CAMERA, store.action(ScreenEdge.RIGHT))
        store.enabled = true
        store.homeEnabled = false
        store.setAction(ScreenEdge.LEFT, EdgeAction.CAMERA)
        store.setAction(ScreenEdge.RIGHT, EdgeAction.NOTHING)
        val backup = prefs.exportToJson(context)
        assertEquals("camera", JSONObject(backup).getString(EdgeActionStore.LEFT))
        assertFalse(JSONObject(backup).getBoolean(EdgeActionStore.HOME_ENABLED))
        store.setAction(ScreenEdge.LEFT, EdgeAction.NOTHING)
        store.enabled = false
        store.homeEnabled = true
        assertTrue(prefs.importFromJson(backup))
        assertTrue(store.enabled)
        assertFalse(store.homeEnabled)
        assertEquals(EdgeAction.CAMERA, store.action(ScreenEdge.LEFT))
        assertEquals(EdgeAction.NOTHING, store.action(ScreenEdge.RIGHT))
        listOf(EdgeActionStore.LEFT to "bogus", EdgeActionStore.RIGHT to 1, EdgeActionStore.ENABLED to "true",
            EdgeActionStore.HOME_ENABLED to "false", EdgeActionStore.HOME_ENABLED to 0).forEach { (key, value) ->
            val bad = JSONObject(backup).put(key, value).put(EdgeActionStore.ENABLED, false)
            if (key == EdgeActionStore.ENABLED) bad.put(key, value)
            assertFalse(prefs.importFromJson(bad.toString()))
            assertTrue(store.enabled)
            assertFalse(store.homeEnabled)
            assertEquals(EdgeAction.CAMERA, store.action(ScreenEdge.LEFT))
        }
        val old = JSONObject(backup).apply {
            remove(EdgeActionStore.LEFT); remove(EdgeActionStore.RIGHT); remove(EdgeActionStore.ENABLED); remove(EdgeActionStore.HOME_ENABLED)
        }
        assertTrue(prefs.importFromJson(old.toString()))
        assertFalse(store.enabled)
        assertTrue(store.homeEnabled)
        assertEquals(EdgeAction.SIDE_SCREEN, store.action(ScreenEdge.LEFT))
        assertEquals(EdgeAction.CAMERA, store.action(ScreenEdge.RIGHT))
    }

    @Test fun settingsChoicesPersistBothEdgesAndShowMissingSecureCamera() = withStore { _, store ->
        store.enabled = true
        val activity = instrumentation.startActivitySync(Intent(Intent.ACTION_MAIN).apply {
            setClassName(context.packageName, MainActivity::class.java.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }) as MainActivity
        withFixtureCleanup(body = {
            instrumentation.runOnMainSync {
                activity.setContent {
                    var revision by remember { mutableIntStateOf(0) }
                    var choice by remember { mutableStateOf<String?>(null) }
                    @Suppress("UNUSED_VARIABLE") val observed = revision
                    val category = edgeSwipesCategory(store.enabled, store.homeEnabled, store.action(ScreenEdge.LEFT), store.action(ScreenEdge.RIGHT), false,
                        { store.enabled = it; revision++ }, { store.homeEnabled = it; revision++ }, { edge, action -> store.setAction(edge, action); revision++ })
                    val current = choice
                    if (current == null) PaperIndexRenderer(category, {}, Modifier.statusBarsPadding().navigationBarsPadding(),
                        onChoice = { choice = it.id }, onHelp = {})
                    else {
                        val row = category.groups.flatMap { it.entries }.filterIsInstance<SettingsEntry.Value>().single { it.id == current }
                        ChoicePage(row.title, row.choices, row.selected, row.onSelect, { choice = null }, row.help)
                    }
                }
            }
            val ui = PlatformUiDriver(instrumentation)
            ui.await("Edge settings did not render") { ui.has(context.getString(R.string.edge_home_enabled)) }
            fun reveal(label: String) {
                // Whole groups move between measured pages; a choice return can recreate page one.
                instrumentation.waitForIdleSync()
                repeat(12) {
                    if (ui.has(label)) return
                    assertTrue("No settings page contains $label", ui.has(context.getString(R.string.paper_next_page)))
                    ui.click(context.getString(R.string.paper_next_page))
                    instrumentation.waitForIdleSync()
                }
                fail("Settings pages did not reveal $label")
            }
            reveal(context.getString(R.string.edge_left))
            ui.click(context.getString(R.string.edge_left))
            listOf(R.string.side_screen, R.string.edge_camera, R.string.edge_nothing).forEach {
                assertTrue(ui.has(context.getString(it)))
            }
            ui.click(context.getString(R.string.edge_camera))
            ui.await("Left action did not persist") { store.action(ScreenEdge.LEFT) == EdgeAction.CAMERA }
            reveal(context.getString(R.string.edge_left))
            ui.click(context.getString(R.string.edge_left))
            ui.click(context.getString(R.string.side_screen))
            ui.await("Side screen action did not persist") { store.action(ScreenEdge.LEFT) == EdgeAction.SIDE_SCREEN }
            reveal(context.getString(R.string.edge_right))
            ui.click(context.getString(R.string.edge_right))
            ui.click(context.getString(R.string.edge_nothing))
            ui.await("Right action did not persist") { store.action(ScreenEdge.RIGHT) == EdgeAction.NOTHING }
            reveal(context.getString(R.string.edge_camera_unavailable))
            assertTrue(ui.has(context.getString(R.string.edge_camera_unavailable)))
            assertTrue(ui.has(context.getString(R.string.edge_camera_setup)))
            val intent = SecureCamera.intent()
            assertEquals(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE, intent.action)
            assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        }, actions = arrayOf<() -> Unit>({ instrumentation.runOnMainSync { activity.finish() } }))
    }

    @Test fun masterHidesDependentRowsAndReallocatesPagesWithTheNotice() = withStore { _, store ->
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val ui = PlatformUiDriver(instrumentation)
        val master = context.getString(R.string.edge_swipes_help)
        val dependent = listOf(R.string.edge_home_enabled, R.string.edge_left, R.string.edge_right).map(context::getString)
        val next = context.getString(R.string.paper_next_page)
        val previous = context.getString(R.string.paper_previous_page)
        val notice = context.getString(R.string.paper_service_off)
        fun capture(name: String) {
            if (InstrumentationRegistry.getArguments().getString("settingsStateEvidence") != "true") return
            instrumentation.waitForIdleSync()
            val automation = instrumentation.platformAutomation
            var bitmap = automation.takeScreenshot()
            var stable = false
            for (attempt in 0 until 20) {
                android.os.SystemClock.sleep(100)
                val current = automation.takeScreenshot()
                stable = current.sameAs(bitmap)
                bitmap.recycle()
                bitmap = current
                if (stable) break
            }
            assertTrue("Edge settings screenshot did not settle", stable)
            java.io.File(context.getExternalFilesDir(null), "$name.png").outputStream().use {
                assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
            bitmap.recycle()
        }
        fun firstPage() {
            repeat(12) {
                if (!ui.has(previous)) return
                ui.click(previous)
                instrumentation.waitForIdleSync()
            }
            fail("Edge settings never returned to page one")
        }
        fun checkPages(enabled: Boolean): Int {
            firstPage()
            val seen = mutableListOf<String>()
            repeat(12) { page ->
                instrumentation.waitForIdleSync()
                val region = ui.find { it.paneTitle?.toString() == context.getString(R.string.paper_notice_region) }
                val footer = android.graphics.Rect().also { checkNotNull(region).getBoundsInScreen(it) }
                assertTrue("Notice lost its message", ui.has(notice))
                dependent.forEach { label ->
                    if (!enabled) assertFalse("Off left a dependent row: $label", ui.has(label))
                    else if (ui.has(label)) {
                        seen += label
                        val row = ui.findAncestor(label) { it.isClickable }!!
                        val bounds = android.graphics.Rect().also(row::getBoundsInScreen)
                        assertTrue("Available row was disabled: $label", row.isEnabled)
                        assertTrue("Notice overlaps $label", bounds.bottom <= footer.top)
                    }
                }
                if (!enabled) assertFalse("Off left an empty Actions heading", ui.has(context.getString(R.string.edge_actions)))
                if (ui.has(next) || ui.has(previous)) {
                    val pager = android.graphics.Rect().also { checkNotNull(ui.find(if (ui.has(next)) next else previous)).getBoundsInScreen(it) }
                    assertTrue("Pager overlaps the Notice", footer.bottom <= pager.top)
                }
                capture("edge-swipes-${if (enabled) "on" else "off"}-${page + 1}")
                if (!ui.has(next)) {
                    if (enabled) assertEquals("Pagination lost or duplicated dependent rows", dependent, seen)
                    return page + 1
                }
                ui.click(next)
            }
            error("Edge settings pagination never ended")
        }
        withFixtureCleanup({ instrumentation.runOnMainSync { activity.finish() } }) {
            instrumentation.runOnMainSync { activity.setContent {
                var revision by remember { mutableIntStateOf(0) }
                @Suppress("UNUSED_VARIABLE") val observed = revision
                PaperIndexRenderer(edgeSwipesCategory(store.enabled, store.homeEnabled,
                    store.action(ScreenEdge.LEFT), store.action(ScreenEdge.RIGHT), false,
                    { store.enabled = it; revision++ }, { store.homeEnabled = it; revision++ },
                    { edge, action -> store.setAction(edge, action); revision++ }), {},
                    Modifier.statusBarsPadding().navigationBarsPadding(),
                    notice = com.gezimos.katapult.ui.settings.NoticeContent(notice,
                        context.getString(R.string.paper_notice_region), context.getString(R.string.paper_notice_dismiss), {},
                        context.getString(R.string.paper_turn_on), {}))
            } }
            ui.await("Master Off missing") { ui.checked(master) == false }
            val offPages = checkPages(false)
            firstPage()
            ui.toggleSwitch(master, false, true) { store.enabled }
            val onPages = checkPages(true)
            assertTrue("Showing dependent rows unexpectedly reduced page count", onPages >= offPages)
            firstPage()
            ui.toggleSwitch(master, true, false) { !store.enabled }
            assertEquals(offPages, checkPages(false))
            assertTrue("Hiding controls changed the saved Home choice", store.homeEnabled)
            assertEquals(EdgeAction.SIDE_SCREEN, store.action(ScreenEdge.LEFT))
            assertEquals(EdgeAction.CAMERA, store.action(ScreenEdge.RIGHT))
        }
    }

    @Test fun sideScreenAndExplicitNothingRoundTripOnEitherEdge() = withStore { prefs, store ->
        store.setAction(ScreenEdge.LEFT, EdgeAction.NOTHING)
        store.setAction(ScreenEdge.RIGHT, EdgeAction.SIDE_SCREEN)
        val backup = prefs.exportToJson(context)
        store.setAction(ScreenEdge.LEFT, EdgeAction.CAMERA)
        store.setAction(ScreenEdge.RIGHT, EdgeAction.NOTHING)
        assertTrue(prefs.importFromJson(backup))
        assertEquals(EdgeAction.NOTHING, store.action(ScreenEdge.LEFT))
        assertEquals(EdgeAction.SIDE_SCREEN, store.action(ScreenEdge.RIGHT))
    }

    @Test fun unknownStoredActionsUseEachEdgesDefaultWithoutRewritingPreferences() {
        val name = "edge-defaults-${UUID.randomUUID()}"
        val raw = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        withFixtureCleanup(body = {
            val store = EdgeActionStore(raw)
            listOf("task_list", "unknown", 42, true).forEach { value ->
                val edit = raw.edit()
                listOf(EdgeActionStore.LEFT, EdgeActionStore.RIGHT).forEach { key ->
                    when (value) {
                        is String -> edit.putString(key, value)
                        is Int -> edit.putInt(key, value)
                        is Boolean -> edit.putBoolean(key, value)
                    }
                }
                assertTrue(edit.commit())
                assertEquals(EdgeAction.SIDE_SCREEN, store.action(ScreenEdge.LEFT))
                assertEquals(EdgeAction.CAMERA, store.action(ScreenEdge.RIGHT))
                assertEquals(value, raw.all[EdgeActionStore.LEFT])
                assertEquals(value, raw.all[EdgeActionStore.RIGHT])
            }
        }, actions = arrayOf<() -> Unit>({ deleteTestPreferences(context, name) }))
    }

    @Test fun launcherDoesNotRequestMuditaNotesPermissions() {
        val requested = context.packageManager.getPackageInfo(context.packageName,
            android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertFalse(requested.contains("com.mudita.data.provider.permission.READ_NOTES"))
        assertFalse(requested.contains("com.mudita.data.provider.permission.WRITE_NOTES"))
    }
}
