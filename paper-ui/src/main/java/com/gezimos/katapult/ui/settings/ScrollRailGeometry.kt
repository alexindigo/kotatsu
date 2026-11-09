package com.gezimos.katapult.ui.settings

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Drawing and slot alignment share the actual rounded arrow-cell measurements. */
object ScrollRailGeometry {
    val arrowCell = 56.dp
    val arrowDrawing = 24.dp
    const val upTip = .2f
    fun topTip(density: Density): Float = with(density) {
        val cell = arrowCell.roundToPx()
        val drawing = arrowDrawing.roundToPx()
        ((cell - drawing) / 2f).roundToInt() + drawing * upTip
    }
    fun bottomTip(height: Int, density: Density): Float = with(density) {
        val cell = arrowCell.roundToPx()
        val drawing = arrowDrawing.roundToPx()
        height - cell + ((cell - drawing) / 2f).roundToInt() + drawing * (1 - upTip)
    }
    fun contentInsets(density: Density): Pair<Int, Int> =
        topTip(density).roundToInt() to (-bottomTip(0, density)).roundToInt()
}

data class RailThumb(val top: Float, val height: Float, val travel: Float)

fun railThumb(track: Float, viewport: Int, maximum: Int, position: Int, minimum: Float): RailThumb {
    val height = track.coerceAtLeast(0f)
    val thumb = (height * viewport.coerceAtLeast(0) / (viewport.toFloat() + maximum).coerceAtLeast(1f))
        .coerceIn(minimum.coerceIn(0f, height), height)
    val travel = height - thumb
    return RailThumb(travel * position.coerceIn(0, maximum.coerceAtLeast(0)) / maximum.coerceAtLeast(1), thumb, travel)
}
