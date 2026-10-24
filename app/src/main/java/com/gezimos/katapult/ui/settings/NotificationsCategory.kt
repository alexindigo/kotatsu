package com.gezimos.katapult.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.gezimos.katapult.R

@Composable
fun notificationsCategory(
    indicators: Boolean, accessGranted: Boolean, onIndicators: (Boolean) -> Unit,
    onApps: () -> Unit, onLog: () -> Unit, onHistory: () -> Unit,
): SettingsCategory = SettingsCategory("notifications", stringResource(R.string.section_notifications), listOf(
    SettingsGroup("indicators", stringResource(R.string.paper_group_indicators), listOf(
        SettingsEntry.Switch("indicators", stringResource(R.string.notification_indicators), stringResource(R.string.notification_indicators_desc), indicators, onIndicators),
    )),
    SettingsGroup("android", stringResource(R.string.paper_group_android_notifications), listOf(
        SettingsEntry.Link("apps", stringResource(R.string.app_notifications), stringResource(R.string.app_notifications_desc), onApps),
        SettingsEntry.Link("log", stringResource(R.string.notification_log), stringResource(R.string.notification_log_desc), onLog),
        SettingsEntry.Link("history", stringResource(R.string.notification_history), stringResource(R.string.notification_history_desc), onHistory),
    )),
), summary = stringResource(if (accessGranted) R.string.paper_access_granted else R.string.paper_access_missing),
    help = SettingsHelp(stringResource(R.string.paper_about_notifications), stringResource(R.string.section_notifications),
        listOf(stringResource(R.string.notifications_readme_control), stringResource(R.string.notifications_readme_debug))))
