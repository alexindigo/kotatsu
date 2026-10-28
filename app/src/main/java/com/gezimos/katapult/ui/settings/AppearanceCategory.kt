package com.gezimos.katapult.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.gezimos.katapult.R
import com.gezimos.katapult.util.EinkHelper
import com.gezimos.katapult.util.PrefsManager
import com.gezimos.katapult.util.WallpaperSource

/** The chapter owns its vocabulary and summary. Persistence and device effects are injected. */
@Composable
fun appearanceCategory(
    isMudita: Boolean, rounded: Boolean, iconSize: Int, themeMode: Int,
    hideAppNames: Boolean, hideStatusBar: Boolean, hideStatusClock: Boolean,
    einkMode: Int, refreshHome: Boolean,
    onShape: (Boolean) -> Unit, onSize: (Int) -> Unit, onTheme: (Int) -> Unit,
    onHideNames: (Boolean) -> Unit, onHideStatus: (Boolean) -> Unit, onHideClock: (Boolean) -> Unit,
    onEink: (Int) -> Unit, onRefreshHome: (Boolean) -> Unit,
    wallpaperSource: WallpaperSource = WallpaperSource.NONE,
    onWallpaper: (String) -> Unit = {},
): SettingsCategory {
    val shapes = listOf(SettingsChoice("rounded", stringResource(R.string.icon_shape_rounded)),
        SettingsChoice("circle", stringResource(R.string.icon_shape_circle)))
    val sizes = listOf(PrefsManager.ICON_SIZE_SMALL, PrefsManager.ICON_SIZE_LARGE).map {
        SettingsChoice(it.toString(), stringResource(R.string.icon_size_value, it, it))
    }
    val themes = listOf(PrefsManager.THEME_SYSTEM to R.string.theme_system,
        PrefsManager.THEME_LIGHT to R.string.theme_light, PrefsManager.THEME_DARK to R.string.theme_dark)
        .map { (id, label) -> SettingsChoice(id.toString(), stringResource(label)) }
    val eink = listOf(EinkHelper.MEINK_MODE_DISABLED to R.string.paper_eink_disabled,
        EinkHelper.MEINK_MODE_CLEAR to R.string.paper_eink_clear,
        EinkHelper.MEINK_MODE_CONTRAST to R.string.paper_eink_contrast,
        EinkHelper.MEINK_MODE_READING to R.string.paper_eink_reading)
        .map { (id, label) -> SettingsChoice(id.toString(), stringResource(label)) }
    val shape = shapes[if (rounded) 0 else 1]
    val theme = themes.firstOrNull { it.id == themeMode.toString() } ?: themes[1]
    val mode = eink.firstOrNull { it.id == einkMode.toString() } ?: eink[0]
    val wallpapers = listOf(
        SettingsChoice("NONE", stringResource(R.string.wallpaper_none)),
        SettingsChoice("KOTATSU", stringResource(R.string.wallpaper_kotatsu)),
        SettingsChoice("PHOTO", stringResource(R.string.wallpaper_photos)),
    )
    return SettingsCategory("appearance", stringResource(R.string.section_appearance), listOf(
        SettingsGroup("icons", stringResource(R.string.paper_group_icons), listOf(
            SettingsEntry.Value("shape", stringResource(R.string.icon_shape), stringResource(R.string.paper_icon_shape_help),
                shape.label, shapes, { onShape(it == "rounded") }, shape.id),
            SettingsEntry.Value("size", stringResource(R.string.icon_size), stringResource(R.string.paper_icon_size_help),
                stringResource(R.string.icon_size_value, iconSize, iconSize), sizes, { onSize(it.toInt()) }, iconSize.toString()),
            SettingsEntry.Switch("names", stringResource(R.string.hide_app_names), stringResource(R.string.hide_app_names_desc),
                hideAppNames, onHideNames),
        )),
        SettingsGroup("screen", stringResource(R.string.paper_group_screen), listOf(
            SettingsEntry.Value("theme", stringResource(R.string.theme_mode), stringResource(R.string.theme_mode_desc),
                theme.label, themes, { onTheme(it.toInt()) }, theme.id),
            SettingsEntry.Value("wallpaper", stringResource(R.string.wallpaper), stringResource(R.string.wallpaper_help),
                wallpapers.single { it.id == wallpaperSource.name }.label, wallpapers, onWallpaper, wallpaperSource.name),
            SettingsEntry.Switch("status", stringResource(R.string.hide_status_bar), stringResource(R.string.hide_status_bar_desc),
                hideStatusBar, onHideStatus),
            SettingsEntry.Switch("clock", stringResource(R.string.hide_status_bar_clock), stringResource(R.string.hide_status_bar_clock_desc),
                hideStatusClock, onHideClock, visible = isMudita),
        )),
        SettingsGroup("eink", stringResource(R.string.section_eink), listOf(
            SettingsEntry.Value("mode", stringResource(R.string.eink_auto_mode), stringResource(R.string.paper_eink_mode_help),
                mode.label, eink, { onEink(it.toInt()) }, mode.id, visible = isMudita),
            SettingsEntry.Switch("refresh", stringResource(R.string.eink_refresh), stringResource(R.string.eink_refresh_desc),
                refreshHome, onRefreshHome),
        )),
    ), summary = SettingsSummaries.appearance(stringResource(R.string.paper_summary_appearance), shape.label, theme.label,
        if (einkMode == EinkHelper.MEINK_MODE_DISABLED) stringResource(R.string.paper_summary_off) else mode.label),
        help = SettingsHelp(stringResource(R.string.paper_about_eink), stringResource(R.string.section_eink),
            listOf(stringResource(R.string.eink_readme_intro), stringResource(R.string.eink_readme_modes))))
}
