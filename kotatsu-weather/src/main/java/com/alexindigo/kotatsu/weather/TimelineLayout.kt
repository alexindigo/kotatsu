package com.alexindigo.kotatsu.weather

/** Font measurements per pixel of text size, taken once before planning (including every label's ink). */
data class TimelineMetrics(
    val labelWidth: Float, val numberWidth: Float, val neighbourWidth: Float,
    val top: Float, val bottom: Float, val capHeight: Float, val noForecastWidth: Float,
    val statusFontHeight: Float,
)

data class TimelineLayout(
    val textSize: Float, val rowHeight: Float, val rowPitch: Float, val rowTops: List<Float>,
    val baselineOffset: Float, val left: Float, val right: Float, val padding: Float,
    val iconSize: Float, val iconLeft: Float, val numberGap: Float,
    val statusTop: Float, val statusHeight: Float, val statusGap: Float,
)

data class StatusTextLayout(val textSize: Float, val baseline: Float)

/** A caption has half its planned font-wide line height of white before the weather rows. */
private const val STATUS_GAP_LINE_HEIGHTS = .5f
private const val STATUS_TEXT_SP = 12f

/** Font-wide bounds include accents and rasterization slack; a localized glyph cannot escape the lane. */
fun planStatusText(layout: TimelineLayout, width: Float, density: Float, scaledDensity: Float,
    textWidth: Float, fontTop: Float, fontBottom: Float): StatusTextLayout {
    val size = minOf(STATUS_TEXT_SP * scaledDensity,
        (layout.statusHeight - layout.statusGap - 2 * density).coerceAtLeast(.01f) / (fontBottom - fontTop),
        (width - 2 * layout.padding) / textWidth.coerceAtLeast(.01f))
    return StatusTextLayout(size, layout.statusTop + density - fontTop * size)
}

/** One analytic plan, before drawing. All dimensions are pixels; visibility never changes geometry. */
fun planTimelineLayout(
    width: Float, height: Float, count: Int, density: Float, scaledDensity: Float,
    axis: Axis, metrics: TimelineMetrics, reserveStatus: Boolean = false,
): TimelineLayout {
    require(width > 0 && height > 0 && count in 1..MAX_LOCATIONS && density > 0 && scaledDensity > 0)
    val padding = minOf(8f * density, width / 32)
    val bottomInset = minOf(16f * density, height / 10)
    val topInset = minOf(2f * density, height / 32)
    val statusLineHeight = if (reserveStatus) minOf(16f * density, height / 5) else 0f
    // Reserve using the intended caption size even when a long translation needs a smaller width-capped font.
    // Neither the caption's visibility nor its current timestamp can change the weather rows' geometry.
    val statusGap = if (reserveStatus) STATUS_GAP_LINE_HEIGHTS * minOf(
        STATUS_TEXT_SP * scaledDensity * metrics.statusFontHeight,
        (statusLineHeight - 2 * density).coerceAtLeast(0f)) else 0f
    val statusHeight = statusLineHeight + statusGap
    val icon = metrics.capHeight * 1.25f
    // A row box contains all label ink, numbers and the complete icon, not just capitals.
    val ink = maxOf(metrics.bottom - metrics.top, icon)
    val gapRatio = if (count >= 3) .5f else 1.6f
    val stackUnits = count * ink + (count - 1) * ink * gapRatio
    val heightCap = (height - bottomInset - topInset - statusHeight) / stackUnits
    val columnGap = minOf(10f * density, width / 40)
    val numberGap = minOf(3f * density, width / 100)
    val steps = (axis.end - axis.start).toFloat() / MARK_EVERY_HOURS
    val available = width - 2 * padding - 2 * columnGap
    val widthCap = (available - numberGap * steps) /
        (metrics.labelWidth + icon + metrics.numberWidth + metrics.neighbourWidth * steps)
    val noDataCap = (width - 2 * padding - columnGap) / (metrics.labelWidth + metrics.noForecastWidth)
    val size = minOf(20f * scaledDensity, heightCap, widthCap, noDataCap).coerceAtLeast(.01f)
    val rowHeight = ink * size
    val pitch = rowHeight * (1 + gapRatio)
    val tops = stackedRowBottoms(count, rowHeight, pitch - rowHeight, height - bottomInset).map { it - rowHeight }
    val iconSize = icon * size
    val iconLeft = width - padding - iconSize
    return TimelineLayout(size, rowHeight, pitch, tops,
        (rowHeight - (metrics.bottom - metrics.top) * size) / 2 - metrics.top * size,
        padding + metrics.labelWidth * size + columnGap + metrics.numberWidth * size / 2,
        iconLeft - columnGap - metrics.numberWidth * size / 2,
        padding, iconSize, iconLeft, numberGap, tops.first() - statusHeight, statusHeight, statusGap)
}
