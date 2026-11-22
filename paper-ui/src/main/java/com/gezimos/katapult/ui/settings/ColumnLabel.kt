package com.gezimos.katapult.ui.settings

import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gezimos.katapult.ui.LatoFamily
import com.gezimos.katapult.ui.LocalInk

/** One measurement supplies the heading and every cell in its control column. */
@Immutable
data class ControlColumn internal constructor(val label: String, val width: Dp)

@Composable
fun rememberControlColumn(label: String): ControlColumn {
    val style = LocalTextStyle.current.copy(fontFamily = LatoFamily, fontSize = 14.sp, lineHeight = 17.25.sp)
    val measured = rememberTextMeasurer().measure(label, style = style, softWrap = false, maxLines = 1)
    val width = maxOf(64.dp, with(LocalDensity.current) { measured.size.width.toDp() })
    return remember(label, width) { ControlColumn(label, width) }
}

/** The full state label stays on one line without shrinking or ellipsis. */
@Composable
fun ColumnLabel(column: ControlColumn) {
    Text(column.label, fontFamily = LatoFamily, fontSize = 14.sp, lineHeight = 17.25.sp,
        color = LocalInk.current, textAlign = TextAlign.Center, softWrap = false, maxLines = 1,
        modifier = Modifier.width(column.width))
}
