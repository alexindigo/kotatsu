package com.gezimos.katapult.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.gezimos.katapult.R
import com.gezimos.katapult.util.PrefsManager

@Composable
fun screensaverCategory(
    enabled: Boolean, clock: Boolean, notifications: Boolean, eink: Boolean,
    wallpaper: Boolean, hasImage: Boolean, brightness: Boolean, power: Boolean, icon: Boolean, mode: Int,
    onEnabled: (Boolean) -> Unit, onSystem: () -> Unit, onCustomize: () -> Unit, onWallpaper: (Boolean) -> Unit,
    onImage: () -> Unit, onBrightness: (Boolean) -> Unit, onPower: (Boolean) -> Unit, onIcon: (Boolean) -> Unit,
    onMode: (Int) -> Unit,
): SettingsCategory {
    val modes = listOf(PrefsManager.SCREENSAVER_MODE_AUTO to R.string.screensaver_mode_auto,
        PrefsManager.SCREENSAVER_MODE_INTERVAL to R.string.screensaver_mode_interval, PrefsManager.SCREENSAVER_MODE_STATIC to R.string.screensaver_mode_static)
        .map { (id, label) -> SettingsChoice(id.toString(), stringResource(label)) }
    val current = modes.firstOrNull { it.id == mode.toString() } ?: modes.first()
    val customization = listOfNotNull(
        stringResource(R.string.paper_group_clock).takeIf { clock },
        stringResource(R.string.paper_screensaver_notifications).takeIf { notifications },
        stringResource(R.string.screensaver_eink_refresh).takeIf { eink },
    ).joinToString(", ").ifEmpty { stringResource(R.string.double_tap_off) }
    return SettingsCategory("screensaver", stringResource(R.string.section_screensaver), listOf(
        SettingsGroup("display", stringResource(R.string.paper_group_display), listOf(
            SettingsEntry.Switch("enabled", stringResource(R.string.screensaver), stringResource(R.string.screensaver_enable_desc), enabled, onEnabled),
            SettingsEntry.Link("system", stringResource(R.string.screensaver_system), stringResource(R.string.screensaver_settings_desc), onSystem, enabled),
            SettingsEntry.Open("customization", stringResource(R.string.screensaver_customization), stringResource(R.string.paper_screensaver_custom_help), onCustomize, enabled, customization),
        )),
        SettingsGroup("background", stringResource(R.string.paper_group_background), listOf(
            SettingsEntry.Switch("wallpaper", stringResource(R.string.screensaver_wallpaper), stringResource(R.string.screensaver_wallpaper_desc), wallpaper, onWallpaper, enabled),
            SettingsEntry.Open("image", stringResource(if (hasImage) R.string.clear_image else R.string.screensaver_image),
                stringResource(if (hasImage) R.string.paper_clear_image_help else R.string.paper_screensaver_image_help), onImage, enabled && wallpaper,
                stringResource(if (hasImage) R.string.screensaver_wallpaper_custom else R.string.screensaver_wallpaper_home)),
        )),
        SettingsGroup("interaction", stringResource(R.string.paper_group_interaction), listOf(
            SettingsEntry.Switch("brightness", stringResource(R.string.screensaver_double_tap_brightness), stringResource(R.string.screensaver_double_tap_brightness_desc), brightness, onBrightness, enabled),
            SettingsEntry.Switch("power", stringResource(R.string.screensaver_on_power), stringResource(R.string.screensaver_on_power_desc), power, onPower, enabled),
            SettingsEntry.Switch("icon", stringResource(R.string.screensaver_app_icon), stringResource(R.string.screensaver_app_icon_desc), icon, onIcon, enabled),
        )),
        SettingsGroup("refresh", stringResource(R.string.paper_group_refresh), listOf(
            SettingsEntry.Value("mode", stringResource(R.string.screensaver_update_mode), stringResource(R.string.paper_screensaver_mode_help), current.label, modes, { onMode(it.toInt()) }, current.id, enabled),
        )),
    ), summary = stringResource(if (enabled) R.string.paper_on else R.string.paper_off),
        help = SettingsHelp(stringResource(R.string.paper_about_screensaver), stringResource(R.string.section_screensaver), listOf(
            stringResource(R.string.screensaver_readme_accessibility), stringResource(R.string.a11y_shortcut_warning), stringResource(R.string.screensaver_readme_about),
            stringResource(R.string.screensaver_readme_use), stringResource(R.string.screensaver_readme_modes))))
}
