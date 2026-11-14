package com.gezimos.katapult.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.gezimos.katapult.R
import com.gezimos.katapult.util.PrefsManager

@Composable
fun gesturesCategory(
    swipeApps: Boolean, vertical: Boolean, doubleTap: Int, recentApps: Boolean, screenshot: Boolean, homeMenu: Boolean,
    onSwipe: (Boolean) -> Unit, onVertical: (Boolean) -> Unit, onDoubleTap: (Int) -> Unit,
    onRecentApps: (Boolean) -> Unit,
    onScreenshot: (Boolean) -> Unit,
    onHomeMenu: (Boolean) -> Unit,
): SettingsCategory {
    val directions = listOf(SettingsChoice("horizontal", stringResource(R.string.gesture_horizontal)), SettingsChoice("vertical", stringResource(R.string.gesture_vertical)))
    val taps = listOf(PrefsManager.DOUBLE_TAP_OFF to R.string.double_tap_off, PrefsManager.DOUBLE_TAP_BRIGHTNESS to R.string.double_tap_brightness,
        PrefsManager.DOUBLE_TAP_LOCK to R.string.double_tap_lock).map { (id, label) ->
            SettingsChoice(id.toString(), stringResource(label), stringResource(when (id) {
                PrefsManager.DOUBLE_TAP_OFF -> R.string.double_tap_off_help
                PrefsManager.DOUBLE_TAP_BRIGHTNESS -> R.string.double_tap_brightness_help
                else -> R.string.double_tap_lock_help
            }))
        }
    val direction = directions[if (vertical) 1 else 0]
    return SettingsCategory("gestures", stringResource(R.string.section_gestures), listOf(
        SettingsGroup("pages", stringResource(R.string.paper_group_app_pages), listOf(
            SettingsEntry.Switch("swipe", stringResource(R.string.swipe_up_all_apps), stringResource(R.string.swipe_up_all_apps_desc), swipeApps, onSwipe),
            SettingsEntry.Value("direction", stringResource(R.string.app_gesture_direction), stringResource(R.string.paper_gesture_direction_help),
                direction.label, directions, { onVertical(it == "vertical") }, direction.id),
        )),
        SettingsGroup("home", stringResource(R.string.paper_group_on_home), listOf(
            SettingsEntry.Value("double_tap", stringResource(R.string.double_tap_action), stringResource(R.string.paper_double_tap_help),
                (taps.firstOrNull { it.id == doubleTap.toString() } ?: taps.first()).label, taps, { onDoubleTap(it.toInt()) }, doubleTap.toString()),
        )),
        SettingsGroup("home_button", stringResource(R.string.paper_group_home_button), listOf(
            SettingsEntry.Switch("home_menu", stringResource(R.string.home_button_menu), stringResource(R.string.home_button_menu_help),
                homeMenu, onHomeMenu),
            SettingsEntry.Switch("recent_apps", stringResource(R.string.recent_apps), stringResource(R.string.home_button_recent_apps_help),
                recentApps, onRecentApps),
            SettingsEntry.Switch("screenshot", stringResource(R.string.home_button_screenshot), stringResource(R.string.home_button_screenshot_help),
                screenshot, onScreenshot),
        )),
    ), summary = direction.label, help = SettingsHelp(stringResource(R.string.paper_about_gestures), stringResource(R.string.section_gestures),
        listOf(stringResource(R.string.gestures_readme_nav), stringResource(R.string.gestures_readme_apps))))
}
