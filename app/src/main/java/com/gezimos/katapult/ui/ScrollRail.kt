package com.gezimos.katapult.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.gezimos.katapult.R
import com.gezimos.katapult.ui.settings.ScrollRailLabels

/** App-owned localization; the shared rail only knows coordinates and callbacks. */
@Composable
fun scrollRailLabels() = ScrollRailLabels(stringResource(R.string.edge_task_scroll),
    stringResource(R.string.edge_task_scroll_up), stringResource(R.string.edge_task_scroll_down))
