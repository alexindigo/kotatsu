package com.gezimos.katapult

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.compose.setContent
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
        try { val prefs = PrefsManager(isolated); test(prefs, prefs.edgeActions) }
        finally { deleteTestPreferences(context, name) }
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
        val activity = instrumentation.startActivitySync(Intent(Intent.ACTION_MAIN).apply {
            setClassName(context.packageName, MainActivity::class.java.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }) as MainActivity
        try {
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
                        ChoicePage(row.title, row.choices, row.selected, row.onSelect, { choice = null })
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
        } finally { instrumentation.runOnMainSync { activity.finish() } }
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
        try {
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
        } finally { deleteTestPreferences(context, name) }
    }

    @Test fun launcherDoesNotRequestMuditaNotesPermissions() {
        val requested = context.packageManager.getPackageInfo(context.packageName,
            android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertFalse(requested.contains("com.mudita.data.provider.permission.READ_NOTES"))
        assertFalse(requested.contains("com.mudita.data.provider.permission.WRITE_NOTES"))
    }
}
