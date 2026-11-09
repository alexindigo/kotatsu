package com.gezimos.katapult.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gezimos.katapult.R
import com.gezimos.katapult.recent.*
import com.gezimos.katapult.ui.settings.*
import java.util.Calendar

@Composable
private fun lastUseLabel(lastUsed: Long, now: Long): String {
    val day = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
    val today = day.timeInMillis
    day.add(Calendar.DAY_OF_YEAR, -1)
    val use = relativeUse(lastUsed, now, today, day.timeInMillis)
    return when (use.age) {
        RecentAge.NOW -> stringResource(R.string.recent_just_now)
        RecentAge.YESTERDAY -> stringResource(R.string.recent_yesterday)
        RecentAge.MINUTES -> pluralStringResource(R.plurals.recent_minutes, use.count, use.count)
        RecentAge.HOURS -> pluralStringResource(R.plurals.recent_hours, use.count, use.count)
        RecentAge.DAYS -> pluralStringResource(R.plurals.recent_days, use.count, use.count)
    }
}

@Composable
fun RecentAppsScreen(model: RecentApps) {
    val title = stringResource(R.string.recent_apps)
    val close = stringResource(R.string.recent_close)
    CompositionLocalProvider(LocalInk provides Color.Black, LocalSurface provides Color.White) {
        PageFrame(title, model::back, Modifier.windowInsetsPadding(WindowInsets.systemBars)
            .semantics { paneTitle = title }, heading = {
            PageHeader(title, trailing = {
                HeaderAction(stringResource(R.string.recent_dismiss), model::dismiss) { CloseMark() }
            })
            Box(Modifier.fillMaxWidth().height(2.dp).background(LocalInk.current))
        }) {
            if (!model.available) Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                val message = stringResource(R.string.recent_usage_required)
                Entry(if (model.canOpenAccess) SettingsEntry.Open("recent-access", message, "", onOpen = model::openAccess)
                    else SettingsEntry.Metadata("recent-access", message, ""))
            } else {
                WholeRowList(model.apps, scrollRailLabels(), { it.packageName }, reset = model.refreshRevision) { app ->
                    PaperActionEntry(app.label, lastUseLabel(app.lastUsed, model.now), close,
                        { model.switchTo(app.packageName) }, { model.close(app.packageName) },
                        onSwipeLeft = { model.close(app.packageName) })
                }
            }
        }
    }
}
