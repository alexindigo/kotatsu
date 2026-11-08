package com.gezimos.katapult.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.gezimos.katapult.R
import com.gezimos.katapult.lockscreen.SideScreen
import com.gezimos.katapult.lockscreen.SideLayout

@Composable
internal fun SideScreenSettingsPage(screen: SideScreen, name: String, label: (Int) -> String,
                                   rename: () -> Unit, layout: () -> Unit, selectLayout: (SideLayout) -> Unit,
                                   slot: (Int) -> Unit, back: () -> Unit) {
    SettingsPage(stringResource(R.string.side_screen), back) {
        Entry(SettingsEntry.Open("name", stringResource(R.string.side_name), "", rename, actionLabel = name))
        Entry(sideLayoutEntry(screen.layout, selectLayout), onChoice = { layout() })
        repeat(screen.layout.cells) { cell ->
            Entry(SettingsEntry.Open("slot-$cell", stringResource(if (screen.layout == SideLayout.FULL) R.string.side_widget
                else if (cell == 0) R.string.side_top else R.string.side_bottom), "", { slot(cell) }, actionLabel = label(cell)))
        }
    }
}

@Composable
internal fun sideLayoutEntry(layout: SideLayout, select: (SideLayout) -> Unit): SettingsEntry.Value {
    val choices = SideLayout.entries.map {
        SettingsChoice(it.name, stringResource(if (it == SideLayout.FULL) R.string.side_full else R.string.side_split))
    }
    return SettingsEntry.Value("layout", stringResource(R.string.side_layout), stringResource(R.string.side_layout_help),
        choices.single { it.id == layout.name }.label, choices, { select(SideLayout.valueOf(it)) }, layout.name)
}
