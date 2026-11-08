package com.gezimos.katapult.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.gezimos.katapult.R
import com.gezimos.katapult.lockscreen.WidgetRotation

@Composable
internal fun widgetRotationEntry(mode: WidgetRotation, select: (WidgetRotation) -> Unit): SettingsEntry.Value {
    val choices = WidgetRotation.entries.map {
        SettingsChoice(it.name, stringResource(when (it) {
            WidgetRotation.WAKE -> R.string.widget_rotation_wake
            WidgetRotation.SECONDS_30 -> R.string.widget_rotation_30s
            WidgetRotation.MINUTE_1 -> R.string.widget_rotation_1m
            WidgetRotation.MINUTES_5 -> R.string.widget_rotation_5m
            WidgetRotation.MINUTES_15 -> R.string.widget_rotation_15m
        }))
    }
    val value = stringResource(when (mode) {
        WidgetRotation.WAKE -> R.string.widget_rotation_wake
        WidgetRotation.SECONDS_30 -> R.string.widget_rotation_value_30s
        WidgetRotation.MINUTE_1 -> R.string.widget_rotation_value_1m
        WidgetRotation.MINUTES_5 -> R.string.widget_rotation_value_5m
        WidgetRotation.MINUTES_15 -> R.string.widget_rotation_value_15m
    })
    return SettingsEntry.Value("rotation", stringResource(R.string.widget_rotation),
        stringResource(R.string.widget_rotation_help), value, choices,
        { select(WidgetRotation.valueOf(it)) }, mode.name)
}
