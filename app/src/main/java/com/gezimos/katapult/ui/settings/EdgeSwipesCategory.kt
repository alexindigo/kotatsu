package com.gezimos.katapult.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.gezimos.katapult.R
import com.gezimos.katapult.lockscreen.EdgeAction
import com.gezimos.katapult.lockscreen.ScreenEdge

@Composable
fun edgeSwipesCategory(enabled: Boolean, left: EdgeAction, right: EdgeAction, cameraAvailable: Boolean,
                        onEnabled: (Boolean) -> Unit, onAction: (ScreenEdge, EdgeAction) -> Unit,
                        taskListTitle: String? = null, onTaskList: () -> Unit = {}, onSideScreen: () -> Unit = {}): SettingsCategory {
    val choices = EdgeAction.entries.map { SettingsChoice(it.id, stringResource(when (it) {
        EdgeAction.CAMERA -> R.string.edge_camera
        EdgeAction.TASK_LIST -> R.string.edge_task_list
        EdgeAction.SIDE_SCREEN -> R.string.side_screen
        EdgeAction.NOTHING -> R.string.edge_nothing
    })) }
    return SettingsCategory("edges", stringResource(R.string.edge_swipes), listOf(
        SettingsGroup("swipes", "", listOf(
            SettingsEntry.Switch("enabled", stringResource(R.string.edge_swipes), stringResource(R.string.edge_swipes_help), enabled, onEnabled),
        )),
        SettingsGroup("actions", stringResource(R.string.edge_actions), ScreenEdge.entries.map { edge ->
            val action = if (edge == ScreenEdge.LEFT) left else right
            SettingsEntry.Value(edge.name, stringResource(if (edge == ScreenEdge.LEFT) R.string.edge_left else R.string.edge_right),
                "", choices.single { it.id == action.id }.label, choices,
                { id -> EdgeAction.fromId(id)?.let { onAction(edge, it) } }, action.id)
        }),
        SettingsGroup("camera", stringResource(R.string.edge_camera), listOf(
            SettingsEntry.Metadata("camera", stringResource(R.string.edge_camera),
                stringResource(if (cameraAvailable) R.string.edge_camera_available else R.string.edge_camera_unavailable),
                stringResource(R.string.edge_camera_setup)),
        )),
        SettingsGroup("tasks", stringResource(R.string.edge_task_list), listOf(
            SettingsEntry.Open("task-list", stringResource(R.string.edge_task_list), stringResource(R.string.edge_task_help),
                onTaskList, summary = taskListTitle ?: stringResource(R.string.edge_task_choose)),
        )),
        SettingsGroup("side", stringResource(R.string.side_screen), listOf(
            SettingsEntry.Open("side-screen", stringResource(R.string.side_screen), stringResource(R.string.side_screen_help), onSideScreen),
        )),
    ))
}
