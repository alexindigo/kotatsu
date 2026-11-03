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
        assertEquals(EdgeAction.TASK_LIST, store.action(ScreenEdge.LEFT))
        assertEquals(EdgeAction.CAMERA, store.action(ScreenEdge.RIGHT))
        store.enabled = true
        store.setAction(ScreenEdge.LEFT, EdgeAction.CAMERA)
        store.setAction(ScreenEdge.RIGHT, EdgeAction.NOTHING)
        val backup = prefs.exportToJson(context)
        assertEquals("camera", JSONObject(backup).getString(EdgeActionStore.LEFT))
        store.setAction(ScreenEdge.LEFT, EdgeAction.NOTHING)
        store.enabled = false
        assertTrue(prefs.importFromJson(backup))
        assertTrue(store.enabled)
        assertEquals(EdgeAction.CAMERA, store.action(ScreenEdge.LEFT))
        assertEquals(EdgeAction.NOTHING, store.action(ScreenEdge.RIGHT))
        listOf(EdgeActionStore.LEFT to "bogus", EdgeActionStore.RIGHT to 1, EdgeActionStore.ENABLED to "true").forEach { (key, value) ->
            val bad = JSONObject(backup).put(key, value).put(EdgeActionStore.ENABLED, false)
            if (key == EdgeActionStore.ENABLED) bad.put(key, value)
            assertFalse(prefs.importFromJson(bad.toString()))
            assertTrue(store.enabled)
            assertEquals(EdgeAction.CAMERA, store.action(ScreenEdge.LEFT))
        }
        val old = JSONObject(backup).apply { remove(EdgeActionStore.LEFT); remove(EdgeActionStore.RIGHT); remove(EdgeActionStore.ENABLED) }
        assertTrue(prefs.importFromJson(old.toString()))
        assertFalse(store.enabled)
        assertEquals(EdgeAction.TASK_LIST, store.action(ScreenEdge.LEFT))
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
                    val category = edgeSwipesCategory(store.enabled, store.action(ScreenEdge.LEFT), store.action(ScreenEdge.RIGHT), false,
                        { store.enabled = it; revision++ }, { edge, action -> store.setAction(edge, action); revision++ })
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
            ui.click(context.getString(R.string.edge_left))
            ui.click(context.getString(R.string.edge_camera))
            ui.await("Left action did not persist") { store.action(ScreenEdge.LEFT) == EdgeAction.CAMERA }
            ui.click(context.getString(R.string.edge_left))
            ui.click(context.getString(R.string.edge_task_list))
            ui.await("Task list action did not persist") { store.action(ScreenEdge.LEFT) == EdgeAction.TASK_LIST }
            ui.click(context.getString(R.string.edge_right))
            ui.click(context.getString(R.string.edge_nothing))
            ui.await("Right action did not persist") { store.action(ScreenEdge.RIGHT) == EdgeAction.NOTHING }
            repeat(3) {
                if (!ui.has(context.getString(R.string.edge_camera_unavailable)) && ui.has(context.getString(R.string.paper_next_page))) {
                    ui.click(context.getString(R.string.paper_next_page))
                }
            }
            assertTrue(ui.has(context.getString(R.string.edge_camera_unavailable)))
            assertTrue(ui.has(context.getString(R.string.edge_camera_setup)))
            val intent = SecureCamera.intent()
            assertEquals(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE, intent.action)
            assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun taskSelectionAndActionRoundTripWithoutLosingLongIdsOrExplicitNothing() = withStore { prefs, store ->
        store.taskListId = Long.MAX_VALUE
        store.setAction(ScreenEdge.LEFT, EdgeAction.NOTHING)
        store.setAction(ScreenEdge.RIGHT, EdgeAction.TASK_LIST)
        val backup = prefs.exportToJson(context)
        store.taskListId = null
        assertTrue(prefs.importFromJson(backup))
        assertEquals(Long.MAX_VALUE, store.taskListId)
        assertEquals(EdgeAction.NOTHING, store.action(ScreenEdge.LEFT))
        assertEquals(EdgeAction.TASK_LIST, store.action(ScreenEdge.RIGHT))
        listOf(-1, "01", "1.5", "9223372036854775808", true).forEach { invalid ->
            assertFalse(prefs.importFromJson(JSONObject(backup).put(EdgeActionStore.TASK_LIST, invalid).toString()))
            assertEquals(Long.MAX_VALUE, store.taskListId)
            assertEquals(EdgeAction.NOTHING, store.action(ScreenEdge.LEFT))
        }
    }
}
