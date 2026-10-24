package com.gezimos.katapult.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gezimos.katapult.R
import com.gezimos.katapult.model.AppModel
import com.gezimos.katapult.ui.LatoFamily
import com.gezimos.katapult.ui.LocalInk

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
) {
    PagedSheetPage(title, apps, onDismiss, header = {
        if (notifyCell != null) {
            SheetText(stringResource(R.string.hidden_apps_notify_desc))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ColumnLabel(stringResource(R.string.hidden_apps_hide_column))
                Spacer(Modifier.weight(1f))
                ColumnLabel(stringResource(R.string.hidden_apps_notify_column))
            }
        }
        if (selectAllLabel != null) SheetAction(selectAllLabel, onSelectAll)
    }, row = { app ->
        if (notifyCell == null) CheckboxOption(app.label, app.key in checked) { onToggle(app) }
        else VisibilityRow(app.label, app.key in checked, notifyCell(app),
            { onToggle(app) }, { onToggleNotify(app) })
    })
}

@Composable
private fun ColumnLabel(label: String) {
    Text(label, fontFamily = LatoFamily, fontSize = 14.sp, lineHeight = 17.25.sp,
        color = LocalInk.current, textAlign = TextAlign.Center, modifier = Modifier.width(64.dp))
}

/** Neither the parent nor the name target contains the right-hand control. */
@Composable
private fun VisibilityRow(label: String, hidden: Boolean, notify: NotifyCell, onHide: () -> Unit, onNotify: () -> Unit) {
    val hideLabel = stringResource(R.string.paper_hide_app, label)
    val notifyLabel = stringResource(R.string.paper_notify_app, label)
    val unavailable = stringResource(R.string.paper_unavailable)
    val notApplicable = stringResource(R.string.paper_not_applicable)
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.width(64.dp).heightIn(min = 64.dp).fillMaxHeight()
            .toggleable(hidden, role = Role.Checkbox, onValueChange = { onHide() })
            .semantics { contentDescription = hideLabel }, contentAlignment = Alignment.Center) {
            IndexCheckbox(hidden, true)
        }
        Box(Modifier.weight(1f).heightIn(min = 64.dp).fillMaxHeight()
            .clickable(role = Role.Checkbox, onClick = onHide).semantics(mergeDescendants = true) {
                toggleableState = if (hidden) ToggleableState.On else ToggleableState.Off
            }, contentAlignment = Alignment.CenterStart) { IndexLine(label, "") }
        Box(Modifier.width(64.dp).heightIn(min = 64.dp).fillMaxHeight()
            // Even a blank/disabled cell owns the entire independent touch column.
            .clickable { if (notify == NotifyCell.ON || notify == NotifyCell.OFF) onNotify() }
            .semantics {
                if (notify != NotifyCell.NONE) {
                    contentDescription = notifyLabel
                    role = Role.Checkbox
                    toggleableState = if (notify == NotifyCell.ON) ToggleableState.On else ToggleableState.Off
                }
                if (notify == NotifyCell.DISABLED) { disabled(); stateDescription = unavailable }
                if (notify == NotifyCell.NONE) { disabled(); stateDescription = notApplicable }
            }, contentAlignment = Alignment.Center) {
            if (notify != NotifyCell.NONE) IndexCheckbox(notify == NotifyCell.ON, notify != NotifyCell.DISABLED)
        }
    }
}
