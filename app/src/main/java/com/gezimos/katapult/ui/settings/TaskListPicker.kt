package com.gezimos.katapult.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.gezimos.katapult.R
import com.gezimos.katapult.notes.TaskListChoice

@Composable
fun TaskListPicker(choices: List<TaskListChoice>?, selected: Long?, pick: (Long) -> Unit, back: () -> Unit) {
    SettingsPage(stringResource(R.string.edge_task_list), back) {
        SheetText(stringResource(R.string.edge_task_help))
        if (choices.isNullOrEmpty()) SheetText(stringResource(R.string.edge_task_no_lists))
        choices.orEmpty().forEach { choice ->
            ChoiceOption(choice.title.ifBlank { stringResource(R.string.edge_task_untitled) }, choice.id == selected) { back(); pick(choice.id) }
        }
    }
}
