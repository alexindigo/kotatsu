package com.alexindigo.kotatsu.weather

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect

object TimelineRenderer {
    fun render(
        context: Context, model: TimelineModel, width: Int, height: Int,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        if (model.rows.isEmpty()) return bitmap
        val density = context.resources.displayMetrics.density
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = context.resources.getFont(R.font.lato_regular)
            fontFeatureSettings = "tnum"
            strokeWidth = 1.5f * density
        }
        val axis = model.axis
        val layout = layout(context, model, width, height)
        val padding = layout.padding
        val left = layout.left
        val right = layout.right
        val rowHeight = layout.rowHeight
        val iconBox = layout.iconSize
        val iconLeft = layout.iconLeft
        val numberGap = layout.numberGap
        val dotRadius = minOf(1f * density, rowHeight / 8)
        model.lastUpdatedAt?.let { updated ->
            val text = WeatherPresentation.lastUpdated(context, updated, model.zone)
            paint.textSize = 1000f
            paint.letterSpacing = STATUS_TRACKING_EM
            val font = paint.fontMetrics
            val status = planStatusText(layout, width.toFloat(), density, context.resources.displayMetrics.scaledDensity,
                paint.measureText(text) / 1000, font.top / 1000, font.bottom / 1000)
            paint.textSize = status.textSize
            paint.color = Color.rgb(160, 160, 160)
            canvas.drawText(text, padding, status.baseline, paint)
        }
        paint.letterSpacing = 0f
        paint.textSize = layout.textSize
        val rows = model.rows.mapIndexed { index, row ->
            RenderRow(row, layout.rowTops[index] + layout.baselineOffset, layout.rowTops[index] + rowHeight / 2)
        }
        val placedRows = rows.map { row ->
            if (right <= left) emptyList() else placeNumbers(row.numbers, axis, left, right, numberGap, paint::measureText)
        }
        for ((index, row) in rows.withIndex()) {
            paint.color = if (row.model.stale) Color.rgb(160, 160, 160) else Color.BLACK
            paint.letterSpacing = LABEL_TRACKING_EM
            canvas.drawText(row.model.label, padding, row.baseline, paint)
            paint.letterSpacing = 0f
            if (row.model.state == RowState.NO_DATA || right <= left) {
                paint.color = Color.rgb(160, 160, 160)
                canvas.drawText("No forecast", padding + model.rows.maxOf { trackedLabelWidth(paint, it.label) } +
                    minOf(10f * density, width / 40f), row.baseline, paint)
                continue
            }
            for (hour in hourDots(axis, placedRows[index])) {
                canvas.drawCircle(axis.x(hour.toDouble(), left, right), row.y, dotRadius, paint)
            }
            val resource = when (row.model.condition) {
                Condition.THUNDERSTORM -> R.drawable.ic_weather_thunderstorm
                Condition.SNOW -> R.drawable.ic_weather_snowy
                Condition.RAIN -> R.drawable.ic_weather_rainy
                Condition.FOG -> R.drawable.ic_weather_foggy
                Condition.WIND -> R.drawable.ic_weather_wind
                Condition.CLOUDS -> R.drawable.ic_weather_cloudy
                Condition.CLEAR -> R.drawable.ic_weather_clear_day
            }
            context.getDrawable(resource)?.mutate()?.apply {
                setTint(if (row.model.stale) Color.rgb(160, 160, 160) else Color.BLACK)
                setBounds(iconLeft.toInt(), (row.y - iconBox / 2).toInt(), (iconLeft + iconBox).toInt(), (row.y + iconBox / 2).toInt())
                draw(canvas)
            }
        }
        // A single needle spans the stack of rows (not the empty space above it), beneath the number patches.
        model.needleHour?.let { axis.x(it, left, right) }?.takeIf { right > left }?.let { x ->
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
                paint.color = if (row.model.stale) Color.rgb(160, 160, 160) else Color.BLACK
                canvas.drawText(number.number.value.toString(), number.x - number.width / 2, row.baseline, paint)
            }
        }
        return bitmap
    }

    /** Measure at one large reference size to avoid rounded glyph bounds driving fitting or font searches. */
    internal fun layout(context: Context, model: TimelineModel, width: Int, height: Int, reserveStatus: Boolean = true): TimelineLayout {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = context.resources.getFont(R.font.lato_regular)
            fontFeatureSettings = "tnum"
            textSize = 1000f
        }
        val box = Rect()
        val glyphs = model.rows.joinToString("") { it.label } + "H0123456789−-No forecast"
        paint.getTextBounds(glyphs, 0, glyphs.length, box)
        val top = box.top / 1000f
        val bottom = box.bottom / 1000f
        paint.getTextBounds("H0123456789", 0, 11, box)
        val numbers = model.rows.flatMap { it.numbers }.map { it.value.toString() } + "-99"
        val metrics = TimelineMetrics(model.rows.maxOf { trackedLabelWidth(paint, it.label) } / 1000f,
            numbers.maxOf { paint.measureText(it) } / 1000f, paint.measureText("88") / 1000f,
            top, bottom, box.height() / 1000f, paint.measureText("No forecast") / 1000f,
            paint.fontMetrics.let { it.bottom - it.top } / 1000f)
        val display = context.resources.displayMetrics
        return planTimelineLayout(width.toFloat(), height.toFloat(), model.rows.size, display.density,
            display.scaledDensity, model.axis, metrics, reserveStatus)
    }

    /** Labels get a little tracking: Lato's default spacing reads tight at this size. Numbers keep tabular spacing. */
    private const val LABEL_TRACKING_EM = 0.05f
    /** Small grey text needs more air; applied to the same Paint for measurement and drawing. */
    private const val STATUS_TRACKING_EM = 0.075f

    private fun trackedLabelWidth(paint: Paint, label: String): Float {
        paint.letterSpacing = LABEL_TRACKING_EM
        return paint.measureText(label).also { paint.letterSpacing = 0f }
    }

    private data class RenderRow(
        val model: TimelineRow, val baseline: Float, val y: Float,
    ) { val numbers get() = model.numbers }
}
