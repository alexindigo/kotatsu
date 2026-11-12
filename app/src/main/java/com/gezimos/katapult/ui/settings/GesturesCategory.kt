package com.gezimos.katapult.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.gezimos.katapult.R
import com.gezimos.katapult.util.PrefsManager
import com.gezimos.katapult.util.HomeAppSwipe

@Composable
fun gesturesCategory(
    swipeApps: HomeAppSwipe, vertical: Boolean, doubleTap: Int, longPress: Int, recentApps: Boolean, screenshot: Boolean, homeMenu: Boolean,
    onSwipe: (HomeAppSwipe) -> Unit, onVertical: (Boolean) -> Unit, onDoubleTap: (Int) -> Unit, onLongPress: (Int) -> Unit,
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
    val presses = listOf(PrefsManager.HOME_LONG_PRESS_APP_INFO to R.string.home_long_press_app_info,
        PrefsManager.HOME_LONG_PRESS_MENU to R.string.home_long_press_menu, PrefsManager.HOME_LONG_PRESS_CLEAR to R.string.home_long_press_clear,
        PrefsManager.HOME_LONG_PRESS_EDIT to R.string.home_long_press_edit, PrefsManager.HOME_LONG_PRESS_NONE to R.string.home_long_press_none)
        .map { (id, label) -> SettingsChoice(id.toString(), stringResource(label)) }
    val direction = directions[if (vertical) 1 else 0]
    val swipes = HomeAppSwipe.entries.map { choice -> SettingsChoice(choice.id, stringResource(when (choice) {
        HomeAppSwipe.NONE -> R.string.swipe_apps_none
        HomeAppSwipe.UP -> R.string.swipe_apps_up
        HomeAppSwipe.LEFT -> R.string.swipe_apps_left
        HomeAppSwipe.UP_AND_LEFT -> R.string.swipe_apps_up_left
    })) }
    return SettingsCategory("gestures", stringResource(R.string.section_gestures), listOf(
        SettingsGroup("pages", stringResource(R.string.paper_group_app_pages), listOf(
            SettingsEntry.Value("swipe", stringResource(R.string.swipe_up_all_apps), stringResource(R.string.swipe_up_all_apps_desc),
                swipes.single { it.id == swipeApps.id }.label, swipes, { onSwipe(requireNotNull(HomeAppSwipe.fromId(it))) }, swipeApps.id),
            SettingsEntry.Value("direction", stringResource(R.string.app_gesture_direction), stringResource(R.string.paper_gesture_direction_help),
                direction.label, directions, { onVertical(it == "vertical") }, direction.id),
        )),
        SettingsGroup("home", stringResource(R.string.paper_group_on_home), listOf(
            SettingsEntry.Value("double_tap", stringResource(R.string.double_tap_action), stringResource(R.string.paper_double_tap_help),
                (taps.firstOrNull { it.id == doubleTap.toString() } ?: taps.first()).label, taps, { onDoubleTap(it.toInt()) }, doubleTap.toString()),
            SettingsEntry.Value("long_press", stringResource(R.string.home_long_press), stringResource(R.string.home_long_press_desc),
                (presses.firstOrNull { it.id == longPress.toString() } ?: presses[3]).label, presses, { onLongPress(it.toInt()) }, longPress.toString()),
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
