package com.gezimos.katapult

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.util.HomeAppSwipe
import com.gezimos.katapult.util.PrefsManager
import org.junit.Assert.*
import org.junit.Test

class SwipeOwnersInstrumentedTest {
    @get:org.junit.Rule val accessibility = ActionServiceOffRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val ui = PlatformUiDriver(instrumentation)
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun fixture(test: (MainViewModel) -> Unit) {
        ApplicationPreferencesFixture(context).use { preferences ->
            preferences.reset {
                putBoolean("onboarding_complete", true)
                putInt("theme_mode", PrefsManager.THEME_LIGHT)
                putBoolean("hide_arrow_buttons", true)
            }
            val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            try { test(activity.viewModel) }
            finally {
                main { activity.finish() }
                ui.await("Swipe owner fixture did not release its model") { activity.isDestroyed }
            }
        }
    }

    private fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, duration: Int = 250) {
        instrumentation.platformAutomation.boundedShell("input swipe $x1 $y1 $x2 $y2 $duration")
        instrumentation.waitForIdleSync()
    }

    @Test fun homeChoiceUsesOnlyTheDominantDirectionNotItsMinorProjection() = fixture { model ->
        data class Input(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val up: Boolean, val left: Boolean)
        val inputs = listOf(
            Input(380, 500, 100, 360, false, true), // left, with upward drift
            Input(160, 600, 300, 220, true, false), // up, with rightward drift
            Input(100, 500, 380, 360, false, false), // right, with upward drift
            Input(300, 220, 160, 600, false, false), // down, with leftward drift
        )
        for (choice in HomeAppSwipe.entries) for (input in inputs) {
            main { model.prefs.swipeAllApps = choice; model.navigateTo(Screen.ALL_APPS) }
            ui.await("Home test must release the previous pointer owner") { model.screen == Screen.ALL_APPS &&
                model.orderedApps.isNotEmpty() && ui.has(model.orderedApps.first().label) }
            main { model.navigateTo(Screen.HOME) }
            ui.await("Home not rendered for $choice") { ui.has(context.getString(R.string.all_apps)) }
            swipe(input.x1, input.y1, input.x2, input.y2)
            val opens = input.up && choice in listOf(HomeAppSwipe.UP, HomeAppSwipe.UP_AND_LEFT) ||
                input.left && choice in listOf(HomeAppSwipe.LEFT, HomeAppSwipe.UP_AND_LEFT)
            ui.await("Home chose a minor axis for $choice / $input") {
                model.screen == if (opens) Screen.ALL_APPS else Screen.HOME
            }
            assertFalse("Moving input opened the Home menu", ui.has(context.getString(R.string.wallpaper)))
        }
    }

}
