package com.alexindigo.kotatsu.weather

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.ZonedDateTime

@RunWith(AndroidJUnit4::class)
class TimelineLayoutInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val now = ZonedDateTime.parse("2026-10-30T08:30:00-07:00[America/Los_Angeles]")
    private val series = ForecastSeries((0..48).map { h -> HourSample(now.toLocalDate().atStartOfDay(now.zone)
        .toInstant().plusSeconds(h * 3600L), if (h == 13) 90.0 else 58.0, 2, null) }, now.toInstant())

    @Test fun allRowsRenderInsideTheirPlannedBoxesAtKompaktSizes() {
        val density = context.resources.displayMetrics.density
        val directory = File(context.getExternalFilesDir(null), "renders/layout").apply { mkdirs() }
        for (heightDp in listOf(64, 112, 160)) for (count in 1..4) for (missing in listOf(false, true)) {
            val config = WidgetConfig(listOf("Palo Alt", "Portola", "Oakland", "HalfMoon").take(count)
                .map { Location(it, it, 10.0, 20.0) })
            val model = buildTimeline(config, List(count) { if (missing) null else series }, emptyList(), now).copy(needleHour = null)
            val width = (344 * density).toInt()
            val height = (heightDp * density).toInt()
            val plan = TimelineRenderer.layout(context, model, width, height)
            val bitmap = TimelineRenderer.render(context, model, width, height)
            for (y in 0 until height) for (x in 0 until width) if (bitmap.getPixel(x, y) != Color.WHITE) {
                assertTrue("Ink outside rows: $heightDp/$count/$missing at $x,$y", plan.rowTops.any { y >= it - 1 && y <= it + plan.rowHeight + 1 })
                assertTrue("Ink at horizontal edge", x > 0 && x < width - 1)
            }
            plan.rowTops.forEach { top ->
                assertTrue("Missing label $heightDp/$count/$top", (top.toInt()..(top + plan.rowHeight).toInt()).any { y ->
                    (plan.padding.toInt() until plan.left.toInt()).any { x -> bitmap.getPixel(x, y) != Color.WHITE }
                })
            }
            File(directory, "$heightDp-$count-${if (missing) "missing" else "fresh"}.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        }
    }
}
