package com.gezimos.katapult.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.dialog
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gezimos.katapult.ui.LocalInk
import com.gezimos.katapult.ui.LocalSurface

/** Window-independent sheet. The Activity/service host owns Back and the window lifetime. */
@Composable
fun PaperSheet(onDismiss: () -> Unit, modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit) {
    val ink = LocalInk.current
    Box(modifier.fillMaxSize().semantics { dialog() }, contentAlignment = Alignment.BottomCenter) {
        Box(Modifier.fillMaxSize().clickable(onClick = onDismiss))
        Column(Modifier.fillMaxWidth()
            .background(LocalSurface.current, RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
            .drawBehind {
                val half = 1.dp.toPx()
                val radius = 18.dp.toPx().coerceAtMost(size.width / 2)
                // One open path: no bottom edge, overlapping strokes or masking patches.
                val outline = Path().apply {
                    moveTo(half, size.height)
                    lineTo(half, radius)
                    arcTo(Rect(half, half, 2 * radius - half, 2 * radius - half), 180f, 90f, false)
                    lineTo(size.width - radius, half)
                    arcTo(Rect(size.width - 2 * radius + half, half, size.width - half, 2 * radius - half), 270f, 90f, false)
                    lineTo(size.width - half, size.height)
                }
                drawPath(outline, ink, style = Stroke(2.dp.toPx()))
            }
            .clickable { /* Body taps never dismiss or reach the underlying app. */ }
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = 10.dp), content = content)
    }
}

/** Page-header grammar within the sheet's existing content inset and scroll owner. */
@Composable
fun PaperSheetHeading(title: String) {
    Column(Modifier.fillMaxWidth()) {
        PageHeader(title, startInset = 0.dp)
        Box(Modifier.fillMaxWidth().height(2.dp).background(LocalInk.current))
    }
}
