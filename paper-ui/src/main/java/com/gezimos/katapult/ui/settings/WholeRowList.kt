package com.gezimos.katapult.ui.settings

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

data class WholeRowWindow(val first: Int, val end: Int, val maximum: Int, val height: Int)

/** Allocate measured whole rows, defaulting to the newest end, without a partial leading row. */
fun wholeRowWindow(heights: List<Int>, viewport: Int, requested: Int?, gap: Int): WholeRowWindow {
    if (heights.isEmpty()) return WholeRowWindow(0, 0, 0, 0)
    fun fitsFrom(first: Int): Pair<Int, Int> {
        var end = first
        var used = 0
        while (end < heights.size) {
            val next = heights[end] + if (end > first) gap else 0
            if (used + next > viewport && end > first) break
            used += next; end++
            if (used >= viewport) break
        }
        return end to used
    }
    var maximum = heights.lastIndex
    var used = heights.last()
    while (maximum > 0 && used + gap + heights[maximum - 1] <= viewport) {
        maximum--; used += gap + heights[maximum]
    }
    val first = (requested ?: maximum).coerceIn(0, maximum)
    val (end, height) = fitsFrom(first)
    return WholeRowWindow(first, end, maximum, height)
}

/** One measured layout owns row boundaries; the shared rail receives only row coordinates. */
@Composable
fun <T> WholeRowList(items: List<T>, labels: ScrollRailLabels, itemKey: (T) -> Any,
    reset: Any? = Unit, row: @Composable (T) -> Unit) {
    var requested by remember(reset) { mutableStateOf<Int?>(null) }
    var window by remember { mutableStateOf(WholeRowWindow(0, 0, 0, 0)) }
    var rowStep by remember { mutableFloatStateOf(1f) }
    val currentWindow by rememberUpdatedState(window)
    val currentStep by rememberUpdatedState(rowStep)
    fun moveTo(value: Int) { requested = value.coerceIn(0, window.maximum) }
    Row(Modifier.fillMaxSize()) {
        Layout(content = { items.forEach { item -> key(itemKey(item)) { row(item) } } },
            modifier = Modifier.weight(1f).fillMaxHeight()
                .padding(start = 16.dp, end = if (window.maximum > 0) 0.dp else 16.dp, bottom = 9.dp)
                .semantics {
                    verticalScrollAxisRange = ScrollAxisRange({ window.first.toFloat() }, { window.maximum.toFloat() })
                    scrollBy { _, y -> moveTo(window.first + (y / rowStep).roundToInt()); true }
                }.pointerInput(Unit) {
                    var remainder = 0f
                    detectVerticalDragGestures(onDragStart = { remainder = 0f }) { change, amount ->
                        change.consume()
                        remainder += amount
                        val rows = (remainder / currentStep).toInt()
                        if (rows != 0) {
                            requested = (currentWindow.first - rows).coerceIn(0, currentWindow.maximum)
                            remainder -= rows * currentStep
                        }
                    }
                }) { children, constraints ->
            val blocks = children.map { it.measure(Constraints(maxWidth = constraints.maxWidth)) }
            val gap = 2.25.dp.roundToPx()
            val next = wholeRowWindow(blocks.map { it.height }, constraints.maxHeight, requested, gap)
            window = next
            rowStep = if (next.end > next.first) (next.height + gap).toFloat() / (next.end - next.first) else 1f
            layout(constraints.maxWidth, constraints.maxHeight) {
                var y = (constraints.maxHeight - next.height).coerceAtLeast(0)
                for (index in next.first until next.end) {
                    blocks[index].placeRelative(0, y)
                    y += blocks[index].height + gap
                }
            }
        }
        if (window.maximum > 0) PaperScrollRail(window.end - window.first, window.maximum, window.first, labels, ::moveTo)
    }
}
