package com.gezimos.katapult.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gezimos.katapult.R
import com.gezimos.katapult.model.AppModel

enum class NotifyCell { NONE, OFF, ON, DISABLED }

/** One paged app-list renderer; callers supply state and commands, never storage. */
@Composable
fun AppChecklistPage(
    title: String,
    apps: List<AppModel>,
    checked: Set<String>,
    onDismiss: () -> Unit,
    onToggle: (AppModel) -> Unit,
    notifyCell: ((AppModel) -> NotifyCell)? = null,
    onToggleNotify: (AppModel) -> Unit = {},
    selectAllLabel: String? = null,
    onSelectAll: () -> Unit = {},
    help: String = "",
) {
    val hiddenColumn = rememberControlColumn(stringResource(R.string.hidden_apps_hidden_column))
    val notifyColumn = rememberControlColumn(stringResource(R.string.hidden_apps_notify_column))
    PagedSettingsPage(title, apps, onDismiss, header = {
        PaperHelp(help)
        if (notifyCell != null) {
            SheetText(stringResource(R.string.hidden_apps_notify_desc))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ColumnLabel(hiddenColumn)
                Spacer(Modifier.weight(1f))
                ColumnLabel(notifyColumn)
            }
        }
        if (selectAllLabel != null) SheetAction(selectAllLabel, onSelectAll)
    }, row = { app ->
        if (notifyCell == null) CheckboxOption(app.label, app.key in checked) { onToggle(app) }
        else VisibilityRow(app.label, app.key in checked, notifyCell(app),
            { onToggle(app) }, { onToggleNotify(app) }, hiddenColumn.width, notifyColumn.width)
    })
}

/** Neither the parent nor the name target contains the right-hand control. */
@Composable
private fun VisibilityRow(label: String, hidden: Boolean, notify: NotifyCell, onHide: () -> Unit, onNotify: () -> Unit,
    hiddenColumnWidth: Dp, notifyColumnWidth: Dp) {
    val hiddenLabel = stringResource(R.string.paper_hidden_app, label)
    val notifyLabel = stringResource(R.string.paper_notify_app, label)
    val unavailable = stringResource(R.string.paper_unavailable)
    val notApplicable = stringResource(R.string.paper_not_applicable)
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.width(hiddenColumnWidth).heightIn(min = 64.dp).fillMaxHeight()
            .toggleable(hidden, role = Role.Checkbox, onValueChange = { onHide() })
            .semantics { contentDescription = hiddenLabel }, contentAlignment = Alignment.Center) {
            IndexCheckbox(hidden, true)
        }
        PaperCheckboxEntry(label, notifyLabel, notify == NotifyCell.ON, onHide, onNotify,
            Modifier.weight(1f).heightIn(min = 64.dp), nameChecked = hidden, controlColumnWidth = notifyColumnWidth,
            checkboxVisible = notify != NotifyCell.NONE,
            checkboxEnabled = notify == NotifyCell.ON || notify == NotifyCell.OFF,
            checkboxStateDescription = when (notify) {
                NotifyCell.NONE -> notApplicable
                NotifyCell.DISABLED -> unavailable
                else -> null
            })
    }
}
