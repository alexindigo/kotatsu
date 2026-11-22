package com.gezimos.katapult

import android.content.Intent
import android.graphics.Rect
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.model.AppModel
import com.gezimos.katapult.setPaperContent as setContent
import com.gezimos.katapult.ui.settings.*
import org.junit.Assert.*
import org.junit.Test

class ControlColumnsInstrumentedTest {
    @get:org.junit.Rule val nativeState = ConnectedUiStateRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val ui = PlatformUiDriver(instrumentation)
    private fun main(work: () -> Unit) = instrumentation.runOnMainSync(work)
    private fun bounds(label: String) = Rect().also { checkNotNull(ui.find(label)).getBoundsInScreen(it) }

    @Test fun hiddenAndNotifyHeadersRemainSingleLineAndOwnTheirCheckboxWidths() = activity { activity ->
        val app = AppModel("column.fixture", "Calendar", "")
        for (scale in listOf(1f, 1.5f, 2f)) {
            main { activity.setContent {
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density, scale)) {
                    Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                        AppChecklistPage("Hidden apps", listOf(app), setOf(app.key), {}, {}, notifyCell = { NotifyCell.ON })
                    }
                }
            } }
            val hidden = context.getString(R.string.hidden_apps_hidden_column)
            val notify = context.getString(R.string.hidden_apps_notify_column)
            ui.await("Matrix headers missing") { ui.has(hidden) && ui.has(notify) && ui.has(app.label) }
            capture(activity, "hidden-columns-${(scale * 100).toInt()}")
            for ((header, cell) in listOf(hidden to context.getString(R.string.paper_hidden_app, app.label),
                notify to context.getString(R.string.paper_notify_app, app.label))) {
                val heading = bounds(header)
                val target = bounds(cell)
                val box = indexDrawingBounds("paper_index_checkbox").single {
                    it.center.y in target.top.toFloat()..target.bottom.toFloat() &&
                        it.center.x in heading.left.toFloat()..heading.right.toFloat()
                }
                val density = context.resources.displayMetrics.density
                val layout = textLayout(header)
                assertEquals("$header must stay on one line at $scale", 1, layout.lineCount)
                assertFalse("$header must not clip at $scale", layout.didOverflowWidth)
                assertFalse("$header must not ellipsize at $scale", layout.isLineEllipsized(0))
                assertTrue("$header keeps at least a 64dp column", heading.width() >= 64f * density - 1)
                assertEquals("$header column and drawing share a centre", heading.exactCenterX(), box.center.x, 1f)
                assertTrue("$header target owns its entire column", target.width() >= heading.width() - 1)
                if (scale == 1f) assertEquals("Default matrix width is unchanged", 64f * density, heading.width().toFloat(), 1f)
            }
        }
    }

    @Test fun launcherLongValuesAt150UseTheExistingSharedRows() = activity { activity ->
        main { activity.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 1.5f)) {
                Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                    PaperIndexRenderer(homeCategory("hh:mm a", "EEEE, MMMM d", true, false, false, false,
                        false, false, false, {}, {}, {}, {}, {}, {}, {}, {}), {})
                }
            }
        } }
        ui.await("Launcher date row missing") { ui.has(context.getString(R.string.date_format)) }
        capture(activity, "launcher-long-values-150")
    }

    private fun activity(body: (MainActivity) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        withFixtureCleanup({ main { activity.finish() } }) { body(activity) }
    }

    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    private fun textLayout(label: String): androidx.compose.ui.text.TextLayoutResult {
        val result = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        fun semantics(node: androidx.compose.ui.semantics.SemanticsNode) {
            if (node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.any { it.text == label } == true)
                node.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult)?.action?.invoke(result)
            node.children.forEach(::semantics)
        }
        fun views(view: android.view.View) {
            if (view is androidx.compose.ui.platform.ViewRootForTest) semantics(view.semanticsOwner.unmergedRootSemanticsNode)
            else if (view is android.view.ViewGroup) for (i in 0 until view.childCount) views(view.getChildAt(i))
        }
        main { android.view.inspector.WindowInspector.getGlobalWindowViews().forEach(::views) }
        return result.single()
    }

    private fun capture(activity: MainActivity, name: String) {
        instrumentation.waitForIdleSync()
        val committed = java.util.concurrent.CountDownLatch(1)
        main {
            activity.window.decorView.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
            activity.window.decorView.invalidate()
        }
        assertTrue(committed.await(10, java.util.concurrent.TimeUnit.SECONDS))
        val directory = java.io.File(context.getExternalFilesDir(null), "column-evidence").apply { mkdirs() }
        var previous = instrumentation.platformAutomation.takeScreenshot()
        var stable: android.graphics.Bitmap? = null
        ui.await("Screenshot must settle") {
            val next = instrumentation.platformAutomation.takeScreenshot()
            if (previous.sameAs(next)) { previous.recycle(); stable = next; true }
            else { previous.recycle(); previous = next; false }
        }
        val bitmap = checkNotNull(stable)
        java.io.File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}
