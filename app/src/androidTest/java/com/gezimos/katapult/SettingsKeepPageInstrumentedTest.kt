package com.gezimos.katapult

import android.content.Intent
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.setPaperContent as setContent
import com.gezimos.katapult.ui.settings.*
import com.gezimos.katapult.util.PrefsManager
import org.junit.Assert.*
import org.junit.Test

/** Exercise the shared chapter owner, with a choice deliberately on its later page. */
class SettingsKeepPageInstrumentedTest {
    @get:org.junit.Rule val accessibility = ConnectedUiStateRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val ui = PlatformUiDriver(instrumentation)
    private fun label(id: Int) = context.getString(id)

    private fun withActivity(test: (MainActivity) -> Unit) = ApplicationPreferencesFixture(context).use { preferences ->
        preferences.reset {
            putBoolean("onboarding_complete", true)
            putInt("theme_mode", PrefsManager.THEME_LIGHT)
            putBoolean("notice_action_service_dismissed", true)
        }
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try { test(activity) } finally {
            instrumentation.runOnMainSync { activity.finish() }
            ui.await("Position fixture did not finish") { activity.isDestroyed }
        }
    }

    private fun fixture(count: Int, test: () -> Unit) = withActivity { activity ->
        instrumentation.runOnMainSync { activity.setContent {
            var selected by remember { mutableStateOf("0") }
            val entry = SettingsEntry.Value("choice", "Later choice", "", "Option $selected",
                List(count) { SettingsChoice(it.toString(), "Option $it") }, { selected = it }, selected)
            PaperIndexRenderer(SettingsCategory("fixture", "Parent chapter", listOf(
                SettingsGroup("first", "First group", List(6) {
                    SettingsEntry.Metadata("first-$it", "First $it", "Value")
                }),
                SettingsGroup("later", "Later group", listOf(entry)),
            ), help = SettingsHelp("Parent help", "Help child", listOf("Help text"))), {},
                Modifier.requiredSize(320.dp, 480.dp))
        } }
        ui.await("Parent missing") { ui.has("First group") }
        ui.click(label(R.string.paper_next_page))
        ui.await("Later page missing") { ui.has("Later choice") }
        assertTrue(ui.has(context.getString(R.string.paper_page, 2, 2)))
        test()
    }

    private fun assertLaterPage() {
        ui.await("Returning child reset the parent's page") {
            ui.has("Later choice") && ui.has(context.getString(R.string.paper_page, 2, 2))
        }
        assertFalse(ui.has("First group"))
    }

    @Test fun shortChoiceApplyKeepsLaterPage() = fixture(3) {
        ui.click("Later choice")
        ui.click("Option 1")
        assertLaterPage()
        assertTrue(ui.has("Later choice, Option 1"))
    }

    @Test fun fullPageChoiceApplyKeepsLaterPage() = fixture(5) {
        ui.click("Later choice")
        ui.click("Option 1")
        assertLaterPage()
        assertTrue(ui.has("Later choice, Option 1"))
    }

    @Test fun helpAndChoiceBackKeepLaterPage() = fixture(5) {
        ui.click("Later choice")
        ui.await("Choice missing") { ui.checked("Option 0") != null }
        ui.back()
        assertLaterPage()
        ui.click("Parent help")
        ui.await("Help missing") { ui.has("Help text") }
        ui.click(label(R.string.paper_back))
        assertLaterPage()
    }

    private fun scrollToChoice() {
        repeat(8) {
            if (ui.find("Overflow choice")?.isVisibleToUser == true) return
            val viewport = android.graphics.Rect()
            ui.find { it.className == "android.widget.ScrollView" && it.isScrollable }!!.getBoundsInScreen(viewport)
            instrumentation.platformAutomation.boundedShell("input swipe ${viewport.centerX()} ${viewport.bottom - 20} ${viewport.centerX()} ${viewport.top + 20} 250")
            instrumentation.waitForIdleSync()
        }
        fail("Overflow choice was not revealed")
    }

    private fun choiceBounds(): android.graphics.Rect {
        var previous = android.graphics.Rect()
        var stable = 0
        ui.await("Overflow position did not settle") {
            val current = android.graphics.Rect()
            val node = ui.find("Overflow choice")
            node?.getBoundsInScreen(current)
            stable = if (node?.isVisibleToUser == true && current == previous) stable + 1 else 0
            previous = current
            stable >= 3
        }
        return previous
    }

    private fun assertOverflowPosition(before: android.graphics.Rect, page: Int = 2) {
        ui.await("Parent overflow position reset") { ui.find("Overflow choice")?.isVisibleToUser == true }
        assertTrue("Parent page index reset", ui.has(context.getString(R.string.paper_page, page, 2)))
        assertEquals("Parent scroll offset changed", before, choiceBounds())
    }

    private fun overflowChapter(count: Int, test: () -> Unit) = withActivity { activity ->
        instrumentation.runOnMainSync { activity.setContent {
            val entry = SettingsEntry.Value("choice", "Overflow choice", "", "Option 0",
                List(count) { SettingsChoice(it.toString(), "Option $it") }, {}, "0")
            PaperIndexRenderer(SettingsCategory("overflow", "Parent chapter", listOf(
                SettingsGroup("first", "First group", List(6) {
                    SettingsEntry.Metadata("first-$it", "First $it", "Value")
                }),
                SettingsGroup("later", "Later group", listOf(
                    SettingsEntry.Metadata("tall", "Tall entry", "Value", List(35) { "Help $it" }.joinToString("\n")), entry)),
            ), help = SettingsHelp("Parent help", "Help child", listOf("Help text"))), {},
                Modifier.requiredSize(320.dp, 480.dp))
        } }
        ui.click(label(R.string.paper_next_page))
        ui.await("Later overflow page absent") { ui.has("Later group") }
        scrollToChoice()
        test()
    }

    @Test fun fullPageChoiceReturnKeepsOverflowPosition() = overflowChapter(5) {
        val before = choiceBounds()
        ui.click("Overflow choice")
        ui.click("Option 1")
        assertOverflowPosition(before)
    }

    @Test fun shortChoiceReturnKeepsOverflowPosition() = overflowChapter(3) {
        val before = choiceBounds()
        ui.click("Overflow choice")
        ui.click("Option 1")
        assertOverflowPosition(before)
    }

    @Test fun choiceAndHelpBackKeepOverflowPositionButChangingPageResetsIt() = overflowChapter(5) {
        val before = choiceBounds()
        ui.click("Overflow choice")
        ui.await("Choice missing") { ui.checked("Option 0") != null }
        ui.back()
        assertOverflowPosition(before)
        ui.click("Parent help")
        ui.await("Help missing") { ui.has("Help text") }
        ui.click(label(R.string.paper_back))
        assertOverflowPosition(before)
        ui.click(label(R.string.paper_previous_page))
        ui.await("Previous page absent") { ui.has("First group") }
        ui.click(label(R.string.paper_next_page))
        ui.await("New page must start at its top") { ui.has("Later group") }
        assertFalse("Changing pages retained the previous scroll offset", ui.find("Overflow choice")?.isVisibleToUser == true)
    }

    @Test fun helpBackKeepsOverflowPosition() = overflowChapter(5) {
        val before = choiceBounds()
        ui.click("Parent help")
        ui.await("Help missing") { ui.has("Help text") }
        ui.back()
        assertOverflowPosition(before)
    }

    @Test fun secondarySubpageBackKeepsSavedPageAndScroll() = secondarySubpageBack(later = true)

    @Test fun secondaryFirstPageBackKeepsSavedScroll() = secondarySubpageBack(later = false)

    @Test fun secondaryListSubpageBackKeepsSavedPageAndScroll() = secondarySubpageBack(later = true, list = true)

    private fun secondarySubpageBack(later: Boolean, list: Boolean = false) = withActivity { activity ->
        instrumentation.runOnMainSync { activity.setContent {
            val navigation = rememberSettingsNavigation()
            val pages = rememberSaveableStateHolder()
            val back = { pages.removeState(navigation.current); navigation.pop() }
            SettingsBackOwner(back) {
                pages.SaveableStateProvider(navigation.current) {
                    if (navigation.current == "child") SettingsPage("Editor child", back) { SheetText("Child text") }
                    else androidx.compose.foundation.layout.Box(Modifier.requiredSize(320.dp, 480.dp)) {
                        val overflow: @Composable () -> Unit = {
                            androidx.compose.foundation.layout.Column {
                                SheetText(List(35) { "Help $it" }.joinToString("\n"))
                                SheetAction("Overflow choice") { navigation.push("child") }
                            }
                        }
                        if (list) PagedSettingsPage("Management parent", (0..6).toList(), {}, header = {}) { index ->
                            if (index == 6) overflow() else SheetAction("First $index") {}
                        } else SettingsPage("Management parent", {}) {
                            if (later) repeat(6) { SheetAction("First $it") {} }
                            overflow()
                            if (!later) repeat(6) { SheetAction("Last $it") {} }
                        }
                    }
                }
            }
        } }
        ui.await("Secondary parent absent") { ui.has("Management parent") }
        if (later) ui.click(label(R.string.paper_next_page))
        val page = if (later) 2 else 1
        ui.await("Secondary page absent") { ui.has(context.getString(R.string.paper_page, page, 2)) }
        scrollToChoice()
        val before = choiceBounds()
        ui.click("Overflow choice")
        ui.await("Subpage absent") { ui.has("Child text") }
        ui.back()
        assertOverflowPosition(before, page)
    }

    @Test fun productionThemeApplyAndHelpBackKeepLaterPage() = productionThemeApplyAndHelpBack(false)

    @Test fun kompaktThemeApplyAndHelpBackKeepLaterPage() = productionThemeApplyAndHelpBack(true)

    private fun productionThemeApplyAndHelpBack(kompakt: Boolean) {
        val deviceModel = android.os.Build::class.java.getDeclaredField("MODEL").apply { isAccessible = true }
        val previousModel = deviceModel.get(null)
        if (kompakt) deviceModel.set(null, "Kompakt")
        try {
            withActivity { activity ->
                instrumentation.runOnMainSync { activity.viewModel.navigateTo(Screen.SETTINGS) }
                ui.click(label(R.string.section_appearance))
                ui.click(label(R.string.paper_next_page))
                ui.await("Later appearance page absent") { ui.has(label(R.string.theme_mode)) }
                val page = ui.find { it.contentDescription?.toString()?.startsWith("Page ") == true }!!.contentDescription.toString()
                ui.click(label(R.string.theme_mode))
                ui.click(label(R.string.theme_dark))
                ui.await("Production theme selection lost later page") {
                    activity.viewModel.prefs.themeMode == PrefsManager.THEME_DARK &&
                        ui.has(label(R.string.theme_mode)) && ui.has(page)
                }
                ui.click(label(R.string.paper_about_eink))
                ui.await("Help absent") { ui.has(label(R.string.eink_readme_intro)) }
                ui.back()
                ui.await("Production Help Back lost later page") { ui.has(label(R.string.theme_mode)) && ui.has(page) }
                ui.click(label(R.string.wallpaper))
                ui.click(label(R.string.wallpaper_photos))
                ui.await("Photo picker handoff absent") { ui.windowVisible() && !ui.packageVisible(context.packageName) }
                ui.back()
                ui.await("External handoff Back lost later page") { ui.has(label(R.string.theme_mode)) && ui.has(page) }
            }
        } finally { deviceModel.set(null, previousModel) }
    }
}
