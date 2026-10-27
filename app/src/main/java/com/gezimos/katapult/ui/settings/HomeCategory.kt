package com.gezimos.katapult.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.gezimos.katapult.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val clockFormats = listOf("system", "HH:mm", "hh:mm a", "h:mm a", "h:mm")
private val dateFormats = listOf("system", "EEE, MMM d", "EEEE, MMMM d", "MMMM d, yyyy", "M/d/yyyy", "yyyy-MM-dd")

@Composable
private fun formatChoices(patterns: List<String>): List<SettingsChoice> = patterns.map { pattern ->
    SettingsChoice(pattern, if (pattern == "system") stringResource(R.string.theme_system)
        else SimpleDateFormat(pattern, Locale.getDefault()).format(Date()))
}

@Composable
fun homeCategory(
    clockFormat: String, dateFormat: String, alarm: Boolean, battery: Boolean,
    weatherAvailable: Boolean, weather: Boolean, celsius: Boolean, extraDock: Boolean,
    disableMusicWidget: Boolean,
    onClock: (String) -> Unit, onDate: (String) -> Unit, onAlarm: (Boolean) -> Unit,
    onBattery: (Boolean) -> Unit, onWeather: (Boolean) -> Unit, onCelsius: (Boolean) -> Unit,
    onDock: (Boolean) -> Unit, onMusic: (Boolean) -> Unit,
): SettingsCategory {
    val clocks = formatChoices(clockFormats)
    val dates = formatChoices(dateFormats)
    return SettingsCategory("home", stringResource(R.string.section_home), listOf(
        SettingsGroup("clock", stringResource(R.string.paper_group_clock), listOf(
            SettingsEntry.Value("clock_format", stringResource(R.string.clock_format), stringResource(R.string.paper_clock_format_help),
                clocks.firstOrNull { it.id == clockFormat }?.label.orEmpty(), clocks, onClock, clockFormat),
            SettingsEntry.Value("date_format", stringResource(R.string.date_format), stringResource(R.string.paper_date_format_help),
                dates.firstOrNull { it.id == dateFormat }?.label.orEmpty(), dates, onDate, dateFormat),
            SettingsEntry.Switch("alarm", stringResource(R.string.show_alarm), stringResource(R.string.show_alarm_desc), alarm, onAlarm),
            SettingsEntry.Switch("battery", stringResource(R.string.show_battery), stringResource(R.string.show_battery_desc), battery, onBattery),
        )),
        SettingsGroup("weather", stringResource(R.string.paper_group_weather), listOf(
            SettingsEntry.Switch("weather", stringResource(R.string.show_weather), stringResource(R.string.show_weather_desc), weather, onWeather, weatherAvailable),
            SettingsEntry.Switch("celsius", stringResource(R.string.weather_celsius), stringResource(R.string.weather_celsius_desc), celsius, onCelsius, weatherAvailable),
        )),
        SettingsGroup("layout", stringResource(R.string.paper_group_home_layout), listOf(
            SettingsEntry.Switch("dock", stringResource(R.string.extra_dock_row), stringResource(R.string.extra_dock_row_desc), extraDock, onDock),
            // The public setting is positive. The stored disable flag and export format do not change.
            SettingsEntry.Switch("music", stringResource(R.string.paper_music_widget), stringResource(R.string.paper_music_widget_help), !disableMusicWidget, onMusic),
        )),
    ), summary = SettingsSummaries.home(stringResource(if (alarm) R.string.paper_summary_home_on else R.string.paper_summary_home_off), if (extraDock) 3 else 0))
}
