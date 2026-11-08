package com.gezimos.katapult.lockscreen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.gezimos.katapult.R
import com.gezimos.katapult.ui.LocalInk
import com.gezimos.katapult.ui.NoIndication
import kotlin.math.roundToInt

@Composable
internal fun Modifier.railFocus(interactions: MutableInteractionSource): Modifier {
    val focused by interactions.collectIsFocusedAsState()
    val ink = LocalInk.current
    return drawBehind { if (focused) drawRect(ink, style = Stroke(2.dp.toPx())) }
}

/** Shared drawing and input; the consumer owns the scroll coordinate domain and commands. */
@Composable
internal fun NotesScrollRail(viewport: Int, maximum: Int, position: Int, moveTo: (Int) -> Unit,
                             step: (Int) -> Unit = { direction -> moveTo((position + direction * viewport / 2).coerceIn(0, maximum)) }) {
    val ink = LocalInk.current
    val currentPosition by rememberUpdatedState(position)
    val currentMaximum by rememberUpdatedState(maximum)
    val currentViewport by rememberUpdatedState(viewport)
    val currentMove by rememberUpdatedState(moveTo)
    val interactions = remember { MutableInteractionSource() }
    val scrollLabel = stringResource(R.string.edge_task_scroll)
    Column(Modifier.fillMaxHeight().width(ScrollRailGeometry.arrowCell)) {
        ScrollArrow(false, position > 0) { step(-1) }
        Canvas(Modifier.weight(1f).fillMaxWidth().railFocus(interactions).focusable(interactionSource = interactions).semantics {
            contentDescription = scrollLabel
            progressBarRangeInfo = ProgressBarRangeInfo(position.toFloat(), 0f..maximum.coerceAtLeast(1).toFloat())
            setProgress { moveTo(it.roundToInt()); true }
        }.pointerInput(Unit) {
            // A gesture owns its displacement, not the consumer's asynchronous feedback.
            // Freeze track travel for this gesture, but map its fraction into the latest
            // measured range. Wrapped native rows may change that range on every layout.
            var fraction = 0f
            var travel = 1f
            detectVerticalDragGestures(onDragStart = {
                fraction = currentPosition.toFloat() / currentMaximum.coerceAtLeast(1)
                travel = railThumb(size.height.toFloat(), currentViewport, currentMaximum,
                    currentPosition, 24.dp.toPx()).travel.coerceAtLeast(1f)
            }) { change, amount ->
                change.consume()
                fraction = (fraction + amount / travel).coerceIn(0f, 1f)
                currentMove((fraction * currentMaximum).roundToInt())
            }
        }) {
            val width = 8.dp.toPx()
            val left = (size.width - width) / 2
            val radius = CornerRadius(width / 2)
            drawRoundRect(ink, Offset(left, 0f), Size(width, size.height), radius, style = Stroke(1.5.dp.toPx()))
            val thumb = railThumb(size.height, viewport, maximum, position, 24.dp.toPx())
            drawRoundRect(ink, Offset(left, thumb.top), Size(width, thumb.height), radius)
        }
        ScrollArrow(true, position < maximum) { step(1) }
    }
}

@Composable
private fun ScrollArrow(down: Boolean, available: Boolean, onClick: () -> Unit) {
    val ink = LocalInk.current
    val label = stringResource(if (down) R.string.edge_task_scroll_down else R.string.edge_task_scroll_up)
    val interactions = remember { MutableInteractionSource() }
    Box(Modifier.size(ScrollRailGeometry.arrowCell).railFocus(interactions)
        .clickable(enabled = available, interactionSource = interactions, indication = NoIndication, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(ScrollRailGeometry.arrowDrawing)) {
            val path = Path().apply {
                moveTo(size.width * .1f, size.height * if (down) .3f else .7f)
                lineTo(size.width * .9f, size.height * if (down) .3f else .7f)
                lineTo(size.width * .5f, size.height * if (down) 1 - ScrollRailGeometry.upTip else ScrollRailGeometry.upTip)
                close()
            }
            if (available) drawPath(path, ink, style = androidx.compose.ui.graphics.drawscope.Fill)
            else clipPath(path) {
                val step = 1.5.dp.toPx()
                var y = 0f
                while (y < size.height) {
                    var x = 0f
                    while (x < size.width) { drawCircle(ink, .3.dp.toPx(), Offset(x, y)); x += step }
                    y += step
                }
            }
        }
    }
}
