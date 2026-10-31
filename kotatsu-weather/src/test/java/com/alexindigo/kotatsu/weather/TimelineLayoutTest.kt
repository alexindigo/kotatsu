package com.alexindigo.kotatsu.weather

import org.junit.Assert.*
import org.junit.Test

class TimelineLayoutTest {
    private val metrics = TimelineMetrics(5.5f, 1.5f, 1.16f, -.8f, .2f, .74f, 5.2f)

    @Test fun everyHostSizeContainsEveryPlaceWithOrWithoutReservedStatusAtEveryDensity() {
        for (density in listOf(1f, 4f / 3, 2f, 3f)) for (height in listOf(64, 112, 160))
            for (count in 1..4) for (slot in listOf(false, true)) {
                val plan = planTimelineLayout(344 * density, height * density, count, density, density,
                    Axis(7, 23), metrics, slot)
                assertEquals(count, plan.rowTops.size)
                assertTrue("font $height/$count/$slot", plan.textSize / density >= 6f)
                assertTrue(plan.textSize / density <= 20f)
                assertTrue(plan.statusTop >= 0)
                assertTrue(plan.rowTops.last() + plan.rowHeight <= height * density)
                assertTrue(plan.left < plan.right)
                assertTrue(metrics.neighbourWidth * plan.textSize + plan.numberGap <=
                    (plan.right - plan.left) * MARK_EVERY_HOURS / 16 + .001f)
                plan.rowTops.zipWithNext().forEach { (a, b) -> assertTrue(a + plan.rowHeight < b) }
                if (height >= 112 && count == 3) assertTrue(plan.textSize / density >= 14f)
            }
    }

    @Test fun widthCapsTheWholeRowIncludingLabelsNegativeNumbersAndNoForecast() {
        for (width in listOf(200f, 344f, 464f)) {
            val plan = planTimelineLayout(width, 112f, 4, 1f, 1f, Axis(7, 23), metrics)
            assertTrue(plan.padding + metrics.labelWidth * plan.textSize < plan.left - metrics.numberWidth * plan.textSize / 2)
            assertTrue(plan.right + metrics.numberWidth * plan.textSize / 2 < plan.iconLeft)
            assertTrue(plan.iconLeft + plan.iconSize <= width - plan.padding + .001f)
            assertTrue((metrics.labelWidth + metrics.noForecastWidth) * plan.textSize + 2 * plan.padding + 10 <= width + .001f)
        }
    }

    @Test fun fontScaleCannotPushRowsOutOfTheirAllocation() {
        val normal = planTimelineLayout(344f, 64f, 4, 1f, 1f, Axis(7, 23), metrics, true)
        val enlarged = planTimelineLayout(344f, 64f, 4, 1f, 2f, Axis(7, 23), metrics, true)
        assertEquals(normal, enlarged)
    }
}
