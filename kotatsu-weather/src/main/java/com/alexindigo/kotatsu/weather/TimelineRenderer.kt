package com.alexindigo.kotatsu.weather

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import java.time.ZonedDateTime

object TimelineRenderer {
    fun render(
        context: Context, config: WidgetConfig, forecasts: List<ForecastSeries?>,
        width: Int, height: Int, now: ZonedDateTime = ZonedDateTime.now(),
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        if (config.locations.isEmpty()) return bitmap
        val density = context.resources.displayMetrics.density
        val scaledDensity = context.resources.displayMetrics.scaledDensity
        val count = config.locations.size
        // Rows stack from the bottom, so the text needn't shrink as lines are added; the solver below lowers it
        // only when the axis or the widget's height runs out.
        val maxTextSize = MAX_TEXT_SP * scaledDensity
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = context.resources.getFont(R.font.lato_regular)
            fontFeatureSettings = "tnum"
            strokeWidth = 1.5f * density
        }
        val padding = 8 * density
        val axis = sharedAxis(config.locations)
        val dotRadius = 1f * density
        // White kept around each number: the needle stops this far from the digits, and neighbouring numbers are
        // sized so this white never reaches each other's digits.
        val numberGap = 3f * density
        // Rows are as tall as the visible text (capitals and digits, including the round digits' overshoot) and
        // sit at the bottom: rowGapDp() between rows, BOTTOM_INSET_DP under the last one.
        val rowGap = rowGapDp(count) * density
        val bottomInset = BOTTOM_INSET_DP * density
        val glyphs = Rect()
        fun glyphBox(size: Float): Rect {
            paint.textSize = size
            paint.getTextBounds(ROW_GLYPHS, 0, ROW_GLYPHS.length, glyphs)
            return glyphs
        }
        fun rowHeightAt(size: Float): Float = glyphBox(size).height().toFloat()
        // The icon box is a little taller than the text so its glyph reads as the text's height; the extra is the
        // icon's own transparent margin, so it may overhang the row (and the bottom edge) without being visible.
        fun iconSize(size: Float) = rowHeightAt(size) * ICON_TO_CAP
        // Labels and numbers share one size: the largest at which numbers on neighbouring marks fit side by side
        // and the stack fits the widget's height. Smaller labels widen the axis, so the size is solved, not derived.
        fun axisBounds(size: Float): Pair<Float, Float> {
            val iconLeft = width - padding - iconSize(size)
            paint.textSize = size
            val labelWidth = config.locations.maxOf { trackedLabelWidth(paint, it.label) }
            val halfNumber = paint.measureText("-99") / 2
            return padding + labelWidth + 10 * density + halfNumber to iconLeft - 10 * density - halfNumber
        }
        val textSize = largestFitting(maxTextSize, 1f) { size ->
            val (l, r) = axisBounds(size)
            r > l && paint.measureText("88") <= maxNumberWidth((r - l) / (axis.end - axis.start) * MARK_EVERY_HOURS, numberGap) &&
                stackHeight(count, rowHeightAt(size), rowGap) + bottomInset <= height
        }
        val (left, right) = axisBounds(textSize)
        val rowHeight = rowHeightAt(textSize)
        val iconBox = iconSize(textSize)
        val iconLeft = width - padding - iconBox
        paint.textSize = textSize
        val labelWidth = config.locations.maxOf { trackedLabelWidth(paint, it.label) }
        val belowBaseline = glyphBox(textSize).bottom.toFloat()
        paint.textSize = textSize
        val bottoms = stackedRowBottoms(count, rowHeight, rowGap, height - bottomInset)
        val day = DayWindow.of(now.toLocalDate(), now.zone)
        val rows = config.locations.mapIndexed { index, location ->
            val forecast = forecasts.getOrNull(index)
            val numbers = forecast?.let { temperatureNumbers(location, it, day) }.orEmpty()
            RenderRow(location, forecast, numbers, bottoms[index] - belowBaseline, bottoms[index] - rowHeight / 2,
                forecast == null || isStale(forecast.fetchedAt.toEpochMilli(), now.toInstant().toEpochMilli()))
        }
        val placedRows = rows.map { row ->
            if (right <= left) emptyList() else placeNumbers(row.numbers, axis, left, right, numberGap, paint::measureText)
        }
        for ((index, row) in rows.withIndex()) {
            paint.color = if (row.stale) Color.rgb(160, 160, 160) else Color.BLACK
            paint.letterSpacing = LABEL_TRACKING_EM
            canvas.drawText(row.location.label, padding, row.baseline, paint)
            paint.letterSpacing = 0f
            if (row.numbers.isEmpty() || right <= left) {
                paint.color = Color.rgb(160, 160, 160)
                canvas.drawText("No forecast", padding + labelWidth + 10 * density, row.baseline, paint)
                continue
            }
            for (hour in hourDots(axis, placedRows[index])) {
                canvas.drawCircle(axis.x(hour.toDouble(), left, right), row.y, dotRadius, paint)
            }
            val condition = spanHours(row.location, row.forecast!!, day)
                .mapNotNull { condition(it.code, it.gust, config.fahrenheit) }.maxOrNull() ?: Condition.CLEAR
            val resource = when (condition) {
                Condition.THUNDERSTORM -> R.drawable.ic_weather_thunderstorm
                Condition.SNOW -> R.drawable.ic_weather_snowy
                Condition.RAIN -> R.drawable.ic_weather_rainy
                Condition.FOG -> R.drawable.ic_weather_foggy
                Condition.WIND -> R.drawable.ic_weather_wind
                Condition.CLOUDS -> R.drawable.ic_weather_cloudy
                Condition.CLEAR -> R.drawable.ic_weather_clear_day
            }
            context.getDrawable(resource)?.mutate()?.apply {
                setTint(if (row.stale) Color.rgb(160, 160, 160) else Color.BLACK)
                setBounds(iconLeft.toInt(), (row.y - iconBox / 2).toInt(), (iconLeft + iconBox).toInt(), (row.y + iconBox / 2).toInt())
                draw(canvas)
            }
        }
        // A single needle spans the stack of rows (not the empty space above it), beneath the number patches.
        axis.nowX(now.hour + now.minute / 60.0, left, right)?.takeIf { right > left }?.let { x ->
            paint.color = Color.rgb(128, 128, 128)
            canvas.drawLine(x, rows.first().y - iconBox / 2, x, rows.last().y + iconBox / 2, paint)
        }
        for ((index, row) in rows.withIndex()) {
            if (right <= left) continue
            for (number in placedRows[index]) {
                paint.color = Color.WHITE
                // The patch never reaches a neighbouring mark's number or dot (see maxNumberWidth).
                val half = number.width / 2 + numberGap
                canvas.drawRect(number.x - half, row.y - rowHeight / 2 - numberGap,
                    number.x + half, row.y + rowHeight / 2 + numberGap, paint)
                paint.color = if (row.stale) Color.rgb(160, 160, 160) else Color.BLACK
                canvas.drawText(number.number.value.toString(), number.x - number.width / 2, row.baseline, paint)
            }
        }
        return bitmap
    }

    /** Labels get a little tracking: Lato's default spacing reads tight at this size. Numbers keep tabular spacing. */
    private const val LABEL_TRACKING_EM = 0.05f

    private fun trackedLabelWidth(paint: Paint, label: String): Float {
        paint.letterSpacing = LABEL_TRACKING_EM
        return paint.measureText(label).also { paint.letterSpacing = 0f }
    }

    /** Largest text, the top of the agreed 14–20 sp range. */
    private const val MAX_TEXT_SP = 20f
    /** White the widget keeps under its last row; with the lock-screen box's 16 dp bottom padding the line sits 32 dp up. */
    private const val BOTTOM_INSET_DP = 16f
    /** Icon box relative to the text's capital height; the glyph inside then reads as the same height. */
    private const val ICON_TO_CAP = 1.25f
    /** What a row's visible height is measured on: capitals and the digits, round ones overshooting both ways. */
    private const val ROW_GLYPHS = "H0123456789"

    private data class RenderRow(
        val location: Location, val forecast: ForecastSeries?, val numbers: List<TemperatureNumber>,
        val baseline: Float, val y: Float, val stale: Boolean,
    )
}
