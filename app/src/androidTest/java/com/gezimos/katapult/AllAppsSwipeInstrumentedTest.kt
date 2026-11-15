package com.gezimos.katapult

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.util.HomeAppSwipe
import com.gezimos.katapult.util.PrefsManager
import org.junit.Assert.*
import org.junit.Test

/** Shell input traverses the real window/pointer owner, not a synthetic direction callback. */
class AllAppsSwipeInstrumentedTest {
    @get:org.junit.Rule val accessibility = ActionServiceOffRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val ui = PlatformUiDriver(instrumentation)
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun withApps(vertical: Boolean, test: (MainViewModel) -> Unit) {
        ApplicationPreferencesFixture(context).use { preferences ->
            preferences.reset {
                putBoolean("onboarding_complete", true)
                putInt("theme_mode", PrefsManager.THEME_LIGHT)
                putInt("icon_size", 80)
                putBoolean("infinite_scroll", false)
                putBoolean("hide_arrow_buttons", vertical)
                putString("swipe_all_apps", HomeAppSwipe.UP_AND_LEFT.id)
            }
            val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            try {
                main { activity.viewModel.navigateTo(Screen.ALL_APPS) }
                val model = activity.viewModel
                ui.await("Two All Apps pages must render") {
                    model.totalPages >= 2 && model.orderedApps.isNotEmpty() && ui.has(model.orderedApps.first().label)
                }
                test(model)
            } finally {
                main { activity.finish() }
                ui.await("Swipe fixture Activity did not release its model") { activity.isDestroyed }
            }
        }
    }

    private fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, duration: Int = 250) {
        instrumentation.platformAutomation.boundedShell("input swipe $x1 $y1 $x2 $y2 $duration")
        instrumentation.waitForIdleSync()
    }

    private fun assertPage(model: MainViewModel, page: Int) {
        ui.await("Expected All Apps page $page, got ${model.screen}/${model.currentPage}") {
            model.screen == Screen.ALL_APPS && model.currentPage == page &&
                ui.has(model.orderedApps[page * model.appsPerPage].label)
        }
    }

    @Test fun crossAxisDriftDoesNotReturnHome() {
        for (vertical in listOf(true, false)) withApps(vertical) { model ->
            for (duration in listOf(100, 250, 600, 1200)) {
                if (vertical) swipe(380, 330, 100, 370, duration)
                else swipe(200, 600, 270, 220, duration)
                assertPage(model, 0)
            }
        }
    }

    @Test fun selectedAxisKeepsForwardBackwardBoundaryAndWrapBehaviour() {
        for (vertical in listOf(true, false)) withApps(vertical) { model ->
            fun forward() { if (vertical) swipe(240, 600, 240, 220) else swipe(380, 360, 100, 360) }
            fun backward() { if (vertical) swipe(240, 220, 240, 600) else swipe(100, 360, 380, 360) }
            forward(); assertPage(model, 1)
            backward(); assertPage(model, 0)
            backward()
            ui.await("Intentional backward swipe on page 1 must still return Home") {
                model.screen == Screen.HOME && ui.has(context.getString(R.string.all_apps))
            }
            main { model.navigateTo(Screen.ALL_APPS); model.prefs.infiniteScroll = true }
            assertPage(model, 0)
            backward(); assertPage(model, model.totalPages - 1)
            forward(); assertPage(model, 0)
            main { model.prefs.infiniteScroll = false; model.showPage(model.totalPages - 1) }
            assertPage(model, model.totalPages - 1)
            forward(); assertPage(model, model.totalPages - 1)
        }
    }

    @Test fun hideArrowButtonsChangesTheLiveAxisWithoutRestart() = withApps(false) { model ->
        val prefs = PrefsManager(context)
        val arrows = context.getString(R.string.hide_arrow_buttons)
        main { model.navigateTo(Screen.SETTINGS) }
        ui.click(context.getString(R.string.section_all_apps))
        if (!ui.has(arrows)) ui.click(context.getString(R.string.paper_next_page))
        ui.await("Hide arrow buttons must start Off") { ui.checked(arrows) == false }
        ui.toggleSwitch(arrows, false, true) { prefs.hideArrowButtons }
        ui.back(); ui.back()
        ui.await("Settings did not return Home") { model.screen == Screen.HOME }
        main { model.navigateTo(Screen.ALL_APPS) }
        assertPage(model, 0)
        swipe(380, 360, 100, 360); assertPage(model, 0)
        swipe(240, 600, 240, 220); assertPage(model, 1)

        // A second preference owner updates the already mounted pager as well.
        main { model.showPage(0); prefs.hideArrowButtons = false }
        assertPage(model, 0)
        swipe(240, 600, 240, 220); assertPage(model, 0)
        swipe(380, 360, 100, 360); assertPage(model, 1)
    }
}
