package com.gezimos.katapult.ui.settings

import androidx.compose.runtime.Composable

/** Launch and app-icon state are siblings, never nested click targets. */
@Composable
fun PaperShortcutEntry(label: String, stateLabel: String, checked: Boolean,
    onOpen: () -> Unit, onToggle: (Boolean) -> Unit) {
    PaperCheckboxEntry(label, "$label: $stateLabel", checked, onOpen, { onToggle(!checked) }, external = true)
}
