package com.alexindigo.kotatsu.weather

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.time.ZonedDateTime

object TimelineRenderer {
    fun render(
        context: Context, config: WidgetConfig, forecasts: List<Forecast?>,
        width: Int, height: Int, now: ZonedDateTime = ZonedDateTime.now(),
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        if (config.locations.isEmpty()) return bitmap
        val density = context.resources.displayMetrics.density
        val scaledDensity = context.resources.displayMetrics.scaledDensity
        val rowHeight = height.toFloat() / config.locations.size
        val fontSp = (rowHeight / scaledDensity * .42f).coerceIn(14f, 20f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = fontSp * scaledDensity
            typeface = context.resources.getFont(R.font.lato_regular)
            fontFeatureSettings = "tnum"
            strokeWidth = 1.5f * density
        }
        val bold = context.resources.getFont(R.font.lato_bold)
        val regular = paint.typeface
        paint.typeface = bold
        val labelWidth = config.locations.maxOf { paint.measureText(it.label) }
        paint.typeface = regular
        val padding = 8 * density
        val iconSize = paint.textSize * 1.15f
        val halfNumber = paint.measureText("-99") / 2
        val left = padding + labelWidth + 10 * density + halfNumber
        val iconLeft = width - padding - iconSize
        val right = iconLeft - 10 * density - halfNumber
        val axis = sharedAxis(config.locations)
        val rows = config.locations.mapIndexed { index, location ->
            val forecast = forecasts.getOrNull(index)
            val numbers = forecast?.let { temperatureNumbers(location, it, now.toLocalDate()) }.orEmpty()
            RenderRow(location, forecast, numbers, (index + .5f) * rowHeight,
                forecast == null || isStale(forecast.fetchedAt, now.toInstant().toEpochMilli()))
        }
        for (row in rows) {
            paint.color = if (row.stale) Color.rgb(160, 160, 160) else Color.BLACK
            paint.typeface = bold
            canvas.drawText(row.location.label, padding, baseline(paint, row.y), paint)
            paint.typeface = regular
            if (row.numbers.isEmpty() || right <= left) {
                paint.color = Color.rgb(160, 160, 160)
                canvas.drawText("No forecast", padding + labelWidth + 10 * density, baseline(paint, row.y), paint)
                continue
            }
            val startX = axis.x(row.location.start.toDouble(), left, right)
            val endX = axis.x(row.location.end.toDouble(), left, right)
            canvas.drawLine(startX, row.y, endX, row.y, paint)
            paint.color = Color.rgb(160, 160, 160)
            var x = left
            while (x <= right) {
                if (x < startX - density || x > endX + density) canvas.drawCircle(x, row.y, .75f * density, paint)
                x += 5 * density
            }
            val condition = spanHours(row.location, row.forecast!!, now.toLocalDate())
                .maxOfOrNull { condition(it.code, it.gust, config.fahrenheit) } ?: Condition.CLEAR
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
                setBounds(iconLeft.toInt(), (row.y - iconSize / 2).toInt(), (iconLeft + iconSize).toInt(), (row.y + iconSize / 2).toInt())
                draw(canvas)
            }
        }
        // A single needle spans the complete stack, beneath the number patches.
        axis.nowX(now.hour + now.minute / 60.0, left, right)?.takeIf { right > left }?.let { x ->
            paint.color = Color.rgb(128, 128, 128)
            canvas.drawLine(x, padding, x, height - padding, paint)
        }
        for (row in rows) {
            if (right <= left) continue
            val placed = placeNumbers(row.numbers, axis, left, right, 5 * density, paint::measureText)
            for (number in placed) {
                paint.color = Color.WHITE
                val half = number.width / 2 + 3 * density
                canvas.drawRect(number.x - half, row.y + paint.fontMetrics.ascent / 2 - 2 * density,
                    number.x + half, row.y - paint.fontMetrics.ascent / 2 + 2 * density, paint)
                paint.color = if (row.stale) Color.rgb(160, 160, 160) else Color.BLACK
                canvas.drawText(number.number.value.toString(), number.x - number.width / 2, baseline(paint, row.y), paint)
            }
        }
        return bitmap
    }

    private fun baseline(paint: Paint, center: Float): Float = center - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2
    private data class RenderRow(val location: Location, val forecast: Forecast?, val numbers: List<TemperatureNumber>, val y: Float, val stale: Boolean)
}
