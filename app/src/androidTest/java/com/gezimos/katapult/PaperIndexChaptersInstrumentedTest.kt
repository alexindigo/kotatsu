package com.gezimos.katapult

import android.content.Context
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.ui.SettingsScreen
import com.gezimos.katapult.ui.settings.*
import com.gezimos.katapult.util.PrefsManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real renderer, full Mudita chapter definitions, and the phone's logical viewport. */
@RunWith(AndroidJUnit4::class)
class PaperIndexChaptersInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val expected = linkedMapOf(
        "appearance" to listOf(listOf("shape", "size", "names"), listOf("theme", "status", "clock", "mode", "refresh")),
        "home" to listOf(listOf("clock_format", "date_format", "alarm", "battery"), listOf("weather", "celsius", "dock", "music")),
        "apps" to listOf(listOf("sort", "icon", "hidden", "system"), listOf("button", "infinite", "arrows")),
        "gestures" to listOf(listOf("swipe", "direction", "double_tap", "long_press")),
        "notifications" to listOf(listOf("indicators", "apps", "log", "history")),
        "lock" to listOf(listOf("notifications", "apps", "sort"), listOf("music", "weather", "widgets", "message")),
        "screensaver" to listOf(listOf("enabled", "system", "customization", "wallpaper"), listOf("brightness", "power", "icon", "mode")),
        "system" to listOf(listOf("sensitive", "permissions", "default", "search"), listOf("config", "app_license", "font_license")),
        "about" to listOf(listOf("version", "source", "upstream")),
    )

    private fun withActivity(test: (MainActivity, PlatformUiDriver) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(Intent.ACTION_MAIN).apply {
            setClassName(context.packageName, MainActivity::class.java.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }) as MainActivity
        try { test(activity, PlatformUiDriver(instrumentation)) }
        finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Composable
    private fun definitions(record: (String) -> Unit, enabled: Boolean = true, background: Boolean = false, image: Boolean = false): List<SettingsCategory> = listOf(
        appearanceCategory(true, true, 80, PrefsManager.THEME_LIGHT, false, false, false, 0, false,
            {}, {}, {}, {}, {}, {}, {}, {}),
        homeCategory("system", "system", true, false, true, false, false, true, false,
            { record(it) }, { record(it) }, {}, {}, {}, {}, {}, {}),
        appsCategory(true, PrefsManager.APP_SORT_MANUAL, false, false, false, false,
            { record(it.toString()) }, {}, {}, {}, {}, {}, {}),
        gesturesCategory(true, true, PrefsManager.DOUBLE_TAP_OFF, PrefsManager.HOME_LONG_PRESS_EDIT,
            {}, { record(it.toString()) }, { record(it.toString()) }, { record(it.toString()) }),
        notificationsCategory(true, true, {}, {}, {}, {}),
        lockCategory(true, true, false, true, false, false, true, 1, {}, {}, { record(it.toString()) }, {}, {}, {}, {}),
        screensaverCategory(enabled, true, true, true, background, image, false, false, false, PrefsManager.SCREENSAVER_MODE_AUTO,
            {}, {}, {}, {}, {}, {}, {}, {}, { record(it.toString()) }),
        systemCategory({}, {}, {}, {}, {}, {}, {}),
        aboutCategory("0.1", {}, {}),
    )

    private fun show(activity: MainActivity, id: String, notice: Boolean = true,
        enabled: Boolean = true, background: Boolean = false, image: Boolean = false,
        record: (String) -> Unit = {}): SettingsCategory {
        val definition = java.util.concurrent.atomic.AtomicReference<SettingsCategory>()
        val composed = java.util.concurrent.CountDownLatch(1)
        // A unique composition owner disposes page/modal state even when two setContent
        // calls coalesce before the next frame (in particular, the two image variants).
        val renderKey = Any()
        instrumentation.runOnMainSync { activity.setContent {
            androidx.compose.runtime.key(renderKey) {
                val category = definitions(record, enabled, background, image).first { it.id == id }
                androidx.compose.runtime.SideEffect { definition.set(category); composed.countDown() }
                val reminder = if (notice && (id == "appearance" || id == "lock" || id == "screensaver" && enabled)) NoticeContent(
                    context.getString(R.string.paper_service_off), context.getString(R.string.paper_notice_region),
                    context.getString(R.string.paper_notice_dismiss), {}, context.getString(R.string.paper_turn_on), {}) else null
                PaperIndexRenderer(category, {}, Modifier.requiredSize(360.dp, 564.dp), reminder,
                    when (id) { "appearance" -> "screen"; "screensaver" -> "interaction"; else -> null })
            }
        } }
        assertTrue("$id chapter did not compose", composed.await(8, java.util.concurrent.TimeUnit.SECONDS))
        return requireNotNull(definition.get())
    }

    @Test fun everyChapterMatchesThePageMapAndEntryOrderWithOrWithoutNotice() = withActivity { activity, ui ->
        for (notice in listOf(true, false)) for ((id, pages) in expected) {
            val definition = show(activity, id, notice)
            val entries = definition.groups.flatMap { it.visibleEntries }.associateBy { it.id }
            assertEquals("$id definition order", pages.flatten(), definition.groups.flatMap { it.visibleEntries }.map { it.id })
            pages.forEachIndexed { page, ids ->
                if (pages.size > 1) ui.await("$id page count/order $page") { ui.has(context.getString(R.string.paper_page, page + 1, pages.size)) }
                val labels = ids.map { entries.getValue(it).title }
                ui.await("$id missing entries on page $page") { labels.all { ui.has(it) } }
                assertEquals("$id visual entry order", labels, ui.gridLabels(labels))
                val otherLabels = (pages.flatten() - ids.toSet()).map { entries.getValue(it).let { entry -> entry.help.ifEmpty { entry.title } } }
                assertTrue("$id leaked entries from another page", otherLabels.none { ui.has(it) })
                assertEquals("$id next control", page < pages.lastIndex, ui.has(context.getString(R.string.paper_next_page)))
                assertEquals("$id previous control", page > 0, ui.has(context.getString(R.string.paper_previous_page)))
                if (page < pages.lastIndex) ui.click(context.getString(R.string.paper_next_page))
            }
        }
    }

    @Test fun everyValueEntryOpensAChoiceSheetWithoutCycling() = withActivity { activity, ui ->
        var opened = 0
        fun hasRadioRole(node: android.view.accessibility.AccessibilityNodeInfo): Boolean {
            if (!node.refresh()) return false
            if (node.className == "android.widget.RadioButton") return true
            return (0 until node.childCount).any { index -> node.getChild(index)?.let { hasRadioRole(it) } == true }
        }
        for ((id, pages) in expected) {
            var calls = 0
            val definition = show(activity, id, record = { calls++ })
            val entries = definition.groups.flatMap { it.visibleEntries }.associateBy { it.id }
            pages.forEachIndexed { page, ids ->
                if (pages.size > 1) ui.await("$id page $page did not open") { ui.has(context.getString(R.string.paper_page, page + 1, pages.size)) }
                for (entry in ids.map { entries.getValue(it) }.filterIsInstance<SettingsEntry.Value>()) {
                    val before = calls
                    ui.click(entry.title)
                    ui.await("$id/${entry.id} did not open a choice sheet") {
                        // Compose exports the role as a synthetic child, with checkable state
                        // on its owning row. Require both, as the real platform tree exposes them.
                        entry.choices.all { option ->
                            val owner = ui.findAncestor(option.label) { it.isCheckable }
                            owner != null && owner.isChecked == (option.id == entry.selected) && hasRadioRole(owner)
                        }
                    }
                    assertEquals("$id/${entry.id} changed immediately", before, calls)
                    assertFalse("$id chapter leaked behind its choice page", ui.has(definition.title))
                    ui.back()
                    ui.await("Choice Back did not dismiss $id/${entry.id}") { ui.find { it.className == "android.widget.RadioButton" } == null }
                    assertEquals("Choice Back applied $id/${entry.id}", before, calls)
                    ui.await("Choice Back did not reveal $id/${entry.id}") { ui.has(entry.title) }
                    opened++
                }
                if (page < pages.lastIndex) ui.click(context.getString(R.string.paper_next_page))
            }
        }
        assertEquals("All twelve Value entries must be exercised", 12, opened)
    }

    @Test fun everyHelpPageReplacesItsChapterAndReturnsToItsPageNumber() = withActivity { activity, ui ->
        var opened = 0
        for ((id, pages) in expected) {
            val definition = show(activity, id)
            val help = definition.help ?: continue
            if (pages.size > 1) {
                ui.await("Chapter pager not ready") { ui.has(context.getString(R.string.paper_page, 1, pages.size)) }
                ui.click(context.getString(R.string.paper_next_page))
            }
            ui.click(help.label)
            ui.await("Help page missing for $id") { ui.has(help.paragraphs.first()) }
            assertFalse("Chapter pager leaked into help", ui.has(context.getString(R.string.paper_page, if (pages.size > 1) 2 else 1, pages.size)))
            ui.click(context.getString(R.string.paper_back))
            val labels = pages[if (pages.size > 1) 1 else 0].map { entryId ->
                definition.groups.flatMap { it.visibleEntries }.single { it.id == entryId }.title
            }
            ui.await("Help Back lost $id chapter state") { labels.all { ui.has(it) } }
            if (pages.size > 1) assertTrue(ui.has(context.getString(R.string.paper_page, 2, pages.size)))
            opened++
        }
        assertEquals("All five chapter help pages must be exercised", 5, opened)
    }

    @Test fun lockNoticeIsOneSharedRegionAndNeverPerRow() = withActivity { activity, ui ->
        show(activity, "lock")
        for (page in 1..2) {
            ui.await("Lock Notice missing on page $page") { ui.has(context.getString(R.string.paper_service_off)) }
            val notice = ui.find(context.getString(R.string.paper_service_off))!!
            val noticeBounds = android.graphics.Rect().also { notice.getBoundsInScreen(it) }
            val pagerBounds = android.graphics.Rect().also { ui.find(context.getString(R.string.paper_page, page, 2))!!.getBoundsInScreen(it) }
            assertTrue("Notice must precede the bottom pager", noticeBounds.bottom <= pagerBounds.top)
            assertFalse("Retired per-row warning is still present", ui.has(context.getString(R.string.action_service_off)))
            if (page == 1) ui.click(context.getString(R.string.paper_next_page))
        }
    }

    @Test fun screensaverOffAndBothImageVariantsRetainTheirConditionalPages() = withActivity { activity, ui ->
        val off = show(activity, "screensaver", enabled = false)
        assertEquals(listOf("enabled"), off.groups.flatMap { it.visibleEntries }.map { it.id })
        ui.await("OFF Screensaver entry missing") { ui.has(context.getString(R.string.screensaver_enable_desc)) }
        assertFalse(ui.has(context.getString(R.string.paper_next_page)))
        for (image in listOf(false, true)) {
            show(activity, "screensaver", background = true, image = image)
            ui.await("Background variant image=$image must retain two pages") { ui.has(context.getString(R.string.paper_page, 1, 2)) }
            assertTrue(ui.has(context.getString(if (image) R.string.clear_image else R.string.screensaver_image)))
            assertTrue(ui.has(context.getString(if (image) R.string.screensaver_wallpaper_custom else R.string.screensaver_wallpaper_home)))
            assertFalse("Page 1 must not reserve or render the Interaction Notice", ui.has(context.getString(R.string.paper_service_off)))
            val summaryBounds = android.graphics.Rect().also {
                ui.find(context.getString(if (image) R.string.screensaver_wallpaper_custom else R.string.screensaver_wallpaper_home))!!.getBoundsInScreen(it)
            }
            val pagerBounds = android.graphics.Rect().also { ui.find(context.getString(R.string.paper_page, 1, 2))!!.getBoundsInScreen(it) }
            assertTrue("Image summary overlaps its page footer", summaryBounds.bottom <= pagerBounds.top)
            ui.click(context.getString(R.string.paper_next_page))
            ui.await("Image variant lost Refresh") { ui.has(context.getString(R.string.screensaver_update_mode)) }
            assertTrue("Interaction page lost the shared Notice", ui.has(context.getString(R.string.paper_service_off)))
        }
    }

    @Test fun positiveMusicWidgetPersistsTheInverseWithoutChangingExport() = withActivity { activity, ui ->
        val raw = context.getSharedPreferences("katapult_prefs", Context.MODE_PRIVATE)
        val previous = raw.all["disable_music_widget"]
        val prefs = PrefsManager(context)
        try {
            instrumentation.runOnMainSync {
                prefs.disableMusicWidget = false
                val vm = ViewModelProvider(activity)[MainViewModel::class.java]
                vm.resolveLockscreenReEnable()
                activity.setContent { SettingsScreen(vm, activity.configExporter, activity.configImporter, activity.screensaverWallpaperPicker) }
            }
            ui.click(context.getString(R.string.section_home))
            val title = context.getString(R.string.paper_music_widget)
            ui.await("Home chapter not ready") { ui.has(title) || ui.has(context.getString(R.string.paper_next_page)) }
            if (!ui.has(title)) ui.click(context.getString(R.string.paper_next_page))
            assertFalse(ui.has("Text backgrounds"))
            ui.await("Positive music state must be On for stored disable=false") { ui.checked(title) == true }
            ui.click(title)
            ui.await("Music Off must persist disable=true") { prefs.disableMusicWidget && ui.checked(title) == false }
            assertTrue(org.json.JSONObject(prefs.exportToJson(context)).getBoolean("disable_music_widget"))
            ui.click(title)
            ui.await("Music On must persist disable=false") { !prefs.disableMusicWidget && ui.checked(title) == true }
        } finally {
            instrumentation.runOnMainSync {
                val editor = raw.edit()
                if (previous is Boolean) editor.putBoolean("disable_music_widget", previous) else editor.remove("disable_music_widget")
                assertTrue(editor.commit())
            }
        }
    }

    @Test fun screensaverCustomizationNoLongerOffersTextBackgrounds() = withActivity { activity, ui ->
        val raw = context.getSharedPreferences("katapult_prefs", Context.MODE_PRIVATE)
        val previous = raw.all["screensaver_enabled"]
        try {
            instrumentation.runOnMainSync {
                assertTrue(raw.edit().putBoolean("screensaver_enabled", true).commit())
                val vm = ViewModelProvider(activity)[MainViewModel::class.java]
                vm.resolveLockscreenReEnable()
                activity.setContent { SettingsScreen(vm, activity.configExporter, activity.configImporter, activity.screensaverWallpaperPicker) }
            }
            ui.click(context.getString(R.string.section_screensaver))
            ui.click(context.getString(R.string.screensaver_customization))
            ui.await("Screensaver customization did not open") { ui.has(context.getString(R.string.screensaver_show_clock)) }
            assertTrue(ui.has(context.getString(R.string.screensaver_show_notifications)))
            assertTrue(ui.has(context.getString(R.string.screensaver_eink_refresh)))
            assertFalse(ui.has("Text backgrounds"))
            assertFalse(ui.has("Screensaver Text Backgrounds"))
        } finally {
            instrumentation.runOnMainSync {
                val editor = raw.edit()
                if (previous is Boolean) editor.putBoolean("screensaver_enabled", previous) else editor.remove("screensaver_enabled")
                assertTrue(editor.commit())
                assertEquals(previous, raw.all["screensaver_enabled"])
            }
        }
    }
}
