package com.gezimos.katapult

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.model.AppModel
import com.gezimos.katapult.setPaperContent as setContent
import com.gezimos.katapult.ui.settings.*
import org.junit.Assert.*
import org.junit.Test

/** Observe the actual measured drawing nodes, not the larger accessibility hit regions. */
@OptIn(androidx.compose.ui.InternalComposeUiApi::class)
internal fun indexDrawingBounds(tag: String): List<androidx.compose.ui.geometry.Rect> {
    val result = mutableListOf<androidx.compose.ui.geometry.Rect>()
    fun semantics(node: SemanticsNode) {
        if (node.config.getOrNull(SemanticsProperties.TestTag) == tag) result += node.boundsInWindow
        node.children.forEach(::semantics)
    }
    fun views(view: View) {
        if (view is ViewRootForTest) semantics(view.semanticsOwner.unmergedRootSemanticsNode)
        else if (view is ViewGroup) for (i in 0 until view.childCount) views(view.getChildAt(i))
    }
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        android.view.inspector.WindowInspector.getGlobalWindowViews().forEach(::views)
    }
    return result
}

class TrailingCheckboxGeometryInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val ui = PlatformUiDriver(instrumentation)
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun fixture(content: @Composable () -> Unit, test: () -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            main { activity.setContent {
                Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp)) { content() }
            } }
            test()
        } finally { main { activity.finish() } }
    }

    private fun geometry(label: String) {
        ui.await("Checkbox target missing: $label") { ui.findAncestor(label) { it.isCheckable } != null }
        val target = android.graphics.Rect().also {
            ui.findAncestor(label) { it.isCheckable }!!.getBoundsInScreen(it)
        }
        val box = indexDrawingBounds("paper_index_checkbox").filter {
            it.center.y >= target.top && it.center.y <= target.bottom && it.center.x >= target.left && it.center.x <= target.right
        }.maxByOrNull { it.right } ?: error("Checkbox drawing missing: $label")
        val leader = indexDrawingBounds("paper_index_leader").filter {
            it.center.y >= target.top && it.center.y <= target.bottom && it.right <= box.left
        }.maxByOrNull { it.right } ?: error("Leader drawing missing: $label")
        val density = context.resources.displayMetrics.density
        assertEquals("Leader-to-checkbox gap for $label", 7.5f * density, box.left - leader.right, 1f)
        assertTrue("Checkbox width target for $label", target.width() >= 48f * density - 1f)
        assertTrue("Checkbox height target for $label", target.height() >= 48f * density - 1f)
        android.util.Log.i("CheckboxGeometry", "$label gap=${box.left - leader.right} target=${target.width()}x${target.height()}")
    }

    @Test fun shortcutDrawingGapAndIndependentNameLeaderAndBoxTargets() {
        var checked by mutableStateOf(false)
        var opened = 0
        fixture(content = { PaperShortcutEntry("Camera", "App icon", checked, { opened++ }, { checked = it }) }) {
            geometry("Camera: App icon")
            val leader = indexDrawingBounds("paper_index_leader").single()
            tap(leader.center.x, leader.center.y)
            ui.await("Leader did not toggle its checkbox") { checked }
            assertEquals(0, opened)
            val box = indexDrawingBounds("paper_index_checkbox").single()
            tap(box.center.x, box.center.y)
            ui.await("Box did not toggle its checkbox") { !checked }
            ui.click("Camera")
            ui.await("Name did not open its own action") { opened == 1 }
            assertFalse(checked)
        }
    }

    @Test fun choiceSheetRowsKeepTheirGapAndFullRowTargets() {
        val entry = SettingsEntry.Value("choice", "Theme mode", "", "Light",
            listOf(SettingsChoice("system", "System"), SettingsChoice("light", "Light"), SettingsChoice("dark", "Dark")), {}, "light")
        fixture(content = { ChoiceSurface(entry, {}) }) {
            listOf("System", "Light", "Dark").forEach(::geometry)
        }
    }

    @Test fun hiddenAppsNotifyUsesTheSameDrawingGapAndIndependentTarget() {
        val app = AppModel("geometry.fixture", "Hidden fixture", "")
        var notify by mutableStateOf(NotifyCell.OFF)
        var hideClicks = 0
        fixture(content = {
            AppChecklistPage("Hidden apps", listOf(app), setOf(app.key), {}, { hideClicks++ },
                notifyCell = { notify }, onToggleNotify = { notify = NotifyCell.ON })
        }) {
            val label = context.getString(R.string.paper_notify_app, app.label)
            geometry(label)
            val leader = indexDrawingBounds("paper_index_leader").single()
            tap(leader.center.x, leader.center.y)
            ui.await("Notify leader did not toggle Notify") { notify == NotifyCell.ON }
            assertEquals(0, hideClicks)
            ui.click(app.label)
            ui.await("Name did not retain Hide") { hideClicks == 1 }
        }
    }

    private fun tap(x: Float, y: Float) {
        val down = android.os.SystemClock.uptimeMillis()
        for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            val event = android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(instrumentation.platformAutomation.injectInputEvent(event, true))
            event.recycle()
        }
    }
}
