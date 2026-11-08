package com.gezimos.katapult.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gezimos.katapult.R
import com.gezimos.katapult.lockscreen.LockscreenWidgetSettings

@Composable
internal fun LockscreenWidgetsPage(settings: LockscreenWidgetSettings, onBack: () -> Unit,
    onEnabled: (Boolean) -> Unit, onRotation: () -> Unit, onManage: (Int) -> Unit, onAdd: () -> Unit,
    onOpen: (Int) -> Unit) {
    SettingsPage(stringResource(R.string.lockscreen_widgets), onBack) {
        Entry(SettingsEntry.Switch("show", stringResource(R.string.lockscreen_widgets_show),
            stringResource(R.string.lockscreen_widgets_show_desc), settings.enabled, onEnabled))
        Entry(widgetRotationEntry(settings.rotation, settings::rotation), onChoice = { onRotation() })
        Entry(SettingsEntry.Switch("widget-swipe", stringResource(R.string.widget_swipe),
            stringResource(R.string.widget_swipe_help), settings.swipeToSwitch, settings::swipeToSwitch))
        Column(Modifier.fillMaxWidth()) {
            GroupHeading(stringResource(R.string.paper_widget_cards_group))
            Spacer(Modifier.height(9.dp))
            PaperCardList(settings.rows, { it.widget.id.toString() },
                stringResource(R.string.lockscreen_widgets_up), stringResource(R.string.lockscreen_widgets_down),
                settings::reorder, onCardClick = { onManage(it.widget.id) }) { row ->
                PaperCardEntry(row.label, stringResource(R.string.paper_widget_card_help), stringResource(widgetSizeTitle(row.widget.size)),
                    if (row.canOpen) { { onOpen(row.widget.id) } } else null)
            }
            Entry(SettingsEntry.Open("add", stringResource(R.string.lockscreen_widgets_add), "", onAdd))
        }
    }
}

internal fun widgetSizeTitle(size: com.gezimos.katapult.lockscreen.LockscreenWidgetSize): Int = when (size) {
    com.gezimos.katapult.lockscreen.LockscreenWidgetSize.S -> R.string.lockscreen_widgets_small
    com.gezimos.katapult.lockscreen.LockscreenWidgetSize.M -> R.string.lockscreen_widgets_medium
    com.gezimos.katapult.lockscreen.LockscreenWidgetSize.L -> R.string.lockscreen_widgets_large
}

@Composable
internal fun LockscreenWidgetManagementPage(settings: LockscreenWidgetSettings, id: Int, onBack: () -> Unit) {
    val row = settings.rows.firstOrNull { it.widget.id == id }
    if (row == null) androidx.compose.runtime.LaunchedEffect(id) { onBack() }
    else SettingsPage(row.label, onBack) {
        com.gezimos.katapult.lockscreen.LockscreenWidgetSize.entries.forEach { size ->
            ChoiceOption(stringResource(widgetSizeTitle(size)), size == row.widget.size) { settings.resize(id, size); onBack() }
        }
        val index = settings.rows.indexOf(row)
        if (index > 0) SheetAction(stringResource(R.string.lockscreen_widgets_up)) { settings.move(id, -1); onBack() }
        if (index < settings.rows.lastIndex) SheetAction(stringResource(R.string.lockscreen_widgets_down)) { settings.move(id, 1); onBack() }
        SheetAction(stringResource(R.string.lockscreen_widgets_remove)) { settings.remove(id); onBack() }
    }
}
