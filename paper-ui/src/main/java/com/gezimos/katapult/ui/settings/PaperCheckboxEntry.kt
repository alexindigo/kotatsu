package com.gezimos.katapult.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gezimos.katapult.paper.R
import com.gezimos.katapult.ui.LatoFamily
import com.gezimos.katapult.ui.LocalInk
import com.gezimos.katapult.ui.LocalPalette

/** Name and checkbox are independent actions; the leader belongs to the checkbox target. */
@Composable
fun PaperCheckboxEntry(label: String, checkboxLabel: String, checked: Boolean,
    onOpen: () -> Unit, onToggle: () -> Unit, modifier: Modifier = Modifier,
    external: Boolean = false, nameChecked: Boolean? = null, controlColumnWidth: Dp = 0.dp,
    checkboxVisible: Boolean = true, checkboxEnabled: Boolean = true, checkboxStateDescription: String? = null) {
    val handoff = stringResource(R.string.paper_external)
    IndexTracks(modifier, title = {
        Box(Modifier.heightIn(min = 48.dp).clearAndSetSemantics {}, contentAlignment = Alignment.CenterStart) {
            Row(horizontalArrangement = Arrangement.spacedBy(7.5.dp)) {
                Box(Modifier.weight(1f, fill = false).alignByBaseline()) { IndexName(label) }
                if (external) Text("\u2197", fontFamily = LatoFamily, fontSize = 18.75.sp,
                    fontWeight = FontWeight.Bold, color = LocalInk.current, modifier = Modifier.alignByBaseline())
            }
        }
    }, value = {
        if (checkboxVisible) IndexCheckbox(checked, checkboxEnabled)
        else Spacer(Modifier.size(20.dp))
    }, leadingTarget = {
        Box(Modifier.inkFocus().clickable(role = if (nameChecked == null) Role.Button else Role.Checkbox,
            onClickLabel = if (external) handoff else null, onClick = onOpen).semantics {
            contentDescription = label
            if (nameChecked != null) toggleableState = if (nameChecked) ToggleableState.On else ToggleableState.Off
        })
    }, trailingTarget = {
        val action = if (checkboxVisible) Modifier.toggleable(checked, enabled = checkboxEnabled,
            role = Role.Checkbox, onValueChange = { onToggle() }) else Modifier.clickable {}
        Box(Modifier.inkFocus().then(action).semantics {
            if (checkboxVisible) contentDescription = checkboxLabel
            if (!checkboxVisible || !checkboxEnabled) disabled()
            if (checkboxStateDescription != null) stateDescription = checkboxStateDescription
        })
    }, controlColumnWidth = controlColumnWidth, leaderColor = LocalPalette.current.muted, valueHasBaseline = false)
}
