package com.gezimos.katapult.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.gezimos.katapult.R

@Composable
fun lockCategory(
    isMudita: Boolean, weatherAvailable: Boolean, notifications: Boolean, latestFirst: Boolean,
    music: Boolean, weather: Boolean, widgetsEnabled: Boolean, widgetCount: Int,
    onNotifications: (Boolean) -> Unit, onApps: () -> Unit, onLatest: (Boolean) -> Unit,
    onMusic: (Boolean) -> Unit, onWeather: (Boolean) -> Unit, onWidgets: () -> Unit, onMessage: () -> Unit,
): SettingsCategory {
    val sorts = listOf(SettingsChoice("latest", stringResource(R.string.sort_latest_top)), SettingsChoice("oldest", stringResource(R.string.sort_oldest_top)))
    val current = sorts[if (latestFirst) 0 else 1]
    return SettingsCategory("lock", stringResource(R.string.cat_lockscreen), listOf(
        SettingsGroup("notifications", stringResource(R.string.section_notifications), listOf(
            SettingsEntry.Switch("notifications", stringResource(R.string.lockscreen_widget), stringResource(R.string.lockscreen_widget_desc), notifications, onNotifications),
            SettingsEntry.Open("apps", stringResource(R.string.lockscreen_widget_apps), stringResource(R.string.lockscreen_widget_apps_desc), onApps),
            SettingsEntry.Value("sort", stringResource(R.string.lockscreen_sort), stringResource(R.string.paper_lock_sort_help), current.label, sorts, { onLatest(it == "latest") }, current.id),
        )),
        SettingsGroup("widgets", stringResource(R.string.paper_group_widgets_message), listOf(
            SettingsEntry.Switch("music", stringResource(R.string.lockscreen_music_widget), stringResource(R.string.lockscreen_music_widget_desc), music, onMusic),
            SettingsEntry.Switch("weather", stringResource(R.string.lockscreen_weather), stringResource(R.string.lockscreen_weather_desc), weather, onWeather, weatherAvailable),
            SettingsEntry.Open("widgets", stringResource(R.string.lockscreen_widgets), stringResource(R.string.paper_lock_widgets_help), onWidgets, isMudita,
                summary = if (widgetsEnabled) pluralStringResource(R.plurals.paper_lock_widgets_on, widgetCount, widgetCount) else stringResource(R.string.paper_lock_widgets_off)),
            SettingsEntry.Open("message", stringResource(R.string.lockscreen_message), stringResource(R.string.lockscreen_message_desc), onMessage),
        )),
    ), summary = pluralStringResource(R.plurals.paper_widgets_configured, widgetCount, widgetCount),
        help = SettingsHelp(stringResource(R.string.paper_about_lock), stringResource(R.string.cat_lockscreen), listOf(
            stringResource(R.string.lockscreen_readme_privacy), stringResource(R.string.a11y_shortcut_warning), stringResource(R.string.lockscreen_readme_usage))))
}
