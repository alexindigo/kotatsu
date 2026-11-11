package com.gezimos.katapult

import android.content.Intent
import android.graphics.Bitmap
import com.gezimos.katapult.setPaperContent as setContent
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.lockscreen.SideLayout
import com.gezimos.katapult.lockscreen.SideScreen
import com.gezimos.katapult.ui.settings.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class PaperEntryGeometryInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val ui = PlatformUiDriver(instrumentation)
    private val density get() = context.resources.displayMetrics.density
    private fun main(work: () -> Unit) = instrumentation.runOnMainSync(work)
    private fun withActivity(work: (MainActivity) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try { work(activity) } finally { main { activity.finish() } }
    }

    @Test fun everyHelpLessEntryRetains48dpTargetAndNormalPaddingWhileHelpKeepsItsHeight() = withActivity { activity ->
        val heights = mutableMapOf<String, Int>()
        val rows = listOf(
            SettingsEntry.Open("open", "Open row", "", {}),
            SettingsEntry.Value("value", "Choice row", "", "Daily", emptyList(), {}, "daily"),
            SettingsEntry.Switch("switch", "Switch row", "", true, {}),
            SettingsEntry.Link("link", "Link row", "", {}),
            SettingsEntry.Metadata("metadata", "Metadata row", "Version"),
            SettingsEntry.Open("help", "Help row", "One help line", {}),
        )
        main { activity.setContent {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                rows.forEach { entry ->
                    Box(Modifier.onGloballyPositioned { heights[entry.id] = it.size.height }) { Entry(entry) }
                }
                Box(Modifier.onGloballyPositioned { heights["permission"] = it.size.height }) {
                    Entry(SettingsEntry.Open("permission", "Permission row", "One help line", {}), minimumHeight = 63.dp)
                }
            }
        } }
        ui.await("All measured entry variants must be laid out") {
            var ready = false
            main { ready = heights.size == 7 }
            ready
        }
        main {
            rows.filter { it.help.isEmpty() }.forEach {
                assertEquals("${it.id}: compact row target includes normal entry padding", 48f * density, heights.getValue(it.id).toFloat(), 1f)
            }
            assertEquals("Help row keeps the existing chapter minimum", 67.5f * density, heights.getValue("help").toFloat(), 1f)
            assertEquals("Permission override is unchanged", 63f * density, heights.getValue("permission").toFloat(), 1f)
        }
        val label = android.graphics.Rect().also { ui.find("Open row")!!.getBoundsInScreen(it) }
        val target = android.graphics.Rect().also { ui.findAncestor("Open row") { it.isClickable }!!.getBoundsInScreen(it) }
        assertTrue("Normal top spacing is retained", label.top - target.top >= 9 * density - 1)
        assertTrue("Normal bottom spacing is retained", target.bottom - label.bottom >= 9 * density - 1)
        assertEquals("Entry content remains vertically centered", (label.top - target.top).toFloat(), (target.bottom - label.bottom).toFloat(), 1f)
    }

    @Test fun sideScreenUsesCompactNameAndSlotRowsWhileLayoutHelpStaysUnchanged() = withActivity { activity ->
        main { activity.setContent {
            Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                SideScreenSettingsPage(SideScreen(layout = SideLayout.SPLIT), "Side screen", { "None" }, {}, {}, {}, {}, {})
            }
        } }
        ui.await("Side screen entries must render") { ui.has(context.getString(R.string.side_bottom)) }
        capture(activity, "side-screen")
        for (label in listOf(R.string.side_name, R.string.side_top, R.string.side_bottom)) {
            val bounds = android.graphics.Rect().also {
                ui.findAncestor(context.getString(label)) { it.isClickable }!!.getBoundsInScreen(it)
            }
            assertEquals("Side screen row uses the shared compact target", 48f * density, bounds.height().toFloat(), 1f)
        }
        val layout = android.graphics.Rect().also {
            ui.findAncestor(context.getString(R.string.side_layout)) { it.isClickable }!!.getBoundsInScreen(it)
        }
        assertTrue("Layout help retains the chapter minimum", layout.height() >= 67.5f * density - 1)
    }

    private fun capture(activity: MainActivity, name: String) {
        instrumentation.waitForIdleSync()
        instrumentation.platformAutomation.waitForIdle(100, 5000)
        val committed = CountDownLatch(1)
        main {
            activity.window.decorView.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
            activity.window.decorView.invalidate()
        }
        assertTrue(committed.await(8, TimeUnit.SECONDS))
        val bitmap = instrumentation.platformAutomation.takeScreenshot()
        val directory = File(context.getExternalFilesDir(null), "tight-row-renders").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}
