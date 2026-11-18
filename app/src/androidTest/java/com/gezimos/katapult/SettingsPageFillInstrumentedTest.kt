package com.gezimos.katapult

import android.content.Intent
import android.graphics.Rect
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.lockscreen.EdgeAction
import com.gezimos.katapult.ui.settings.*
import com.gezimos.katapult.util.PrefsManager
import com.gezimos.katapult.setPaperContent as setContent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Measured chapter audit and the original consumer-level pagination regression. */
@RunWith(AndroidJUnit4::class)
class SettingsPageFillInstrumentedTest {
    @get:org.junit.Rule val nativeState = ConnectedUiStateRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val ui = PlatformUiDriver(instrumentation)
    private fun text(id: Int) = context.getString(id)

    private fun withActivity(block: (MainActivity) -> Unit) {
        val raw = context.getSharedPreferences("katapult_prefs", 0)
        val before = raw.all
        instrumentation.runOnMainSync {
            assertTrue(raw.edit().putInt("theme_mode", PrefsManager.THEME_LIGHT)
                .putBoolean("notice_action_service_dismissed", false).commit())
        }
        val activity = instrumentation.startActivitySync(Intent(Intent.ACTION_MAIN).apply {
            setClassName(context.packageName, MainActivity::class.java.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }) as MainActivity
        withFixtureCleanup({ restoreFixturePreferences(raw, before) },
            { instrumentation.runOnMainSync { activity.finish() } }) { block(activity) }
    }

    @Composable
    private fun definitions(): List<SettingsCategory> = listOf(
        appearanceCategory(true, true, 80, PrefsManager.THEME_LIGHT, false, false, false, 0, false,
            {}, {}, {}, {}, {}, {}, {}, {}),
        homeCategory("system", "system", true, false, true, false, false, true, false,
            {}, {}, {}, {}, {}, {}, {}, {}),
        appsCategory(true, PrefsManager.APP_SORT_MANUAL, false, false, false, false,
            {}, {}, {}, {}, {}, {}, {}),
        gesturesCategory(com.gezimos.katapult.util.HomeAppSwipe.DEFAULT, PrefsManager.DOUBLE_TAP_OFF, true, true, true,
            {}, {}, {}, {}, {}),
        notificationsCategory(true, true, {}, {}, {}, {}),
        lockCategory(true, true, false, true, false, false, true, 1, {}, {}, {}, {}, {}, {}, {}),
        screensaverCategory(true, true, true, true, false, false, false, false, false, PrefsManager.SCREENSAVER_MODE_AUTO,
            {}, {}, {}, {}, {}, {}, {}, {}, {}),
        systemCategory({}, {}, {}, {}, {}, {}, {}),
        aboutCategory("0.1", {}, {}),
        edgeSwipesCategory(true, true, EdgeAction.SIDE_SCREEN, EdgeAction.CAMERA, true, {}, {}, { _, _ -> }),
    )

    private fun reminder() = NoticeContent(text(R.string.paper_service_off), text(R.string.paper_notice_region),
        text(R.string.paper_notice_dismiss), {}, text(R.string.paper_turn_on), {})

    private fun bounds(label: String, target: Boolean = false): Rect = Rect().also {
        requireNotNull(if (target) ui.findAncestor(label) { it.isClickable } else ui.find(label)) {
            "Missing $label"
        }.getBoundsInScreen(it)
    }

    /** Reproducible before/after evidence of every chapter, with real kit measurements. */
    @Test fun captureEveryCategoryAndMeasuredPageMap() = withActivity { activity ->
        val output = File(context.getExternalFilesDir(null), "settings-page-fill").apply { mkdirs() }
        val report = StringBuilder()
        for (withNotice in listOf(true, false)) for (id in listOf("appearance", "home", "apps", "gestures",
            "notifications", "lock", "screensaver", "system", "about", "edges")) {
            val definition = AtomicReference<SettingsCategory>()
            val sizes = AtomicReference<List<Int>>()
            val footerSize = AtomicReference<Int>()
            val capacity = AtomicReference<Int>()
            val key = Any()
            instrumentation.runOnMainSync { activity.setContent {
                key(key) {
                    val category = definitions().single { it.id == id }
                    val notice = if (withNotice && id in listOf("appearance", "gestures", "lock", "screensaver", "edges")) reminder() else null
                    SideEffect { definition.set(category) }
                    Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                        // This unplaced measurement subtree uses precisely the chapter's row, help,
                        // heading, gap and footer grammar, without introducing production test hooks.
                        SubcomposeLayout(Modifier.fillMaxSize()) { constraints ->
                            val loose = Constraints(maxWidth = constraints.maxWidth)
                            sizes.set(category.groups.map { group ->
                                subcompose("measure-${group.id}") {
                                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                                        GroupHeading(group.title)
                                        group.visibleEntries.forEach { Entry(it, {}) }
                                    }
                                }.single().measure(loose).height + 9.dp.roundToPx()
                            })
                            footerSize.set(subcompose("measure-footer") {
                                Column { if (notice != null) Notice(notice); Pager(0, 2, notice != null) {} }
                            }.single().measure(loose).height)
                            val header = subcompose("measure-header") {
                                ChapterHeader(category.title, category.help, {}, {})
                            }.single().measure(loose)
                            capacity.set(constraints.maxHeight - header.height - footerSize.get() + 9.dp.roundToPx())
                            val page = subcompose("page") {
                                PaperIndexRenderer(category, {}, notice = notice,
                                    noticeGroup = when (id) { "appearance" -> "screen"; "screensaver" -> "interaction"; else -> null })
                            }.single().measure(constraints)
                            layout(constraints.maxWidth, constraints.maxHeight) { page.placeRelative(0, 0) }
                        }
                    }
                }
            } }
            ui.await("$id chapter not measured") { definition.get() != null && sizes.get() != null && ui.has(definition.get().title) }
            instrumentation.waitForIdleSync()
            val category = definition.get()
            val entries = category.groups.flatMap { it.visibleEntries }
            val mode = if (withNotice) "notice" else "dismissed"
            report.appendLine("$id/$mode heights=${sizes.get()} footer=${footerSize.get()} capacity=${capacity.get()}")
            val seen = mutableListOf<String>()
            var page = 1
            do {
                instrumentation.waitForIdleSync()
                val visible = entries.filter { ui.has(it.help.ifEmpty { it.title }) }
                seen += visible.map { it.id }
                report.appendLine("  page=$page entries=${visible.map { it.id }}")
                capture(output, "$id-$mode-page-$page.png")
                val next = ui.has(text(R.string.paper_next_page))
                if (next) {
                    ui.click(text(R.string.paper_next_page))
                    val number = ++page
                    ui.await("$id pager did not advance") {
                        ui.find { it.contentDescription?.toString()?.startsWith("Page $number of ") == true } != null
                    }
                }
            } while (next)
            assertEquals("$id lost or reordered entries", entries.map { it.id }, seen)
            File(output, "page-map.txt").writeText(report.toString())
        }
        File(output, "page-map.txt").writeText(report.toString())
    }

    @Test fun edgeActionsFillTheFirstPageWithoutOverlappingTheNotice() = withActivity { activity ->
        // The non-Mudita emulator does not expose the production navigation entry.
        // Render the real Edge swipes definition with its On state and shared Notice.
        instrumentation.runOnMainSync { activity.setContent {
            Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                PaperIndexRenderer(edgeSwipesCategory(true, true, EdgeAction.SIDE_SCREEN, EdgeAction.CAMERA,
                    true, {}, {}, { _, _ -> }), {}, notice = reminder())
            }
        } }
        // The page title has the same text as the master; locate its unique help instead.
        ui.await("Edge master did not publish On") { ui.checked(text(R.string.edge_swipes_help)) == true }
        assertTrue("Must start on page 1", ui.find { it.contentDescription?.toString()?.startsWith("Page 1 of ") == true } != null)
        ui.await("Edge actions fit above the Notice but were deferred to page 2") {
            ui.has(text(R.string.edge_actions)) && ui.has(text(R.string.edge_left)) && ui.has(text(R.string.edge_right))
        }
        val home = bounds(text(R.string.edge_home_enabled), target = true)
        val heading = bounds(text(R.string.edge_actions))
        val left = bounds(text(R.string.edge_left), target = true)
        val right = bounds(text(R.string.edge_right), target = true)
        val notice = Rect().also { rect ->
            requireNotNull(ui.find { it.paneTitle?.toString() == text(R.string.paper_notice_region) })
                .getBoundsInScreen(rect)
        }
        assertTrue("Actions must follow the master group", heading.top >= home.bottom)
        assertTrue("Heading orphaned from first row", heading.bottom <= left.top)
        assertTrue("Action rows overlap", left.bottom <= right.top)
        assertTrue("Actions overlap the Notice", right.bottom <= notice.top)
        File(context.getExternalFilesDir(null), "settings-page-fill/edge-geometry.txt").apply {
            parentFile!!.mkdirs()
            writeText("home=$home\nheading=$heading\nleft=$left\nright=$right\nnotice=$notice\n")
        }
    }

    private fun capture(output: File, name: String) {
        val bitmap = instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot()
        File(output, name).outputStream().use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}
