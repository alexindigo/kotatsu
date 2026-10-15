package com.gezimos.katapult.lockscreen

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gezimos.katapult.ui.AppIconCircle
import com.gezimos.katapult.ui.LocalIconSize

sealed interface PanelCell {
    data class AppIcon(val bitmap: Bitmap?) : PanelCell
}

/** The launchable map is in label order (AppLoader's order); values are launch activities. */
fun selectPanelApps(
    order: List<String>, hidden: Set<String>, launchable: Map<String, String>, count: Int,
): List<Pair<String, String>> = (order + launchable.keys)
    .asSequence()
    .distinct()
    .filter { it !in hidden && '|' !in it && it in launchable }
    .take(count.coerceAtLeast(0))
    .map { it to launchable.getValue(it) }
    .toList()

@Composable
fun LockscreenPanel(cells: List<PanelCell>) {
    val size = LocalIconSize.current
    Column(
        Modifier.fillMaxSize().padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, androidx.compose.ui.Alignment.Bottom),
    ) {
        cells.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                row.forEach { cell ->
                    when (cell) {
                        is PanelCell.AppIcon -> AppIconCircle(cell.bitmap, size)
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.size(size)) }
            }
        }
    }
}
