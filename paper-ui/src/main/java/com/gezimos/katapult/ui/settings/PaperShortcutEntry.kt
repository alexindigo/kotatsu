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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gezimos.katapult.paper.R
import com.gezimos.katapult.ui.LatoFamily
import com.gezimos.katapult.ui.LocalInk
import com.gezimos.katapult.ui.LocalPalette

/** Launch and app-icon state are siblings, never nested click targets. */
@Composable
fun PaperShortcutEntry(label: String, stateLabel: String, checked: Boolean,
    onOpen: () -> Unit, onToggle: (Boolean) -> Unit) {
    val external = stringResource(R.string.paper_external)
    IndexTracks(title = {
        Box(Modifier.heightIn(min = 48.dp).clearAndSetSemantics {}, contentAlignment = Alignment.CenterStart) {
            Row(horizontalArrangement = Arrangement.spacedBy(7.5.dp)) {
                Box(Modifier.weight(1f, fill = false).alignByBaseline()) { IndexName(label) }
                Text("\u2197", fontFamily = LatoFamily, fontSize = 18.75.sp,
                    fontWeight = FontWeight.Bold, color = LocalInk.current, modifier = Modifier.alignByBaseline())
            }
        }
    }, value = {
        Box(Modifier.size(48.dp).inkFocus().toggleable(checked, role = Role.Checkbox, onValueChange = onToggle)
            .semantics { contentDescription = "$label: $stateLabel" }, contentAlignment = Alignment.CenterEnd) {
            IndexCheckbox(checked, true)
        }
    }, leadingTarget = {
        Box(Modifier.inkFocus().clickable(role = Role.Button, onClick = onOpen)
            .semantics { contentDescription = label; onClick(label = external, action = null) })
    }, leaderColor = LocalPalette.current.muted, valueHasBaseline = false)
}
